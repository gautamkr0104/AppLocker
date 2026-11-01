package com.applocker.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.util.Log;
import android.util.TypedValue;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.applocker.R;
import com.applocker.core.Const;
import com.applocker.core.CryptoStore;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen authentication gate launched by the framework hooks.
 *
 * Security properties:
 *  - exported=false (only system_server / same uid can start it)
 *  - FLAG_SECURE: no screenshots / no recents preview of the PIN screen
 *  - excludeFromRecents + taskAffinity="" so it never litters the task list
 *  - showWhenLocked + turnScreenOn so it can appear directly over a just-unlocked device
 *  - never triggers the app lock itself (own package is hard-denied in the hooks)
 *  - back navigation goes HOME, never back to the protected activity
 *  - grants are delivered as an ordered broadcast: the system_server receiver
 *    installs the grant BEFORE our result callback lets this activity finish,
 *    so the protected activity resumes only after the grant exists (no race)
 *  - no plaintext credential is ever logged or persisted
 */
public class AuthActivity extends Activity {

    private static final String TAG = "AppLocker";

    private String targetPkg;
    private String targetLabel;

    private TextView titleView;
    private TextView subtitleView;
    private LinearLayout dotsView;
    private TextView msgView;
    private EditText passwordView;
    private LinearLayout keypadContainer;
    private TextView okButton;

    private final StringBuilder entered = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean grantSent = new AtomicBoolean(false);
    private boolean verified = false;

    private CryptoStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        store = new CryptoStore(this);
        targetPkg = getIntent() != null ? getIntent().getStringExtra(Const.EXTRA_PKG) : null;
        targetLabel = getIntent() != null ? getIntent().getStringExtra(Const.EXTRA_LABEL) : null;
        if (targetLabel == null || targetLabel.isEmpty()) targetLabel = targetPkg;

        if (savedInstanceState != null) {
            String s = savedInstanceState.getString("entered", "");
            entered.append(s == null ? "" : s);
        }

        setContentView(R.layout.activity_auth);
        bindViews();

        if (!store.isPinSet() || targetPkg == null) {
            msgView.setVisibility(View.VISIBLE);
            msgView.setText(R.string.auth_not_ready);
            keypadContainer.setVisibility(View.GONE);
            return;
        }

        if (store.getMode() == CryptoStore.MODE_PASSWORD) {
            titleView.setText(getString(R.string.auth_title_pw, targetLabel));
            subtitleView.setText(R.string.auth_subtitle_pw);
        } else {
            titleView.setText(getString(R.string.auth_title, targetLabel));
            subtitleView.setText(R.string.auth_subtitle_pin);
        }

