package com.applocker.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Non-overridable denylist: packages that can never be protected.
 * Prevents bootloop-class mistakes (SystemUI, Settings, installers, launcher,
 * our own package which must be able to run for authentication).
 *
 * Pure Java (no Android imports) so both the app UI and the system_server hook
 * side share the exact same logic and it is unit-testable on the JVM.
 */
public final class Denylist {

    private Denylist() {}

    private static final Set<String> LOCK = new HashSet<>();

    static {
        // The locker itself (auth + manager must always run)
        add(Const.PKG);

        // System UI / status bar / notification shade / keyguard host
        add("com.android.systemui");
        add("com.samsung.android.app.cocktailbarservice"); // One UI Edge panels (SystemUI helper)

        // Settings
        add("com.android.settings");
        add("com.samsung.android.settings");
        add("com.sec.android.app.safetycenter"); // older Samsung settings companion

        // Package installers / permission controllers
        add("com.android.packageinstaller");
        add("com.google.android.packageinstaller");
        add("com.samsung.android.packageinstaller");
        add("com.google.android.permissioncontroller");
        add("com.samsung.android.permissioncontroller");
        add("com.android.permissioncontroller");

        // Launchers (home must always be reachable or the phone locks up for the user)
        add("com.sec.android.app.launcher");
        add("com.android.launcher3");
        add("com.google.android.apps.nexuslauncher");
        add("com.sec.android.app.twlauncher");
        add("com.android.launcher");

        // In-call UI: locking it can break an active phone call
        add("com.samsung.android.incallui");
        add("com.android.dialer");
        add("com.samsung.android.dialer");
        add("com.android.server.telecom");

        // Recovery paths: keep LSPosed manager accessible so the module can always be disabled
        add("org.lsposed.manager");
        add("org.lsposed.manager.debug");

        // Never lock the framework package itself (not launchable, but be explicit)
        add("android");
    }

    private static void add(String p) { LOCK.add(p); }

    /** True if this package must never be protected (hard deny, not overridable). */
    public static boolean isDenied(String pkg) {
        if (pkg == null) return true;
        String p = pkg.toLowerCase(Locale.ROOT);
        if (LOCK.contains(p)) return true;
        // Defensive: anything in the android/system server namespace
        return p.startsWith("com.android.server.");
    }

    /** Snapshot of denied packages (for diagnostics display). */
    public static Set<String> all() {
        return Collections.unmodifiableSet(LOCK);
    }
}
