package com.applocker.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import com.applocker.core.Const;
import com.applocker.core.CryptoStore;
import com.applocker.core.Policy;
import com.applocker.core.ProtectedSetCodec;
import com.applocker.core.Repo;

/**
 * IPC surface consumed by the system_server hook side.
 *
 * call() methods:
 *   "getConfig" - returns Bundle{enabled, policy, timeoutSec, protected, version, pinSet}
 *                 Restricted to uid 1000 (system) and our own uid.
 *   "report"    - hook diagnostics written BY the module (arg = JSON-ish key=value lines).
 *                 Restricted to uid 1000 (system) and our own uid.
 *   "diag"      - reads back stored hook diagnostics (manager UI). Own uid only (system too).
 *
 * query/insert/update/delete are unsupported (return null / throw): this provider
 * exists only as a narrow, uid-validated call surface.
 */
public final class LockerProvider extends ContentProvider {

    private static final String PREFS = "applocker_diag";
    private static final String K_HOOKS = "hooks_report";
    private static final String K_TS = "hooks_report_ts";

    private boolean allowed() {
        int uid = Binder.getCallingUid();
        return uid == 1000 || uid == android.os.Process.myUid();
    }

    private boolean selfOnly() {
        return Binder.getCallingUid() == android.os.Process.myUid();
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override public String getType(Uri uri) { return null; }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new SecurityException("unsupported");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new SecurityException("unsupported");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new SecurityException("unsupported");
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (method == null) return null;
        switch (method) {
            case "getConfig": {
                if (!allowed()) throw new SecurityException("getConfig restricted");
                Context c = getContext();
                if (c == null) return null;
                Repo repo = new Repo(c);
                CryptoStore store = new CryptoStore(c);
                Bundle b = new Bundle();
                b.putBoolean("enabled", repo.isEnabled() && store.isPinSet());
                b.putInt("policy", repo.getPolicy());
                b.putInt("timeoutSec", repo.getTimeoutSec());
                b.putString("protected", ProtectedSetCodec.encode(repo.getProtected()));
                b.putInt("version", repo.getVersion());
                b.putInt("authVersion", repo.getAuthVersion());
                b.putBoolean("pinSet", store.isPinSet());
                return b;
            }
            case "report": {
                if (!allowed()) throw new SecurityException("report restricted");
                Context c = getContext();
                if (c == null) return null;
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(K_HOOKS, arg == null ? "" : arg)
                        .putLong(K_TS, System.currentTimeMillis())
                        .apply();
                return new Bundle();
            }
            case "diag": {
                if (!selfOnly()) throw new SecurityException("diag restricted");
                Context c = getContext();
                if (c == null) return null;
                android.content.SharedPreferences sp =
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                Bundle b = new Bundle();
                b.putString("hooks", sp.getString(K_HOOKS, ""));
                b.putLong("ts", sp.getLong(K_TS, 0L));
                return b;
            }
            default:
                return null;
        }
    }
}
