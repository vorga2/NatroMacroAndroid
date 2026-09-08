package dev.vorga.natromobile;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Spinner pattern;
    private Spinner hiveSlot;
    private EditText speed;
    private EditText size;
    private EditText reps;
    private EditText seconds;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private View buildUi() {
        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 48, 36, 48);
        scroll.addView(root);

        root.addView(text("Natro Mobile — Pine Tree", 28, true));
        root.addView(text("Natro-style route: inspect/align at hive → ramp → red cannon → glider → Pine Tree → gather pattern.", 15, false));

        status = text("", 15, false);
        status.setPadding(0, 24, 0, 24);
        root.addView(status);

        root.addView(button("1. Enable Accessibility service", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("2. Allow display over other apps", v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));
        root.addView(button("3. Open Roblox", v -> openRoblox()));
        root.addView(button("4. Calibrate controls (joystick + jump)", v -> calibrate()));

        root.addView(label("Hive slot"));
        hiveSlot = new Spinner(this);
        String[] slots = {"1", "2", "3", "4", "5", "6"};
        hiveSlot.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, slots));
        hiveSlot.setSelection(Math.max(0, Math.min(5, p.getInt("hive_slot", 1) - 1)));
        root.addView(hiveSlot);

        root.addView(label("Pine Tree gather pattern"));
        pattern = new Spinner(this);
        String[] items = {"Snake", "Lines", "Squares", "Stationary"};
        pattern.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        String savedPattern = p.getString("pattern", "Snake");
        for (int i = 0; i < items.length; i++) if (items[i].equals(savedPattern)) pattern.setSelection(i);
        root.addView(pattern);

        speed = numberField(String.valueOf(p.getFloat("movespeed", 28f)));
        size = numberField(String.valueOf(p.getFloat("pattern_size", 1f)));
        reps = numberField(String.valueOf(p.getInt("pattern_reps", 3)));
        seconds = numberField(String.valueOf(p.getInt("run_seconds", 900)));
        root.addView(label("Base move speed")); root.addView(speed);
        root.addView(label("Pattern size")); root.addView(size);
        root.addView(label("Pattern repetitions")); root.addView(reps);
        root.addView(label("Gather duration (seconds)")); root.addView(seconds);

        Button start = button("START NATRO → PINE TREE", v -> startMacro());
        start.setTextSize(18);
        root.addView(start);

        root.addView(text("Important: select the same hive slot you actually claimed. Calibration is three taps while Roblox is visible: joystick center, joystick right edge, then jump button.", 14, false));
        return scroll;
    }

    private void startMacro() {
        MacroAccessibilityService svc = MacroAccessibilityService.get();
        if (svc == null) {
            toast("Enable Natro Mobile in Accessibility first.");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow display over other apps first.");
            return;
        }

        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        if (p.getInt("joy_x", -1) < 0 || p.getInt("jump_x", -1) < 0 || p.getInt("joy_radius", -1) < 0) {
            toast("Run the new 3-step controls calibration first.");
            return;
        }

        try {
            p.edit()
                    .putInt("hive_slot", Integer.parseInt(hiveSlot.getSelectedItem().toString()))
                    .putString("field", "Pine Tree")
                    .putString("pattern", pattern.getSelectedItem().toString())
                    .putFloat("movespeed", Float.parseFloat(speed.getText().toString()))
                    .putFloat("pattern_size", Float.parseFloat(size.getText().toString()))
                    .putInt("pattern_reps", Integer.parseInt(reps.getText().toString()))
                    .putInt("run_seconds", Integer.parseInt(seconds.getText().toString()))
                    .apply();
        } catch (NumberFormatException e) {
            toast("Check numeric settings.");
            return;
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        Intent overlay = new Intent(this, ControlOverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(overlay); else startService(overlay);
        svc.startMacroAfterDelay(2000);
        openRoblox();
        toast("Natro armed. It will inspect the hive and route to Pine Tree.");
    }

    private void calibrate() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow overlay permission first.");
            return;
        }
        startService(new Intent(this, CalibrationOverlayService.class));
        openRoblox();
    }

    private void openRoblox() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.roblox.client");
        if (launch == null) {
            toast("Roblox app was not found.");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void updateStatus() {
        SharedPreferences p = getSharedPreferences("config", MODE_PRIVATE);
        boolean calibrated = p.getInt("joy_x", -1) >= 0
                && p.getInt("joy_radius", -1) >= 0
                && p.getInt("jump_x", -1) >= 0;
        status.setText("Accessibility: " + (MacroAccessibilityService.get() != null ? "ON" : "OFF")
                + "\nOverlay: " + (Settings.canDrawOverlays(this) ? "ON" : "OFF")
                + "\nControls calibrated: " + (calibrated ? "YES" : "NO"));
    }

    private TextView label(String s) {
        TextView t = text(s, 14, true);
        t.setPadding(0, 22, 0, 6);
        return t;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(24, 24, 28));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return t;
    }

    private EditText numberField(String value) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return e;
    }

    private Button button(String s, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 8);
        b.setLayoutParams(lp);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
