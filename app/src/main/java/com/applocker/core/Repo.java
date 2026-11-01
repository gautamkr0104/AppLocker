package com.applocker.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Manager-side configuration storage (protected set, policy, enable flag, version).
 * Every mutation bumps an internal version and notifies the hook side via a
 * signature-gated broadcast; the hook side then re-reads config through the provider.
 *
 * Config survives reboot (SharedPreferences) and is never uploaded anywhere.
 */
public final class Repo {

    private static final String PREFS = "applocker_config";

    private static final String K_ENABLED = "enabled";
    private static final String K_POLICY = "policy";
    private static final String K_TIMEOUT = "timeout_sec";
    private static final String K_PROTECTED = "protected_pkgs";
    private static final String K_VERSION = "version";
    private static final String K_AUTH_VERSION = "auth_version";

    private final SharedPreferences sp;
    private final Context ctx;

    public Repo(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return sp.getBoolean(K_ENABLED, false);
    }

    /** Master switch ("safe disable"). Turning off notifies the hook side immediately. */
    public void setEnabled(boolean enabled) {
        mutate().putBoolean(K_ENABLED, enabled).apply();
    }

    public int getPolicy() {
        int p = sp.getInt(K_POLICY, Policy.DEFAULT);
        return Policy.isValid(p) ? p : Policy.DEFAULT;
    }

    public void setPolicy(int policy) {
        mutate().putInt(K_POLICY, Policy.isValid(policy) ? policy : Policy.DEFAULT).apply();
    }

    public int getTimeoutSec() {
        int t = sp.getInt(K_TIMEOUT, Policy.DEFAULT_TIMEOUT_SEC);
        return t >= 5 && t <= 3600 ? t : Policy.DEFAULT_TIMEOUT_SEC;
    }

    public void setTimeoutSec(int sec) {
        mutate().putInt(K_TIMEOUT, Math.max(5, Math.min(3600, sec))).apply();
    }

    public Set<String> getProtected() {
        return new LinkedHashSet<>(ProtectedSetCodec.decode(sp.getString(K_PROTECTED, "")));
    }

    public void setProtected(Set<String> pkgs) {
        mutate().putString(K_PROTECTED, ProtectedSetCodec.encode(pkgs)).apply();
    }

    public boolean isProtected(String pkg) {
        return getProtected().contains(pkg);
    }

    public void setProtected(String pkg, boolean protect) {
        Set<String> s = getProtected();
        boolean changed = protect ? s.add(pkg) : s.remove(pkg);
        if (changed) setProtected(s);
    }

    public int getVersion() {
        return sp.getInt(K_VERSION, 0);
    }

    public int getAuthVersion() {
        return sp.getInt(K_AUTH_VERSION, 0);
    }

    /**
     * Call after every credential (PIN/password) change. The hook side clears all
     * in-memory grants whenever this value changes.
     */
    public void bumpAuthVersion() {
        mutate().putInt(K_AUTH_VERSION, sp.getInt(K_AUTH_VERSION, 0) + 1).apply();
    }

    /** Begin a mutation: bumps the config version; call apply() to persist + broadcast. */
    public SharedPreferences.Editor mutate() {
        SharedPreferences.Editor e = sp.edit().putInt(K_VERSION, sp.getInt(K_VERSION, 0) + 1);
        return new BroadcastingEditor(e);
    }

    private final class BroadcastingEditor implements SharedPreferences.Editor {
        private final SharedPreferences.Editor delegate;
        BroadcastingEditor(SharedPreferences.Editor d) { this.delegate = d; }

        @Override public SharedPreferences.Editor putString(String k, String v) { delegate.putString(k, v); return this; }
        @Override public SharedPreferences.Editor putStringSet(String k, Set<String> v) { delegate.putStringSet(k, v); return this; }
        @Override public SharedPreferences.Editor putInt(String k, int v) { delegate.putInt(k, v); return this; }
        @Override public SharedPreferences.Editor putLong(String k, long v) { delegate.putLong(k, v); return this; }
        @Override public SharedPreferences.Editor putFloat(String k, float v) { delegate.putFloat(k, v); return this; }
        @Override public SharedPreferences.Editor putBoolean(String k, boolean v) { delegate.putBoolean(k, v); return this; }
        @Override public SharedPreferences.Editor remove(String k) { delegate.remove(k); return this; }
        @Override public SharedPreferences.Editor clear() { delegate.clear(); return this; }
        @Override public boolean commit() { boolean ok = delegate.commit(); if (ok) notifyHooks(); return ok; }
        @Override public void apply() { delegate.apply(); notifyHooks(); }
    }

    /** Notify the system_server hook side that config changed (gated by our signature permission). */
    public void notifyHooks() {
        try {
            // Delivered to the dynamic receiver registered by the module inside system_server;
            // that receiver was registered with broadcastPermission=INTERNAL, so only
            // senders holding our signature permission can reach it. No package targeting
            // needed (dynamic receivers live in the "android" package context).
            ctx.sendBroadcast(new Intent(Const.ACTION_CONFIG_CHANGED));
        } catch (Throwable ignored) {
            // Never crash the manager if broadcast delivery fails; TTL refresh is the fallback.
        }
    }
}
