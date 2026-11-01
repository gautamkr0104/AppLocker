package com.applocker.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.applocker.core.Const;
import com.applocker.core.Denylist;
import com.applocker.core.Policy;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * State and side-effect surface for the hook code running inside system_server.
 *
 * THREADING CONTRACT (learned from a real watchdog deadlock on SM-A035F, 2026-10-02):
 *
 *   The resume hook runs INSIDE com.android.server.wm under the WindowManager global
 *   lock (RootWindowContainer.resumeFocusedTasksTopActivities -> hook). Doing ANY
 *   blocking/binder work there (registerReceiver, ContentResolver.call, ...) creates
 *   ABBA deadlocks against threads holding the AMS lock and waiting for the WM lock,
 *   which trips the platform watchdog and kills system_server.
 *
 *   Therefore, from hook context the ONLY allowed operations are:
 *     - reading volatile fields / ConcurrentHashMap lookups
 *     - Handler.post() onto our own dedicated background HandlerThread
 *   All IPC (receiver registration, provider calls, startActivity for the auth
 *   screen) happens on that dedicated thread, which never holds framework locks.
 *
 * Responsibilities:
 *  - lazily initialize on the background thread (config fetch, receivers)
 *  - cache protected-set config (signature-gated broadcast refresh + TTL check
 *    that only runs when a relevant launch is actually evaluated -- no timers)
 *  - hold per-package authentication grants in memory (never persisted here)
 *  - launch the authentication Activity after a launch was blocked
 *  - report hook-install diagnostics back to the manager app
 */
public final class SystemBridge {

    private SystemBridge() {}

    static final String TAG = "AppLocker";
    static final String MODULE = Const.PKG;

    private static final long CONFIG_TTL_MS = 10_000L;
    private static final long INIT_RETRY_MS = 2_000L;

    /** Immutable snapshot of manager configuration as seen by the hook side. */
    static final class Config {
        final boolean enabled;
        final int policy;
        final int timeoutSec;
        final int version;
        final int authVersion;
        final Set<String> protectedPkgs;

        Config(boolean enabled, int policy, int timeoutSec, int version, int authVersion, Set<String> protectedPkgs) {
            this.enabled = enabled;
            this.policy = policy;
            this.timeoutSec = timeoutSec;
            this.version = version;
            this.authVersion = authVersion;
            this.protectedPkgs = protectedPkgs;
        }

        static Config disabled() {
            return new Config(false, Policy.DEFAULT, Policy.DEFAULT_TIMEOUT_SEC, 0, 0, java.util.Collections.emptySet());
        }

        static Config from(Bundle b) {
            Set<String> pkgs = new java.util.HashSet<>();
            String enc = b.getString("protected", "");
            if (enc != null && !enc.isEmpty()) {
                for (String line : enc.split("\n")) {
                    String p = line.trim();
                    if (!p.isEmpty()) pkgs.add(p);
                }
            }
            return new Config(
                    b.getBoolean("enabled", false),
                    b.getInt("policy", Policy.DEFAULT),
                    b.getInt("timeoutSec", Policy.DEFAULT_TIMEOUT_SEC),
                    b.getInt("version", 0),
                    b.getInt("authVersion", 0),
                    pkgs);
        }
    }

    /** In-memory authentication grant for one package. */
    static final class Grant {
        final long at;        // wall-clock ms
        final int policy;     // policy snapshot at grant time
        Grant(long at, int policy) { this.at = at; this.policy = policy; }
    }

    // ---- hot-path state (volatile / concurrent only; readable under any lock) ----
    static volatile Config cfg = Config.disabled();
    static volatile long cfgFetchedAt = 0L;
    static volatile boolean initialized = false;
    static volatile Context ctx = null;
    static volatile boolean debug = false;

    static final ConcurrentHashMap<String, Grant> grants = new ConcurrentHashMap<>();

    // ---- background infrastructure (only touched from bg thread / lock-free posts) ----
    private static volatile Handler bg = null;
    private static volatile boolean cfgFetchPending = false;
    private static final AtomicBoolean initScheduled = new AtomicBoolean(false);
    private static final AtomicBoolean reported = new AtomicBoolean(false);

    // Auth launch dedupe state
    static volatile String lastAuthPkg = null;
    static volatile long lastAuthAt = -10_000L;

    // Hook diagnostics (filled by Hooks.install)
    static volatile String hookReport = "";

    // ------------------------------------------------------------------
    // Background thread
    // ------------------------------------------------------------------

    private static Handler bgHandler() {
        Handler h = bg;
        if (h != null) return h;
        synchronized (SystemBridge.class) {
            if (bg == null) {
                HandlerThread t = new HandlerThread("applocker-bridge");
                t.start();
                bg = new Handler(t.getLooper());
            }
            return bg;
        }
    }

