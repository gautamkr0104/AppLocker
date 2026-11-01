package com.applocker.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import com.applocker.R;
import com.applocker.core.Policy;
import com.applocker.core.Repo;

/** Relock policy configuration. */
public class PolicyActivity extends Activity {

    private Repo repo;
    private EditText timeoutEdit;
    private TextView timeoutLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_policy);
        repo = new Repo(this);

        timeoutEdit = findViewById(R.id.timeout_edit);
        timeoutLabel = findViewById(R.id.timeout_label);
        timeoutEdit.setText(String.valueOf(repo.getTimeoutSec()));

        int cur = repo.getPolicy();
        RadioButton rb = findViewById(policyRadio(cur));
        if (rb != null) rb.setChecked(true);
        updateTimeoutVisibility(cur);

        findViewById(R.id.policy_immediate).setOnClickListener(v -> updateTimeoutVisibility(Policy.IMMEDIATE));
        findViewById(R.id.policy_screen_off).setOnClickListener(v -> updateTimeoutVisibility(Policy.SCREEN_OFF));
        findViewById(R.id.policy_timeout).setOnClickListener(v -> updateTimeoutVisibility(Policy.TIMEOUT));
        findViewById(R.id.policy_session).setOnClickListener(v -> updateTimeoutVisibility(Policy.SESSION));

        findViewById(R.id.policy_save).setOnClickListener(v -> save());
        findViewById(R.id.policy_back).setOnClickListener(v -> finish());
    }

    private int policyRadio(int policy) {
        switch (policy) {
            case Policy.IMMEDIATE: return R.id.policy_immediate;
            case Policy.SCREEN_OFF: return R.id.policy_screen_off;
            case Policy.TIMEOUT: return R.id.policy_timeout;
            case Policy.SESSION: return R.id.policy_session;
            default: return R.id.policy_immediate;
        }
    }

    private int selectedPolicy() {
        if (findViewById(R.id.policy_immediate) instanceof RadioButton
                && ((RadioButton) findViewById(R.id.policy_immediate)).isChecked()) return Policy.IMMEDIATE;
        if (((RadioButton) findViewById(R.id.policy_screen_off)).isChecked()) return Policy.SCREEN_OFF;
        if (((RadioButton) findViewById(R.id.policy_timeout)).isChecked()) return Policy.TIMEOUT;
        if (((RadioButton) findViewById(R.id.policy_session)).isChecked()) return Policy.SESSION;
        return Policy.DEFAULT;
    }

    private void updateTimeoutVisibility(int policy) {
        boolean show = policy == Policy.TIMEOUT;
        timeoutLabel.setVisibility(show ? View.VISIBLE : View.GONE);
        timeoutEdit.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void save() {
        int policy = selectedPolicy();
        repo.setPolicy(policy);
        if (policy == Policy.TIMEOUT) {
            try {
                int sec = Integer.parseInt(timeoutEdit.getText().toString().trim());
                repo.setTimeoutSec(sec);
            } catch (NumberFormatException e) {
                repo.setTimeoutSec(Policy.DEFAULT_TIMEOUT_SEC);
            }
        }
        Toast.makeText(this, R.string.policy_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
