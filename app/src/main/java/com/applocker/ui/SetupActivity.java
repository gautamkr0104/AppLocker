package com.applocker.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.applocker.R;
import com.applocker.core.Const;
import com.applocker.core.CryptoStore;
import com.applocker.core.Repo;

/**
 * First-run credential creation and change-PIN/password flow.
 *
 * Steps:
 *   change mode: 0 = verify current credential, then steps 1-2 like setup
 *   setup mode:  1 = enter new credential, 2 = confirm it
 *
 * Only a PBKDF2 hash (Keystore-wrapped) is stored; plaintext is held in memory
 * only for the confirmation comparison and then wiped. Nothing is logged.
 */
public class SetupActivity extends Activity {

    private static final int STEP_VERIFY = 0;
    private static final int STEP_ENTER = 1;
    private static final int STEP_CONFIRM = 2;

    private CryptoStore store;
    private Repo repo;

    private int step = STEP_ENTER;
    private boolean changeMode = false;
    private boolean usePassword = false;
    private String pending; // first entry awaiting confirmation (held only in memory)

    private TextView titleView;
    private TextView stepView;
    private EditText inputView;
    private EditText confirmView;
    private CheckBox passwordToggle;
    private TextView errorView;
    private TextView nextButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        store = new CryptoStore(this);
        repo = new Repo(this);
        changeMode = Const.MODE_CHANGE.equals(getIntent() != null ? getIntent().getStringExtra(Const.EXTRA_MODE) : null);

        titleView = findViewById(R.id.setup_title);
        stepView = findViewById(R.id.setup_step);
        inputView = findViewById(R.id.setup_input);
        confirmView = findViewById(R.id.setup_confirm);
        passwordToggle = findViewById(R.id.setup_use_password);
        errorView = findViewById(R.id.setup_error);
        nextButton = findViewById(R.id.setup_next);

        usePassword = store.getMode() == CryptoStore.MODE_PASSWORD && store.isPinSet();
        passwordToggle.setChecked(usePassword);
        passwordToggle.setOnCheckedChangeListener((btn, checked) -> {
            usePassword = checked;
            applyInputMode();
            errorView.setVisibility(View.GONE);
        });

        nextButton.setOnClickListener(v -> onNext());
        findViewById(R.id.setup_cancel).setOnClickListener(v -> finish());

        step = changeMode ? STEP_VERIFY : (store.isPinSet() ? STEP_VERIFY : STEP_ENTER);
        if (!changeMode && !store.isPinSet()) {
            usePassword = false;
            passwordToggle.setChecked(false);
        }
        applyInputMode();
        renderStep();
    }

    private void applyInputMode() {
        if (usePassword) {
            inputView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            confirmView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        } else {
            inputView.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            confirmView.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            inputView.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
            confirmView.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        }
    }

    private void renderStep() {
        errorView.setVisibility(View.GONE);
        switch (step) {
            case STEP_VERIFY:
                titleView.setText(R.string.setup_change_title);
                stepView.setText(R.string.setup_verify_hint);
                inputView.setVisibility(View.VISIBLE);
                confirmView.setVisibility(View.GONE);
                passwordToggle.setVisibility(View.GONE);
                nextButton.setText(R.string.setup_next);
                focusInput(inputView);
                break;
            case STEP_ENTER:
                titleView.setText(changeMode ? R.string.setup_change_title : R.string.setup_title);
                stepView.setText(usePassword ? R.string.setup_enter_pw : R.string.setup_enter_pin);
                inputView.setVisibility(View.VISIBLE);
                confirmView.setVisibility(View.GONE);
                passwordToggle.setVisibility(View.VISIBLE);
                nextButton.setText(R.string.setup_next);
                focusInput(inputView);
                break;
            case STEP_CONFIRM:
                stepView.setText(usePassword ? R.string.setup_confirm_pw : R.string.setup_confirm_pin);
                inputView.setVisibility(View.GONE);
                confirmView.setVisibility(View.VISIBLE);
                passwordToggle.setVisibility(View.GONE);
                nextButton.setText(R.string.setup_save);
                focusInput(confirmView);
                break;
        }
    }

    /**
     * Keep the typing flow continuous: the field for the current step takes focus
     * and raises the keyboard by itself, so the user never has to tap the box
     * again between steps.
     */
    private void focusInput(final EditText target) {
        target.post(() -> {
            if (target.getVisibility() != View.VISIBLE) return;
            target.requestFocus();
            target.setSelection(target.getText().length());
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void onNext() {
        errorView.setVisibility(View.GONE);
        switch (step) {
            case STEP_VERIFY: {
                String cur = inputView.getText().toString();
                if (cur.isEmpty()) { showError(R.string.setup_empty); return; }
                char[] c = cur.toCharArray();
                inputView.setText("");
                if (!store.verify(c)) {
                    showError(R.string.setup_wrong_current);
                    return;
                }
                step = STEP_ENTER;
                usePassword = store.getMode() == CryptoStore.MODE_PASSWORD;
                applyInputMode();
                renderStep();
                break;
            }
            case STEP_ENTER: {
                String v = inputView.getText().toString();
                if (!validate(v)) return;
                pending = v;
                inputView.setText("");
                step = STEP_CONFIRM;
                renderStep();
                break;
            }
            case STEP_CONFIRM: {
                String v = confirmView.getText().toString();
                confirmView.setText("");
                if (pending == null || !pending.equals(v)) {
                    pending = null;
                    step = STEP_ENTER;
                    inputView.setText("");
                    renderStep();
                    showError(R.string.setup_mismatch);
                    return;
                }
                save(pending);
                pending = null;
                break;
            }
        }
    }

    private boolean validate(String v) {
        if (usePassword) {
            if (v.length() < 4 || v.length() > 32 || v.trim().isEmpty()) {
                showError(R.string.setup_pw_invalid);
                return false;
            }
        } else {
            if (v.length() < 4 || v.length() > 8 || !v.matches("\\d+")) {
                showError(R.string.setup_pin_invalid);
                return false;
            }
        }
        return true;
    }

    private void save(String value) {
        int mode = usePassword ? CryptoStore.MODE_PASSWORD : CryptoStore.MODE_PIN;
        char[] cred = value.toCharArray();
        store.setCredential(cred, mode);
        // Enable the locker on first successful setup; keep current state on change.
        if (!changeMode && !repo.isEnabled()) {
            repo.setEnabled(true);
        }
        repo.bumpAuthVersion(); // system side drops all in-memory grants
        Toast.makeText(this, R.string.setup_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private void showError(int res) {
        errorView.setVisibility(View.VISIBLE);
        errorView.setText(res);
    }

    @Override
    protected void onDestroy() {
        pending = null;
        super.onDestroy();
    }
}