    // ------------------------------------------------------------------
    // Initialization (always runs on the background thread)
    // ------------------------------------------------------------------

    /**
     * Schedule (never execute inline!) an init attempt. Safe to call from hook
     * context: only does a volatile read and a Handler.post.
     */
    static void ensureInitDeferred() {
        if (initialized) return;
        if (!initScheduled.compareAndSet(false, true)) return;
        try {
            bgHandler().post(() -> {
                initScheduled.set(false);
                ensureInitOnBg();
            });
        } catch (Throwable t) {
            initScheduled.set(false);
        }
    }

    /** True when bridge is fully ready (context + receivers). Gate fails open until then. */
    static boolean isReady() {
        return initialized && ctx != null;
    }

    /** Runs on the background thread only. Never under framework locks. */
    private static void ensureInitOnBg() {
        if (initialized) return;
        try {
            Context c = acquireSystemContext();
            if (c == null) {
                // Context not ready yet (very early boot): retry shortly, on this thread.
                bgHandler().postDelayed(SystemBridge::ensureInitOnBg, INIT_RETRY_MS);
                return;
            }
            Handler h = bgHandler();
            ctx = c;

            // Config refresh: only our signed senders can deliver (broadcastPermission).
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    refreshConfigAsync();
                }
            }, new IntentFilter(Const.ACTION_CONFIG_CHANGED), Const.PERMISSION, h);

            // Screen-off relock: ACTION_SCREEN_OFF is a protected broadcast (system-only).
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    grants.clear();
                    if (debug) Log.d(TAG, "screen off: grants cleared");
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_OFF), null, h);

            // Grant delivery from AuthActivity after successful verification.
            // Gated by broadcastPermission=INTERNAL (signature): only apps signed with
            // our key can deliver. Runs before AuthActivity's ordered-broadcast result
            // callback, so the grant is installed before the protected app resumes.
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    String pkg = intent == null ? null : intent.getStringExtra(Const.EXTRA_PKG);
                    if (pkg == null || pkg.isEmpty()) return;
                    if (Denylist.isDenied(pkg)) return;
                    grants.put(pkg, new Grant(System.currentTimeMillis(), cfg.policy));
                    if (debug) Log.d(TAG, "grant installed: " + pkg);
                }
            }, new IntentFilter(Const.ACTION_GRANT), Const.PERMISSION, h);

            initialized = true;
            refreshConfigAsync();

            // Report hook status once shortly after boot (transient provider call,
            // not a service and not a persistent process dependency).
            h.postDelayed(SystemBridge::reportHooksOnce, 20_000L);
            if (debug) Log.d(TAG, "bridge initialized");
        } catch (Throwable t) {
            logErr("init failed", t);
            bgHandler().postDelayed(SystemBridge::ensureInitOnBg, INIT_RETRY_MS * 3);
        }
    }

    private static Context acquireSystemContext() {
        try {
            Method m = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread");
            Object at = m.invoke(null);
            if (at != null) {
                try {
                    Object app = at.getClass().getMethod("getSystemContext").invoke(at);
                    if (app instanceof Context) return (Context) app;
                } catch (Throwable ignored) { }
                try {
                    Object app = at.getClass().getMethod("getApplication").invoke(at);
                    if (app instanceof Context) return (Context) app;
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ------------------------------------------------------------------
    // Config (all execution on bg thread)
    // ------------------------------------------------------------------

    /** Called from hook context: posts a refresh if config is stale. Never blocks. */
    static void ensureFreshConfig() {
        if (initialized && (System.currentTimeMillis() - cfgFetchedAt) <= CONFIG_TTL_MS) return;
        refreshConfigAsync();
    }

    static void refreshConfigAsync() {
        if (cfgFetchPending) return;
        cfgFetchPending = true;
        try {
            bgHandler().post(() -> {
                cfgFetchPending = false;
                Context c = ctx;
                if (c == null) return;
                try {
                    Bundle b = c.getContentResolver().call(
                            Uri.parse("content://" + Const.AUTHORITY), "getConfig", null, null);
                    if (b == null) return;
                    Config neu = Config.from(b);
                    Config old = cfg;
                    // Relock everything on: disable->enable edges, policy changes, credential changes.
                    if (old.enabled && (!neu.enabled || neu.policy != old.policy || neu.authVersion != old.authVersion)) {
                        grants.clear();
                    }
                    cfg = neu;
                    cfgFetchedAt = System.currentTimeMillis();
                } catch (Throwable t) {
                    // Manager app not installed/disabled: keep last config (default = no protection).
                    if (debug) Log.d(TAG, "config refresh failed: " + t);
                }
            });
        } catch (Throwable t) {
            cfgFetchPending = false;
        }
    }

    // ------------------------------------------------------------------
    // Protection / grant checks (hot path, lock-free)
    // ------------------------------------------------------------------

    /** Hot path: is this package protected right now? */
    static boolean isProtectedNow(String pkg) {
        Config c = cfg;
        if (!c.enabled || pkg == null) return false;
        if (Denylist.isDenied(pkg)) return false;   // defense in depth: denylist wins in system too
        return c.protectedPkgs.contains(pkg);
    }

    /** Hot path: is a valid authentication grant present for this package? */
    static boolean isGranted(String pkg) {
        Grant g = grants.get(pkg);
        if (g == null) return false;
        Config c = cfg;
        // Unified policy decision (unit-tested pure logic in core.Policy).
        boolean valid = Policy.grantValid(g.policy, g.at, System.currentTimeMillis(),
                c.timeoutSec, false /* screen-off handled by explicit revoke */, false);
        if (!valid) grants.remove(pkg, g);
        return valid;
    }

    /** Called when an activity of {@code resumedPkg} reaches RESUMED (lock-free). */
    static void onActivityResumed(String resumedPkg) {
        if (grants.isEmpty()) return;
        Config c = cfg;
        if (!c.enabled) { grants.clear(); return; }
        // IMMEDIATE policy: any other package resuming means the user left the app.
        if (c.policy != Policy.IMMEDIATE) return;
        for (java.util.Map.Entry<String, Grant> e : grants.entrySet()) {
            if (!e.getKey().equals(resumedPkg)) grants.remove(e.getKey(), e.getValue());
        }
    }

    // ------------------------------------------------------------------
    // Authentication Activity launch (posted; never inline in hook)
    // ------------------------------------------------------------------

    static void launchAuth(String pkg, int taskId) {
        ensureInitDeferred();
        if (!isReady()) return;   // gate fails open if bridge not ready (tiny boot window)

        long now = android.os.SystemClock.uptimeMillis();
        // Dedupe: rapid duplicate launches must not stack auth screens (test N).
        if (pkg.equals(lastAuthPkg) && (now - lastAuthAt) < 900L) return;
        lastAuthPkg = pkg;
        lastAuthAt = now;

        try {
            bgHandler().post(() -> {
                Context c = ctx;
                if (c == null) return;
                try {
                    Intent i = new Intent();
                    i.setClassName(MODULE, Const.AUTH_ACTIVITY);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    i.putExtra(Const.EXTRA_PKG, pkg);
                    String label = resolveLabel(c, pkg);
                    if (label != null) i.putExtra(Const.EXTRA_LABEL, label);

                    if (taskId >= 0) {
                        // Prefer attaching auth to the blocked task so that finishing auth
                        // naturally resumes the protected activity underneath.
                        try {
                            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                            Method setLaunchTaskId = android.app.ActivityOptions.class
                                    .getMethod("setLaunchTaskId", int.class);
                            setLaunchTaskId.invoke(o, taskId);
                            c.startActivity(i, o.toBundle());
                            return;
                        } catch (Throwable ignored) {
                            // fall through to plain start (own task)
                        }
                    }
                    c.startActivity(i);
                } catch (Throwable t) {
                    logErr("auth launch failed", t);
                }
            });
        } catch (Throwable t) {
            logErr("auth launch post failed", t);
        }
    }

    private static String resolveLabel(Context c, String pkg) {
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = c.getPackageManager().getApplicationLabel(ai);
            return label != null ? label.toString() : pkg;
        } catch (Throwable t) {
            return pkg;
        }
    }

    // ------------------------------------------------------------------
    // Diagnostics reporting (no secrets ever)
    // ------------------------------------------------------------------

    private static void reportHooksOnce() {
        if (!reported.compareAndSet(false, true)) return;
        report(hookReport);
    }

    /** Push a diagnostics string to the manager provider (uid-validated there). */
    static void report(String text) {
        try {
            bgHandler().post(() -> {
                Context c = ctx;
                if (c == null) return;
                try {
                    c.getContentResolver().call(
                            Uri.parse("content://" + Const.AUTHORITY), "report", text, null);
                } catch (Throwable t) {
                    if (debug) Log.d(TAG, "report failed: " + t);
                }
            });
        } catch (Throwable ignored) { }
    }

    static void logErr(String what, Throwable t) {
        Log.e(TAG, what + ": " + t);
        try {
            de.robv.android.xposed.XposedBridge.log("AppLocker " + what + ": " + t);
        } catch (Throwable ignored) { }
    }
}
