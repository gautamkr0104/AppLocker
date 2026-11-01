package com.applocker;

import static org.junit.Assert.*;

import com.applocker.core.Policy;

import org.junit.Test;

public class PolicyTest {

    @Test
    public void validityBoundaries() {
        assertTrue(Policy.isValid(Policy.IMMEDIATE));
        assertTrue(Policy.isValid(Policy.SCREEN_OFF));
        assertTrue(Policy.isValid(Policy.TIMEOUT));
        assertTrue(Policy.isValid(Policy.SESSION));
        assertFalse(Policy.isValid(-1));
        assertFalse(Policy.isValid(4));
    }

    @Test
    public void screenOffAlwaysRelocks() {
        assertFalse(Policy.grantValid(Policy.IMMEDIATE, 0, 0, 30, true, false));
        assertFalse(Policy.grantValid(Policy.SCREEN_OFF, 0, 0, 30, true, false));
        assertFalse(Policy.grantValid(Policy.TIMEOUT, 0, 0, 30, true, false));
        assertFalse(Policy.grantValid(Policy.SESSION, 0, 0, 30, true, false));
    }

    @Test
    public void immediateRelocksWhenOtherAppResumes() {
        assertFalse(Policy.grantValid(Policy.IMMEDIATE, 1000, 1001, 30, false, true));
        assertTrue(Policy.grantValid(Policy.IMMEDIATE, 1000, 1001, 30, false, false));
    }

    @Test
    public void immediateGrantHardExpiresEvenIfRevocationEventWasMissed() {
        long granted = 1_000_000L;
        // Fresh grant survives (covers the auth -> target handoff).
        assertTrue(Policy.grantValid(Policy.IMMEDIATE, granted, granted + 1_000, 30, false, false));
        // Past the cap the grant is dead even though no other-app resume was observed.
        assertFalse(Policy.grantValid(Policy.IMMEDIATE, granted,
                granted + Policy.IMMEDIATE_MAX_AGE_MS + 1L, 30, false, false));
    }

    @Test
    public void timeoutExpires() {
        long granted = 1_000_000L;
        assertTrue(Policy.grantValid(Policy.TIMEOUT, granted, granted + 29_000, 30, false, false));
        assertFalse(Policy.grantValid(Policy.TIMEOUT, granted, granted + 31_000, 30, false, false));
        // TIMEOUT does not relock merely because another app resumed
        assertTrue(Policy.grantValid(Policy.TIMEOUT, granted, granted + 100, 30, false, true));
    }

    @Test
    public void sessionStaysValidUntilScreenOff() {
        assertTrue(Policy.grantValid(Policy.SESSION, 0, Long.MAX_VALUE / 2, 5, false, true));
    }

    @Test
    public void screenOffPolicyStaysValidWhileScreenOn() {
        assertTrue(Policy.grantValid(Policy.SCREEN_OFF, 0, Long.MAX_VALUE / 2, 5, false, true));
    }

    @Test
    public void unknownPolicyFailsClosed() {
        assertFalse(Policy.grantValid(99, 0, 0, 30, false, false));
    }
}