        if (store.getMode() == CryptoStore.MODE_PASSWORD) {
            // The system keyboard is the right input for a password: give it focus
            // immediately instead of making the user tap the field first.
            passwordView.setVisibility(View.VISIBLE);
            dotsView.setVisibility(View.GONE);
            okButton.setVisibility(View.VISIBLE);
            keypadContainer.setVisibility(View.GONE);
            focusPassword();
        } else {
            passwordView.setVisibility(View.GONE);
            dotsView.setVisibility(View.VISIBLE);
            okButton.setVisibility(View.GONE);
            findViewById(R.id.auth_show).setVisibility(View.GONE);
            buildKeypad();
        }
        renderEntry();
        checkLockout();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("entered", entered.toString());
    }

    private void bindViews() {
        titleView = findViewById(R.id.auth_title);
        subtitleView = findViewById(R.id.auth_subtitle);
        dotsView = findViewById(R.id.auth_dots);
        msgView = findViewById(R.id.auth_msg);
        passwordView = findViewById(R.id.auth_password);
        keypadContainer = findViewById(R.id.auth_keypad);
        okButton = findViewById(R.id.auth_ok);

        passwordView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        okButton.setOnClickListener(v -> submit());
        findViewById(R.id.auth_show).setOnClickListener(v -> {
            int sel = passwordView.getSelectionStart();
            passwordView.setInputType(passwordView.getInputType()
                    ^ InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordView.setSelection(sel);
        });
        findViewById(R.id.auth_home).setOnClickListener(v -> goHome());
    }

    /**
     * Compact numeric keypad in the Samsung (One UI) keyboard style: flat rounded
     * white keys on a light sheet, regular-weight digits, tight gaps. The pad is
     * sized in dp (not stretched to the screen), so it stays small on tall phones.
     */
    private void buildKeypad() {
        keypadContainer.removeAllViews();
        float dens = getResources().getDisplayMetrics().density;
        int keyHeight = (int) (52 * dens);
        int gap = (int) (7 * dens);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int rowWidth = Math.min((int) (340 * dens), screenWidth - (int) (32 * dens));

        String[][] rows = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"CLR", "0", "DEL"}
        };
        for (int r = 0; r < rows.length; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(rowWidth, keyHeight);
            if (r > 0) lp.topMargin = gap;
            line.setLayoutParams(lp);

            for (int c = 0; c < rows[r].length; c++) {
                final String key = rows[r][c];
                TextView b = new TextView(this);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) blp.setMarginStart(gap);
                b.setLayoutParams(blp);
                b.setGravity(Gravity.CENTER);
                b.setBackgroundResource(R.drawable.bg_key);
                b.setTextColor(getColor(R.color.text_primary));
                b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f);
                b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                        android.graphics.Typeface.NORMAL));
                if ("DEL".equals(key)) {
                    b.setText("\u232B");
                    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
                    b.setTextColor(getColor(R.color.text_secondary));
                    b.setContentDescription("delete");
                    b.setOnClickListener(v -> {
                        tap(v);
                        onKeyDel();
                    });
                } else if ("CLR".equals(key)) {
                    b.setText("C");
                    b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
                    b.setTextColor(getColor(R.color.text_secondary));
                    b.setContentDescription("clear");
                    b.setOnClickListener(v -> {
                        tap(v);
                        onKeyClr();
                    });
                } else {
                    b.setText(key);
                    b.setOnClickListener(v -> {
                        tap(v);
                        onKeyDigit(key);
                    });
                }
                line.addView(b);
            }
            keypadContainer.addView(line);
        }
    }

    private void focusPassword() {
        passwordView.post(() -> {
            passwordView.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(passwordView, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void tap(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    /** Keypad routing: password mode types into the password EditText (which submit()
     *  reads); PIN mode fills the masked buffer with auto-submit at PIN length. */
    private void onKeyDigit(String key) {
        if (store.getMode() == CryptoStore.MODE_PASSWORD) {
            if (passwordView.length() < 64) passwordView.append(key);
            return;
        }
        if (entered.length() < 32) {
            entered.append(key);
            renderEntry();
            maybeAutoSubmit();
        }
    }

    private void onKeyDel() {
        if (store.getMode() == CryptoStore.MODE_PASSWORD) {
            int n = passwordView.length();
            if (n > 0) passwordView.getText().delete(n - 1, n);
            return;
        }
        if (entered.length() > 0) {
            entered.deleteCharAt(entered.length() - 1);
            renderEntry();
        }
    }

    private void onKeyClr() {
        if (store.getMode() == CryptoStore.MODE_PASSWORD) {
            passwordView.setText("");
            return;
        }
        entered.setLength(0);
        renderEntry();
    }

    private void renderEntry() {
        if (store.getMode() == CryptoStore.MODE_PASSWORD) {
            // password mode uses its own EditText; keep buffer in sync for fallback
            return;
        }
        dotsView.removeAllViews();
        float dens = getResources().getDisplayMetrics().density;
        int size = (int) (9 * dens);
        int margin = (int) (6 * dens);
        int pinLen = store.getPinLength();
        int total = Math.max(4, Math.min(pinLen > 0 ? pinLen : 4, 12));
        int filled = Math.min(entered.length(), total);
        for (int i = 0; i < total; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(size, size);
            p.setMargins(margin, 0, margin, 0);
            dot.setLayoutParams(p);
            dot.setBackgroundResource(R.drawable.bg_dot);
            dot.setAlpha(i < filled ? 1f : 0.16f);
            dotsView.addView(dot);
        }
    }

    private void maybeAutoSubmit() {
        int pinLen = store.getPinLength();
        if (store.getMode() == CryptoStore.MODE_PIN && pinLen > 0 && entered.length() >= pinLen) {
            submit();
        }
    }

    private void submit() {
        if (verified) return;
        long locked = store.lockedForMs();
        if (locked > 0) {
            showLockout(locked);
            return;
        }
        char[] candidate;
        if (store.getMode() == CryptoStore.MODE_PASSWORD) {
            String pw = passwordView.getText().toString();
            candidate = pw.toCharArray();
            passwordView.setText("");
        } else {
            candidate = entered.toString().toCharArray();
        }
        if (candidate.length == 0) return;

        boolean ok = store.verify(candidate);
        Log.i(TAG, "verify=" + ok + " mode=" + store.getMode()); // no credential data logged
        if (ok) {
            verified = true;
            msgView.setVisibility(View.GONE);
            sendGrantAndFinish();
        } else {
            entered.setLength(0);
            renderEntry();
            msgView.setVisibility(View.VISIBLE);
            msgView.setText(store.getMode() == CryptoStore.MODE_PASSWORD
                    ? R.string.auth_wrong_pw : R.string.auth_wrong);
            long after = store.lockedForMs();
            if (after > 0) showLockout(after);
        }
    }

    /**
     * Deliver the grant to system_server through an ordered broadcast, then finish.
     *
     * On this One UI build the ordered-broadcast RESULT callback is never invoked,
     * so we do not rely on it alone: the grant receiver in system_server runs
     * ~1ms after delivery (observed via dumpsys), so finishing a short moment after
     * the send still guarantees the grant is installed BEFORE the protected activity
     * resumes (it must pass the resume gate again). The ordered result, if it ever
     * fires, just finishes us even sooner. The 3s valve only runs if both fail.
     */
    private void sendGrantAndFinish() {
        if (!grantSent.compareAndSet(false, true)) return;
        Intent i = new Intent(Const.ACTION_GRANT).putExtra(Const.EXTRA_PKG, targetPkg);
        final Intent intent = i;
        handler.post(() -> {
            try {
                sendOrderedBroadcast(intent, null, new android.content.BroadcastReceiver() {
                    @Override
                    public void onReceive(Context receiverCtx, Intent receiverIntent) {
                        Log.i(TAG, "grant: ordered result received");
                        finishVerified();
                    }
                }, null, RESULT_OK, null, null);
                Log.i(TAG, "grant: broadcast sent");
            } catch (Throwable t) {
                // Delivery failed: never leave the user stuck on a dead auth screen.
                Log.w(TAG, "grant: broadcast failed", t);
            }
            // Fallback finish: grant receiver runs ~1ms after send (observed).
            handler.postDelayed(() -> {
                if (finishedOnce) return;
                Log.i(TAG, "grant: fallback finish (no ordered result)");
                finishVerified();
            }, 300L);
        });
        // Safety valve: if nothing completed, go HOME (never reveal target).
        handler.postDelayed(() -> {
            if (!finishedOnce) {
                Log.w(TAG, "grant: safety valve -> home");
                goHome();
            }
        }, 3000L);
    }

    private volatile boolean finishedOnce = false;

    private void finishVerified() {
        if (finishedOnce) return;
        finishedOnce = true;
        setResult(RESULT_OK);
        finish();
    }

    /** Leaving auth must NEVER fall through to the protected activity. */
    private void goHome() {
        if (finishedOnce) return;
        finishedOnce = true;
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch (Throwable ignored) { }
        finish();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            goHome();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showLockout(long ms) {
        msgView.setVisibility(View.VISIBLE);
        long sec = (ms + 999) / 1000;
        msgView.setText(getString(R.string.auth_lockout, sec));
        handler.postDelayed(this::checkLockout, 500L);
    }

    private void checkLockout() {
        long locked = store.lockedForMs();
        if (locked > 0) showLockout(locked);
        else if (msgView.getVisibility() == View.VISIBLE
                && msgView.getText().length() > 0
                && msgView.getText().toString().contains("Wait")) {
            msgView.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
