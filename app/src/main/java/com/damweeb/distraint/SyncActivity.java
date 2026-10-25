package com.damweeb.distraint;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.InputFilter;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

/**
 * Sync screen, opened from MainActivity's "+" menu. Pair once with a code from the PC (PC: Settings > Sync > Pair a device),
 * after that study time is exchanged automatically (when the app is open, and every minute while the blocker service runs).
 * The UI is built in code, like GamePickerActivity.
 *
 * DEBUGGING TIPS
 *   - "Couldn't find the PC": type its address (shown in the PC's pairing window) in the address box.
 *   - Targeting Android 17+ (API 37): the ACCESS_LOCAL_NETWORK permission is requested here before pairing.
 */
public class SyncActivity extends AppCompatActivity {
    private static final String LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";   // spelled out so it compiles on any SDK
    private StudyState state;
    private TextView status, result;
    private EditText hostEdit, codeEdit;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        state = new StudyState(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("Sync with PC");
        title.setTextSize(24);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("On the PC open Distraint > Settings > Sync, turn sync on and tap \"Pair a device\". "
                + "Type the 6-digit code here. Both devices must be on the same Wi-Fi.");
        hint.setPadding(0, 16, 0, 24);
        root.addView(hint);

        status = new TextView(this);
        status.setPadding(0, 0, 0, 24);
        root.addView(status);

        hostEdit = new EditText(this);
        hostEdit.setHint("PC address (optional, e.g. 192.168.1.20)");
        hostEdit.setSingleLine(true);
        hostEdit.setText(state.syncHost());
        root.addView(hostEdit);

        codeEdit = new EditText(this);
        codeEdit.setHint("6-digit code from the PC");
        codeEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        root.addView(codeEdit);

        MaterialButton pair = button("Pair");
        pair.setOnClickListener(v -> onPair());
        root.addView(pair);

        MaterialButton sync = button("Sync now");
        sync.setOnClickListener(v -> {
            result.setText("Syncing...");
            SyncClient.syncAsync(this, (ok, msg) -> { result.setText(msg); refreshStatus(); });
        });
        root.addView(sync);

        MaterialButton forget = button("Forget the PC");
        forget.setOnClickListener(v -> {
            state.forgetSync();
            result.setText("Unpaired.");
            hostEdit.setText("");
            refreshStatus();
        });
        root.addView(forget);

        result = new TextView(this);
        result.setPadding(0, 24, 0, 0);
        root.addView(result);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
        refreshStatus();
    }

    private MaterialButton button(String text) {
        MaterialButton btn = new MaterialButton(this);
        btn.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 24;
        btn.setLayoutParams(lp);
        return btn;
    }

    private void refreshStatus() {
        status.setText(state.syncSecret() == null ? "Not paired." : state.peersSummary());
    }

    private boolean localNetworkAllowed() {
        // Apps targeting below Android 17 get this automatically; newer targets must ask.
        return Build.VERSION.SDK_INT < 37 || checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) result.setText("Allowed. Tap Pair again.");
        else result.setText("Distraint needs local network access to find the PC. Allow it in the app's permissions.");
    }

    private void onPair() {
        String code = codeEdit.getText().toString().trim();
        if (code.length() != 6) { Toast.makeText(this, "Enter the 6-digit code from the PC", Toast.LENGTH_SHORT).show(); return; }
        if (!localNetworkAllowed()) { requestPermissions(new String[]{LOCAL_NETWORK}, 1); return; }

        String typed = hostEdit.getText().toString().trim();
        if (typed.isEmpty()) {
            result.setText("Looking for the PC...");
            SyncClient.discoverAsync(this, 4000, (host, port, name) -> {
                if (host == null) {
                    result.setText("Couldn't find the PC automatically. Type its address above (shown in the PC's pairing window).");
                    return;
                }
                hostEdit.setText(host);
                doPair(host, port, code);
            });
        } else {
            int port = SyncClient.PORT;
            String host = typed;
            int colon = typed.lastIndexOf(':');
            if (colon > 0) {                                  // "192.168.1.20:47653"
                try { port = Integer.parseInt(typed.substring(colon + 1)); host = typed.substring(0, colon); }
                catch (NumberFormatException ignored) { }
            }
            doPair(host, port, code);
        }
    }

    private void doPair(String host, int port, String code) {
        result.setText("Pairing...");
        SyncClient.pairAsync(this, host, port, code, (ok, msg) -> {
            result.setText(msg);
            if (ok) {
                codeEdit.setText("");
                SyncClient.syncAsync(this, (ok2, msg2) -> { result.setText(msg + ". " + msg2); refreshStatus(); });
            }
            refreshStatus();
        });
    }
}
