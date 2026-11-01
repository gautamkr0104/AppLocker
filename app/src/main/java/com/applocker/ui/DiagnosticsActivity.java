package com.applocker.ui;

import android.app.Activity;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import com.applocker.R;
import com.applocker.core.Const;
import com.applocker.core.CryptoStore;
import com.applocker.core.Policy;
import com.applocker.core.Repo;

/**
 * Diagnostics: hook status, config state, device identity, audit summary.
 * Shows only non-sensitive state (never PIN/password material).
 */
public class DiagnosticsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        Repo repo = new Repo(this);
        CryptoStore store = new CryptoStore(this);

        String hooks = "";
        long hookTs = 0;
        try {
            Bundle b = getContentResolver().call(
                    Uri.parse("content://" + Const.AUTHORITY), "diag", null, null);
            if (b != null) {
                hooks = b.getString("hooks", "");
                hookTs = b.getLong("ts", 0);
            }
        } catch (Throwable t) {
            hooks = "provider error: " + t;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("== Device ==\n");
        sb.append("Model: ").append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android ").append(Build.VERSION.RELEASE)
          .append(" / API ").append(Build.VERSION.SDK_INT).append("\n");
        sb.append("One UI: ").append(getProp("ro.build.version.oneui")).append("\n");
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n\n");

        sb.append("== Module ==\n");
        if (hooks.contains("ok=[")) {
            sb.append("Status: LOADED in system_server\n");
        } else if (hooks.isEmpty()) {
            sb.append("Status: NOT REPORTED (module not enabled, or reboot pending)\n");
        } else {
            sb.append("Status: PARTIAL / ERROR\n");
        }
        sb.append("Report: ").append(hooks.isEmpty() ? "-" : hooks).append("\n");
        if (hookTs > 0) {
            sb.append("Reported at: ").append(new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(hookTs))).append("\n");
        }
        sb.append("\n== Configuration ==\n");
        sb.append("Locker enabled: ").append(repo.isEnabled()).append("\n");
        sb.append("PIN set: ").append(store.isPinSet())
          .append(" (").append(store.getMode() == CryptoStore.MODE_PASSWORD ? "password" : "PIN").append(")\n");
        sb.append("Policy: ").append(Policy.name(repo.getPolicy())).append("\n");
        if (repo.getPolicy() == Policy.TIMEOUT) {
            sb.append("Timeout: ").append(repo.getTimeoutSec()).append("s\n");
        }
        sb.append("Protected apps: ").append(repo.getProtected().size()).append("\n");
        sb.append("Config version: ").append(repo.getVersion()).append("\n");
        sb.append("Auth version: ").append(repo.getAuthVersion()).append("\n\n");

        sb.append("== Permission audit ==\n");
        sb.append("Declared permissions: com.applocker.permission.INTERNAL (signature)\n");
        sb.append("Accessibility: NONE\n");
        sb.append("Notification listener: NONE\n");
        sb.append("Usage access: NONE\n");
        sb.append("Foreground service: NONE\n");
        sb.append("Internet: NONE\n");
        sb.append("Package visibility: launcher-activity query only\n");

        ((TextView) findViewById(R.id.diag_text)).setText(sb.toString());
        findViewById(R.id.diag_back).setOnClickListener(v -> finish());
    }

    private static String getProp(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, name);
            return v == null ? "?" : v.toString();
        } catch (Throwable t) {
            return "?";
        }
    }
}
