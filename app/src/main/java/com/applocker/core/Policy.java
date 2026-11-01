package com.applocker.core;

/**
 * Relock policy definitions (pure Java, shared + unit-tested).
 *
 * Policies:
 *  0 IMMEDIATE   - lock as soon as another app resumes or the screen turns off (secure default)
 *  1 SCREEN_OFF  - lock only on screen off (session-like while the screen stays on)
 *  2 TIMEOUT     - lock N seconds after authentication (screen off always locks too)
 *  3 SESSION     - stay unlocked until screen off
 *
 * Screen-off always revokes every grant regardless of policy.
 */
public final class Policy {

    private Policy() {}

    public static final int IMMEDIATE = 0;
    public static final int SCREEN_OFF = 1;
    public static final int TIMEOUT = 2;
    public static final int SESSION = 3;

    public static final int DEFAULT = IMMEDIATE;
    public static final int DEFAULT_TIMEOUT_SEC = 30;

    /**
     * Hard age cap for IMMEDIATE grants. Revocation for this policy is
     * event-driven (another package's activity must reach RESUMED); if that
     * transition is missed (stuck WM state, rotation racing the gate) a grant
     * could otherwise live forever and act as a silent bypass. The grant only
     * needs to bridge the auth-screen -> protected-activity handoff (< 1s),
     * so 10s is a generous bound.
     */
    public static final int IMMEDIATE_MAX_AGE_MS = 10_000;

    public static boolean isValid(int policy) {
        return policy >= IMMEDIATE && policy <= SESSION;
    }

    public static String name(int policy) {
        switch (policy) {
            case IMMEDIATE: return "Lock immediately when leaving the app";
            case SCREEN_OFF: return "Lock on screen off only";
            case TIMEOUT: return "Lock after timeout";
            case SESSION: return "Session unlock (until screen off)";
            default: return "Unknown";
        }
    }

    /**
     * Decide whether a grant remains valid.
     *
     * @param policy         active policy
     * @param grantedAtMs    wall-clock time the grant was created
     * @param nowMs          current wall-clock time
     * @param timeoutSec     timeout used for TIMEOUT policy
     * @param screenOff      true exactly once per screen-off event
     * @param otherAppResumed true when a different package's activity resumed since the grant
     * @return true if the protected app may be shown without re-authentication
     */
    public static boolean grantValid(int policy, long grantedAtMs, long nowMs,
                                     int timeoutSec, boolean screenOff, boolean otherAppResumed) {
        if (screenOff) return false; // screen off always relocks
        switch (policy) {
            case IMMEDIATE:
                return !otherAppResumed
                        && (nowMs - grantedAtMs) <= IMMEDIATE_MAX_AGE_MS;
            case SCREEN_OFF:
            case SESSION:
                return true;
            case TIMEOUT:
                long ms = Math.max(1, timeoutSec) * 1000L;
                return (nowMs - grantedAtMs) <= ms;
            default:
                return false; // fail closed on unknown policy
        }
    }
}
