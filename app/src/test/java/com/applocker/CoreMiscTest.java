package com.applocker;

import static org.junit.Assert.*;

import com.applocker.core.Denylist;
import com.applocker.core.ProtectedSetCodec;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class CoreMiscTest {

    @Test
    public void codecRoundTrip() {
        List<String> in = Arrays.asList("com.whatsapp", "com.instagram.android", "org.telegram.messenger");
        String enc = ProtectedSetCodec.encode(in);
        assertEquals(in, ProtectedSetCodec.decode(enc));
    }

    @Test
    public void codecEmptyAndNullSafe() {
        assertTrue(ProtectedSetCodec.decode(null).isEmpty());
        assertTrue(ProtectedSetCodec.decode("").isEmpty());
        assertTrue(ProtectedSetCodec.encode(null).isEmpty());
        assertTrue(ProtectedSetCodec.encode(java.util.Collections.emptyList()).isEmpty());
    }

    @Test
    public void codecSkipsGarbage() {
        String enc = ProtectedSetCodec.encode(Arrays.asList("", null, "com.ok"));
        assertEquals(java.util.Collections.singletonList("com.ok"), ProtectedSetCodec.decode(enc));
        // decode tolerates \r\n and blank lines
        assertEquals(2, ProtectedSetCodec.decode("a.b\n\r\nc.d\n\n").size());
    }

    @Test
    public void denylistBlocksCriticalPackages() {
        assertTrue(Denylist.isDenied("com.android.systemui"));
        assertTrue(Denylist.isDenied("com.android.settings"));
        assertTrue(Denylist.isDenied("com.samsung.android.settings"));
        assertTrue(Denylist.isDenied("com.sec.android.app.launcher"));
        assertTrue(Denylist.isDenied("com.google.android.permissioncontroller"));
        assertTrue(Denylist.isDenied("com.applocker"));
        assertTrue(Denylist.isDenied("org.lsposed.manager"));
        assertTrue(Denylist.isDenied(null));
        assertTrue(Denylist.isDenied("com.android.server.foo"));
    }

    @Test
    public void denylistAllowsNormalApps() {
        assertFalse(Denylist.isDenied("com.whatsapp"));
        assertFalse(Denylist.isDenied("com.instagram.android"));
        assertFalse(Denylist.isDenied("org.telegram.messenger"));
    }
}
