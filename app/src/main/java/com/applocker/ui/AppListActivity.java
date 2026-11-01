package com.applocker.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.applocker.R;
import com.applocker.core.Denylist;
import com.applocker.core.Repo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Launchable-app picker: name, package, icon, protect switch, search.
 * Uses ACTION_MAIN/CATEGORY_LAUNCHER query visibility only (no QUERY_ALL_PACKAGES).
 */
public class AppListActivity extends Activity {

    private Repo repo;
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private Adapter adapter;

    static final class Entry {
        final String pkg;
        final String label;
        final Drawable icon;
        Entry(String pkg, String label, Drawable icon) {
            this.pkg = pkg; this.label = label; this.icon = icon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_list);
        repo = new Repo(this);

        ListView list = findViewById(R.id.app_list);
        EditText search = findViewById(R.id.app_search);
        adapter = new Adapter();
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { filter(s.toString()); }
        });

        findViewById(R.id.app_back).setOnClickListener(v -> finish());

        loadApps();
        filter("");
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<android.content.pm.ResolveInfo> ris = pm.queryIntentActivities(
                new android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0);
        for (android.content.pm.ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            CharSequence label = ri.loadLabel(pm);
            Drawable icon;
            try { icon = ri.loadIcon(pm); } catch (Throwable t) { icon = null; }
            all.add(new Entry(pkg, label == null ? pkg : label.toString(), icon));
        }
        // Sort by label, case-insensitive
        all.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
    }

    private void filter(String q) {
        shown.clear();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        for (Entry e : all) {
            if (needle.isEmpty()
                    || e.label.toLowerCase(Locale.ROOT).contains(needle)
                    || e.pkg.toLowerCase(Locale.ROOT).contains(needle)) {
                shown.add(e);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.app_row, parent, false);
            }
            Entry e = shown.get(position);
            ((ImageView) v.findViewById(R.id.row_icon)).setImageDrawable(e.icon);
            ((TextView) v.findViewById(R.id.row_label)).setText(e.label);
            ((TextView) v.findViewById(R.id.row_pkg)).setText(e.pkg);

            Switch sw = v.findViewById(R.id.row_switch);
            sw.setOnCheckedChangeListener(null);
            boolean denied = Denylist.isDenied(e.pkg);
            boolean prot = repo.isProtected(e.pkg);
            sw.setChecked(prot);
            sw.setEnabled(!denied);
            v.findViewById(R.id.row_denied).setVisibility(denied ? View.VISIBLE : View.GONE);

            sw.setOnCheckedChangeListener((btn, checked) -> {
                if (Denylist.isDenied(e.pkg)) {
                    btn.setChecked(false);
                    Toast.makeText(AppListActivity.this,
                            R.string.denylist_toast, Toast.LENGTH_SHORT).show();
                    return;
                }
                repo.setProtected(e.pkg, checked);
            });
            return v;
        }
    }
}
