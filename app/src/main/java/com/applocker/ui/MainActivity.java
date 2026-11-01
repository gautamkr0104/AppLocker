package com.applocker.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.applocker.R;
import com.applocker.core.Const;
import com.applocker.core.CryptoStore;
import com.applocker.core.Repo;

/**
 * Manager home: status, master switch (safe disable), navigation.
 * The manager never hides itself and never disguises itself.
 */
public class MainActivity extends Activity {

    private Repo repo;
    private CryptoStore store;

    private TextView statusView;
    private TextView moduleView;
    private TextView countView;
    private Switch masterSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        repo = new Repo(this);
        store = new CryptoStore(this);

        if (!store.isPinSet()) {
            // First run: PIN creation first, everything else after.
            startActivity(new Intent(this, SetupActivity.class)
                    .putExtra(Const.EXTRA_MODE, Const.MODE_SETUP));
            // fall through to layout so back from setup still shows the manager
        }

        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status_text);
        moduleView = findViewById(R.id.module_text);
        countView = findViewById(R.id.protected_count);
        masterSwitch = findViewById(R.id.master_switch);

        masterSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (!store.isPinSet()) {
                btn.setChecked(false);
                Toast.makeText(this, R.string.need_pin_first, Toast.LENGTH_SHORT).show();
                return;
            }
            repo.setEnabled(checked);
            refresh();
        });

        findViewById(R.id.row_apps).setOnClickListener(v ->
                startActivity(new Intent(this, AppListActivity.class)));
        findViewById(R.id.row_policy).setOnClickListener(v ->
                startActivity(new Intent(this, PolicyActivity.class)));
        findViewById(R.id.row_change_pin).setOnClickListener(v -> {
            Intent i = new Intent(this, SetupActivity.class);
            i.putExtra(Const.EXTRA_MODE, Const.MODE_CHANGE);
            startActivity(i);
        });
        findViewById(R.id.row_diagnostics).setOnClickListener(v ->
                startActivity(new Intent(this, DiagnosticsActivity.class)));
        findViewById(R.id.row_lsposed).setOnClickListener(v -> {
            // Convenience: open LSPosed Manager if installed (module activation help).
            Intent i = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
            if (i != null) startActivity(i);
            else Toast.makeText(this, R.string.lsposed_not_found, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean enabled = repo.isEnabled() && store.isPinSet();
        masterSwitch.setChecked(enabled);
        statusView.setText(enabled ? R.string.status_on : R.string.status_off);
        statusView.setBackgroundResource(enabled ? R.drawable.bg_chip_on : R.drawable.bg_glass_tile);
        statusView.setTextColor(getColor(enabled ? R.color.ok_green : R.color.on_header));
        countView.setText(getString(R.string.protected_count_fmt, repo.getProtected().size()));

        String hooks = "";
        try {
            Bundle b = getContentResolver().call(
                    Uri.parse("content://" + Const.AUTHORITY), "diag", null, null);
            if (b != null) hooks = b.getString("hooks", "");
        } catch (Throwable ignored) { }

        boolean loaded = hooks.contains("ok=[");
        moduleView.setText(loaded ? R.string.module_loaded : R.string.module_not_loaded);
        moduleView.setTextColor(getColor(loaded ? R.color.ok_green : R.color.warn_orange));
    }
}
