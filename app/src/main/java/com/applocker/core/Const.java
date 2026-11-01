package com.applocker.core;

/** Shared constants between the manager app and the system_server hook side. */
public final class Const {
    private Const() {}

    /** Our application/package name. */
    public static final String PKG = "com.applocker";

    /** Auth activity class (fully qualified). */
    public static final String AUTH_ACTIVITY = "com.applocker.ui.AuthActivity";

    /** Content provider authority used by the hook side to read config / write diagnostics. */
    public static final String AUTHORITY = "com.applocker.provider";

    /**
     * Signature-level permission. The system_server receiver registers with this as
     * broadcastPermission, so only senders holding it (i.e. apps signed with our key)
     * can deliver config/grant broadcasts. Not held by system UID 1000 either.
     */
    public static final String PERMISSION = "com.applocker.permission.INTERNAL";

    /** Bump on every config mutation; hook side uses it for cache invalidation diagnostics. */
    public static final String ACTION_CONFIG_CHANGED = "com.applocker.action.CONFIG_CHANGED";

    /** Sent (ordered) by AuthActivity after successful PIN verification. */
    public static final String ACTION_GRANT = "com.applocker.action.GRANT";

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_TASK_ID = "task_id";
    public static final String EXTRA_MODE = "mode";

    /** Setup/Change PIN modes for SetupActivity. */
    public static final String MODE_SETUP = "setup";
    public static final String MODE_CHANGE = "change";
}
