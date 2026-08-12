package com.instrumental.attachment;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class Ge100RemoteActivity extends Activity
        implements Ge100ProController.Listener {
    private static final int REQUEST_BLUETOOTH = 7201;
    private static final int INK = Color.rgb(19, 48, 68);
    private static final int MUTED = Color.rgb(71, 103, 124);
    private static final int BLUE = Color.rgb(33, 126, 196);
    private static final int CYAN = Color.rgb(33, 165, 217);
    private static final int GREEN = Color.rgb(29, 157, 105);
    private static final int BORDER = Color.rgb(153, 205, 232);
    private static final int SURFACE = Color.rgb(246, 252, 255);

    private Ge100ProController controller;
    private TextView connectionDot;
    private TextView connectionText;
    private TextView currentPresetText;
    private TextView presetCountText;
    private LinearLayout presetRows;
    private LinearLayout chainRows;
    private EditText search;
    private SeekBar volume;
    private SeekBar input;
    private SeekBar otg;
    private TextView volumeValue;
    private TextView inputValue;
    private TextView otgValue;
    private List<String> allPresets = Collections.emptyList();
    private int currentPreset;
    private boolean metalOnly;
    private boolean updatingLevels;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        getWindow().getDecorView().post(this::enterFullscreen);
        try {
            controller = new Ge100ProController(this, this);
            controller.start();
        } catch (RuntimeException e) {
            controller = null;
            onStatus("External pedal control could not start", false, "");
        }
    }

    @Override protected void onDestroy() {
        if (controller != null) controller.close();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterFullscreen();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth permission is required for wireless control",
                            Toast.LENGTH_LONG).show();
                    return;
                }
            }
            if (controller != null) controller.scanBluetooth();
        }
    }

    private void buildUi() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable stage = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(33, 165, 217), Color.rgb(33, 126, 196), Color.WHITE});
        stage.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        screen.setBackground(stage);

        screen.addView(buildHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout left = buildPresetBrowser();
        LinearLayout right = buildEditor();
        body.addView(left, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 3.8f));
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 6.2f);
        rightLp.leftMargin = dp(12);
        body.addView(right, rightLp);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(10);
        screen.addView(body, bodyLp);
        setContentView(screen);
    }

    private View buildHeader() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(7), dp(10), dp(7));
        bar.setBackground(panel(Color.argb(246, 247, 253, 255), BORDER, 10));

        Button back = button("‹", BLUE);
        back.setTextSize(28);
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("External Pedal", 20, INK, true);
        titles.addView(title);
        titles.addView(text("External pedal presets and effect chain · BETA", 11, MUTED, false));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(10);
        bar.addView(titles, titleLp);

        connectionDot = text("●", 13, Color.rgb(191, 83, 84), true);
        bar.addView(connectionDot);
        connectionText = text("Waiting for pedal", 12, INK, true);
        connectionText.setSingleLine(true);
        connectionText.setMaxWidth(dp(260));
        connectionText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusLp.leftMargin = dp(5);
        statusLp.rightMargin = dp(10);
        bar.addView(connectionText, statusLp);

        Button otgButton = button("OTG", CYAN);
        otgButton.setOnClickListener(v -> {
            if (controller != null) controller.connectUsb();
        });
        bar.addView(otgButton, new LinearLayout.LayoutParams(dp(72), dp(40)));
        Button bleButton = button("Bluetooth", BLUE);
        bleButton.setOnClickListener(v -> requestBluetoothThenScan());
        LinearLayout.LayoutParams bleLp = new LinearLayout.LayoutParams(dp(104), dp(40));
        bleLp.leftMargin = dp(7);
        bar.addView(bleButton, bleLp);
        Button refresh = button("↻", GREEN);
        refresh.setTextSize(22);
        refresh.setContentDescription("Refresh pedal");
        refresh.setOnClickListener(v -> {
            if (controller != null) controller.refresh();
        });
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(dp(44), dp(40));
        refreshLp.leftMargin = dp(7);
        bar.addView(refresh, refreshLp);
        return bar;
    }

    private LinearLayout buildPresetBrowser() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(panel(SURFACE, BORDER, 10));

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("DEVICE PRESETS", 12, BLUE, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        presetCountText = text("0 / 150", 11, MUTED, true);
        heading.addView(presetCountText);
        panel.addView(heading);

        currentPresetText = text("No preset loaded", 17, INK, true);
        panel.addView(currentPresetText, top(dp(7)));

        search = new EditText(this);
        search.setHint("Search installed presets");
        search.setHintTextColor(MUTED);
        search.setTextColor(INK);
        search.setSingleLine(true);
        search.setTextSize(13);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.setBackground(panel(Color.WHITE, BORDER, 8));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderPresets();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        panel.addView(search, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        Button metal = button("Metal finder", Color.rgb(71, 91, 115));
        metal.setOnClickListener(v -> {
            metalOnly = !metalOnly;
            metal.setText(metalOnly ? "✓ Metal finder" : "Metal finder");
            metal.setBackground(buttonBackground(metalOnly ? GREEN : Color.rgb(71, 91, 115)));
            renderPresets();
        });
        panel.addView(metal, top(dp(7)));

        presetRows = new LinearLayout(this);
        presetRows.setOrientation(LinearLayout.VERTICAL);
        TextView waiting = text("Connect the pedal to read its installed presets.",
                13, MUTED, false);
        waiting.setPadding(dp(8), dp(12), dp(8), dp(12));
        presetRows.addView(waiting);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(presetRows);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.topMargin = dp(7);
        panel.addView(scroll, scrollLp);
        return panel;
    }

    private LinearLayout buildEditor() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(panel(Color.argb(246, 247, 253, 255), BORDER, 10));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout levels = new LinearLayout(this);
        levels.setOrientation(LinearLayout.VERTICAL);
        levels.addView(text("GLOBAL LEVELS", 12, BLUE, true));
        levels.addView(levelRow("Output", 0));
        levels.addView(levelRow("Input", 1));
        levels.addView(levelRow("OTG", 2));
        top.addView(levels, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout library = new LinearLayout(this);
        library.setOrientation(LinearLayout.VERTICAL);
        library.setPadding(dp(12), dp(8), dp(12), dp(8));
        library.setBackground(panel(Color.rgb(226, 245, 253), BORDER, 8));
        library.addView(text("EXTERNAL CAPTURES", 11, BLUE, true));
        library.addView(text("NAM / MNRS / IR stay outside the APK. Import them with "
                + "MOOER Studio, then select the resulting preset here.", 11, INK, false));
        Button browse = button("Browse licensed NAM", BLUE);
        browse.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.tone3000.com/search?tags=metal&platform=nam"))));
        library.addView(browse, top(dp(6)));
        LinearLayout.LayoutParams libraryLp = new LinearLayout.LayoutParams(dp(238),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        libraryLp.leftMargin = dp(12);
        top.addView(library, libraryLp);
        panel.addView(top);

        TextView label = text("LIVE SIGNAL CHAIN", 12, BLUE, true);
        LinearLayout.LayoutParams labelLp = top(dp(8));
        panel.addView(label, labelLp);

        chainRows = new LinearLayout(this);
        chainRows.setOrientation(LinearLayout.HORIZONTAL);
        chainRows.setGravity(Gravity.CENTER_VERTICAL);
        chainRows.addView(text("Connect the pedal to read the active chain.", 13, MUTED, false));
        HorizontalScrollView chainScroll = new HorizontalScrollView(this);
        chainScroll.setFillViewport(true);
        chainScroll.setHorizontalScrollBarEnabled(false);
        chainScroll.addView(chainRows);
        LinearLayout.LayoutParams chainLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        chainLp.topMargin = dp(6);
        panel.addView(chainScroll, chainLp);

        TextView foot = text("Preset changes are immediate on the pedal. Capture upload, "
                + "firmware, delete and factory reset are intentionally blocked.", 10, MUTED, false);
        panel.addView(foot, top(dp(5)));
        return panel;
    }

    private View levelRow(String name, int field) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = text(name, 11, INK, true);
        row.addView(label, new LinearLayout.LayoutParams(dp(52), dp(30)));
        SeekBar slider = new SeekBar(this);
        slider.setMax(field == 0 ? 100 : 21);
        slider.setProgress(0);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        row.addView(slider, sliderLp);
        TextView value = text("--", 11, INK, true);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(value, new LinearLayout.LayoutParams(dp(42), dp(30)));
        if (field == 0) { volume = slider; volumeValue = value; }
        else if (field == 1) { input = slider; inputValue = value; }
        else { otg = slider; otgValue = value; }
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(field == 0 ? progress + "%" : progress + "/21");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                if (!updatingLevels && controller != null) {
                    controller.setGlobalLevel(field, seekBar.getProgress());
                }
            }
        });
        return row;
    }

    private void renderPresets() {
        if (presetRows == null) return;
        presetRows.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim()
                .toLowerCase(Locale.US);
        int shown = 0;
        for (int i = 0; i < allPresets.size(); i++) {
            String name = allPresets.get(i);
            if (!query.isEmpty() && !name.toLowerCase(Locale.US).contains(query)) continue;
            if (metalOnly && !isMetalPreset(name)) continue;
            final int presetIndex = i + 1;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(7), dp(8), dp(7));
            boolean selected = presetIndex == currentPreset;
            row.setBackground(panel(selected ? Color.rgb(198, 239, 249) : Color.WHITE,
                    selected ? GREEN : BORDER, 7));
            TextView number = text(String.format(Locale.US, "%03d", presetIndex),
                    10, selected ? GREEN : MUTED, true);
            row.addView(number, new LinearLayout.LayoutParams(dp(38),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            TextView title = text(name, 13, INK, selected);
            title.setSingleLine(true);
            row.addView(title, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            if (isMetalPreset(name)) {
                TextView tag = text("METAL", 8, Color.WHITE, true);
                tag.setGravity(Gravity.CENTER);
                tag.setBackground(panel(Color.rgb(68, 87, 109), Color.rgb(68, 87, 109), 6));
                row.addView(tag, new LinearLayout.LayoutParams(dp(43), dp(22)));
            }
            row.setOnClickListener(v -> {
                if (controller != null) controller.selectPreset(presetIndex);
            });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
            rowLp.bottomMargin = dp(5);
            presetRows.addView(row, rowLp);
            shown++;
        }
        presetCountText.setText(shown + " / " + allPresets.size());
        if (shown == 0) {
            TextView empty = text(allPresets.isEmpty() ? "Waiting for preset list..."
                    : "No matching presets", 13, MUTED, false);
            empty.setPadding(dp(8), dp(14), dp(8), dp(14));
            presetRows.addView(empty);
        }
    }

    private static boolean isMetalPreset(String name) {
        String n = name.toLowerCase(Locale.US);
        String[] keys = {"metal", "djent", "chug", "5150", "5153", "rect", "mesa",
                "boogie", "mark 3", "mark 5", "brit j800", "fire", "high gain",
                "higain", "modern lead", "modern choke", "modern edge", "heavy",
                "thrash", "doom", "soldano", "engl", "bogner", "diezel", "jp lead",
                "lead burner", "tight kill", "hell song", "break you"};
        for (String key : keys) if (n.contains(key)) return true;
        return false;
    }

    private void requestBluetoothThenScan() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH);
                return;
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_BLUETOOTH);
            return;
        }
        if (controller != null) controller.scanBluetooth();
    }

    @Override public void onStatus(String message, boolean connected, String transport) {
        connectionDot.setTextColor(connected ? GREEN : Color.rgb(191, 83, 84));
        connectionText.setText((transport == null || transport.isEmpty() ? "" : transport + " · ")
                + message);
    }

    @Override public void onPresetNames(List<String> names) {
        allPresets = new ArrayList<>(names);
        renderPresets();
    }

    @Override public void onCurrentPreset(int index, String name) {
        currentPreset = index;
        currentPresetText.setText(String.format(Locale.US, "%03d · %s", index, name));
        renderPresets();
    }

    @Override public void onGlobalParameters(Ge100ProController.GlobalParameters parameters) {
        updatingLevels = true;
        volume.setProgress(Math.min(100, parameters.volume));
        input.setProgress(Math.min(100, parameters.inputLevel));
        otg.setProgress(Math.min(100, parameters.otgLevel));
        volumeValue.setText(parameters.volume + "%");
        inputValue.setText(parameters.inputLevel + "/21");
        otgValue.setText(parameters.otgLevel + "/21");
        updatingLevels = false;
    }

    @Override public void onEffectChain(List<Ge100ProController.EffectModule> modules) {
        chainRows.removeAllViews();
        for (int i = 0; i < modules.size(); i++) {
            Ge100ProController.EffectModule module = modules.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(8), dp(8), dp(8), dp(8));
            boolean active = module.valid != 0 && module.enabled != 0;
            card.setBackground(panel(active ? Color.rgb(205, 245, 248) : Color.WHITE,
                    active ? GREEN : BORDER, 9));
            TextView position = text(String.format(Locale.US, "%02d", i + 1),
                    9, MUTED, true);
            card.addView(position);
            card.addView(text(module.typeName(), 13, INK, true), top(dp(3)));
            String model = module.memoryName.isEmpty()
                    || "MEMORY".equalsIgnoreCase(module.memoryName)
                    ? "Model #" + module.serial : module.memoryName;
            TextView modelText = text(model, 9, MUTED, false);
            modelText.setGravity(Gravity.CENTER);
            modelText.setMaxLines(2);
            card.addView(modelText, top(dp(2)));
            Button toggle = button(active ? "ON" : "OFF", active ? GREEN : Color.rgb(99, 122, 139));
            toggle.setEnabled(module.valid != 0);
            toggle.setOnClickListener(v -> {
                if (controller != null) {
                    controller.setModuleEnabled(module.chainIndex, !active);
                }
            });
            card.addView(toggle, new LinearLayout.LayoutParams(dp(72), dp(32)));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(102),
                    LinearLayout.LayoutParams.MATCH_PARENT);
            if (i > 0) cardLp.leftMargin = dp(7);
            chainRows.addView(card, cardLp);
        }
    }

    @SuppressWarnings("deprecation")
    private void enterFullscreen() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController insets = getWindow().getDecorView()
                    .getWindowInsetsController();
            if (insets != null) {
                insets.hide(WindowInsets.Type.statusBars()
                        | WindowInsets.Type.navigationBars());
                insets.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }

    private Button button(String value, int color) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        button.setPadding(dp(6), 0, dp(6), 0);
        button.setBackground(buttonBackground(color));
        return button;
    }

    private GradientDrawable buttonBackground(int color) {
        return panel(color, lighten(color), 9);
    }

    private GradientDrawable panel(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private static int lighten(int color) {
        return Color.rgb(Math.min(255, Color.red(color) + 38),
                Math.min(255, Color.green(color) + 38),
                Math.min(255, Color.blue(color) + 38));
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
