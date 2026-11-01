package com.applocker.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Encoding for the protected-package set: one package name per line.
 * Newlines can never occur in Android package names, so this is lossless,
 * trivially testable, and avoids org.json in unit tests.
 */
public final class ProtectedSetCodec {

    private ProtectedSetCodec() {}

    public static String encode(Collection<String> pkgs) {
        if (pkgs == null || pkgs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (p == null || p.isEmpty() || p.contains("\n")) continue;
            sb.append(p).append('\n');
        }
        return sb.toString();
    }

    public static List<String> decode(String encoded) {
        List<String> out = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) return out;
        int start = 0;
        while (start <= encoded.length()) {
            int nl = encoded.indexOf('\n', start);
            if (nl < 0) nl = encoded.length();
            String line = encoded.substring(start, nl).trim();
            if (!line.isEmpty()) out.add(line);
            start = nl + 1;
        }
        return out;
    }
}
