package com.applocker;

import static org.junit.Assert.*;

import com.applocker.core.PinHasher;

import org.junit.Test;

public class PinHasherTest {

    @Test
    public void sameCredentialSameHash() {
        byte[] salt = PinHasher.newSalt();
        byte[] a = PinHasher.hash("1234".toCharArray(), salt, 10_000);
        byte[] b = PinHasher.hash("1234".toCharArray(), salt, 10_000);
        assertArrayEquals(a, b);
    }

    @Test
    public void differentCredentialDifferentHash() {
        byte[] salt = PinHasher.newSalt();
        byte[] a = PinHasher.hash("1234".toCharArray(), salt, 10_000);
        byte[] b = PinHasher.hash("1235".toCharArray(), salt, 10_000);
        assertFalse(PinHasher.constantTimeEquals(a, b));
    }

    @Test
    public void differentSaltDifferentHash() {
        byte[] a = PinHasher.hash("1234".toCharArray(), PinHasher.newSalt(), 10_000);
        byte[] b = PinHasher.hash("1234".toCharArray(), PinHasher.newSalt(), 10_000);
        assertFalse(PinHasher.constantTimeEquals(a, b));
    }

    @Test
    public void hexRoundTrip() {
        byte[] salt = PinHasher.newSalt();
        String hex = PinHasher.toHex(salt);
        assertArrayEquals(salt, PinHasher.fromHex(hex));
    }

    @Test
    public void verifyAcceptsCorrectRejectsWrong() {
        byte[] salt = PinHasher.newSalt();
        String stored = PinHasher.toHex(PinHasher.hash("9876".toCharArray(), salt, 10_000));
        assertTrue(PinHasher.verify("9876".toCharArray(), salt, 10_000, stored));
        assertFalse(PinHasher.verify("9875".toCharArray(), salt, 10_000, stored));
        assertFalse(PinHasher.verify(null, salt, 10_000, stored));
        assertFalse(PinHasher.verify("9876".toCharArray(), salt, 10_000, null));
    }

    @Test
    public void hashLengthIs256Bits() {
        byte[] h = PinHasher.hash("x".toCharArray(), PinHasher.newSalt(), 10_000);
        assertEquals(32, h.length);
    }

    @Test
    public void constantTimeEqualsHandlesNullAndLengthMismatch() {
        assertFalse(PinHasher.constantTimeEquals(null, new byte[]{1}));
        assertFalse(PinHasher.constantTimeEquals(new byte[]{1}, null));
        assertFalse(PinHasher.constantTimeEquals(new byte[]{1}, new byte[]{1, 2}));
        assertTrue(PinHasher.constantTimeEquals(new byte[]{1, 2}, new byte[]{1, 2}));
    }
}
