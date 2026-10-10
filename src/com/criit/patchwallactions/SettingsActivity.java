package com.criit.patchwallactions;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки и первичная настройка: что делает одиночное нажатие, статус доступов
 * (часть выдаётся только через adb), какие плитки показывать в меню.
 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xFF1C1C1E;
    private static final int ROW = 0xFF2C2C2E;
    private static final int GRAY_TEXT = 0xFFAEAEB2;
    private static final int OK = 0xFF30D158;
    private static final int BAD = 0xFFFF9F0A;

    private static final String[] TILE_IDS = {"picture", "sound", "timer", "night", "wifi", "vpn", "blue"};
    private static final String[] TILE_LABELS = {"Картинка", "Звук", "Таймер сна", "Ночь", "Wi-Fi", "Amnezia", "Синий свет"};

    private float density;
    private LinearLayout list;
    private final List<Runnable> refreshers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(40), dp(32), dp(40), dp(32));

        TextView title = text("PatchWall Actions", 26, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(title);
        list.addView(text("Пункт управления на кнопке Xiaomi. Двойное нажатие или удержание открывают меню", 14, GRAY_TEXT));

        buildSinglePress();
        buildAccess();
        buildTiles();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        scroll.setBackgroundColor(BG);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(scroll, new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (Runnable r : refreshers) {
            r.run();
        }
    }

    // --- одиночное нажатие ---

    private void buildSinglePress() {
        list.addView(section("Одно нажатие кнопки"));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < Actions.SINGLE_VALUES.length; i++) {
            String value = Actions.SINGLE_VALUES[i];
            Row row = row(Actions.SINGLE_LABELS[i]);
            row.view.setOnClickListener(v -> {
                Actions.prefs(this).edit().putString("single", value).apply();
                for (Runnable r : refreshers) {
                    r.run();
                }
            });
            rows.add(row);
            refreshers.add(() -> {
                boolean chosen = value.equals(Actions.singlePress(this));
                row.status(chosen ? "●" : "○", chosen ? Color.WHITE : GRAY_TEXT);
            });
        }
        list.addView(text("Двойное нажатие и удержание всегда открывают меню, повторное нажатие закрывает его", 13, GRAY_TEXT));
    }

    // --- доступы ---

    private void buildAccess() {
        list.addView(section("Доступ"));

        Row service = row("Служба специальных возможностей");
        service.view.setOnClickListener(v -> Actions.open(this, Actions.action(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        refreshers.add(() -> service.status(serviceEnabled() ? "Вкл" : "Включить", serviceEnabled() ? OK : BAD));

        Row battery = row("Не выгружать из памяти");
        battery.view.setOnClickListener(v -> Actions.open(this,
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())),
                Actions.action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        refreshers.add(() -> battery.status(ignoringBattery() ? "Да" : "Настроить", ignoringBattery() ? OK : BAD));

        Row secure = row("Менять настройки TV");
        secure.view.setOnClickListener(v -> toast("Выдаётся только через adb, команда ниже"));
        refreshers.add(() -> grantedStatus(secure, Manifest.permission.WRITE_SECURE_SETTINGS));

        Row logs = row("Видеть нажатия кнопки");
        logs.view.setOnClickListener(v -> toast("Выдаётся только через adb, команда ниже. Без него двойное нажатие хуже распознаётся"));
        refreshers.add(() -> grantedStatus(logs, Manifest.permission.READ_LOGS));

        Row location = row("Имя сети Wi-Fi");
        location.view.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1));
        refreshers.add(() -> grantedStatus(location, Manifest.permission.ACCESS_FINE_LOCATION));

        Row patchWall = row("PatchWall не мелькает");
        patchWall.view.setOnClickListener(v -> toast("PatchWall удаляется для пользователя через adb, команда ниже. Вернуть: "
                + "adb shell cmd package install-existing " + MiKeyService.PATCHWALL));
        refreshers.add(() -> {
            boolean gone = !MiKeyService.patchWallInstalled(this);
            patchWall.status(gone ? "Да" : "Нет", gone ? OK : BAD);
        });

        String pkg = getPackageName();
        TextView adb = text("Команды для adb с компьютера:\n"
                + "adb shell pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS\n"
                + "adb shell pm grant " + pkg + " android.permission.READ_LOGS\n"
                + "adb shell dumpsys deviceidle whitelist +" + pkg + "\n"
                + "adb shell pm uninstall -k --user 0 " + MiKeyService.PATCHWALL, 13, GRAY_TEXT);
        adb.setTypeface(Typeface.MONOSPACE);
        adb.setPadding(dp(4), dp(12), 0, 0);
        list.addView(adb);
    }

    private void grantedStatus(Row row, String permission) {
        boolean granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        row.status(granted ? "Есть" : "Нет", granted ? OK : BAD);
    }

    private boolean serviceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        String me = new ComponentName(this, MiKeyService.class).flattenToString();
        for (String s : enabled.split(":")) {
            if (me.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
    }

    private boolean ignoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // --- плитки ---

    private void buildTiles() {
        list.addView(section("Плитки в меню"));
        for (int i = 0; i < TILE_IDS.length; i++) {
            String id = TILE_IDS[i];
            Row row = row(TILE_LABELS[i]);
            row.view.setOnClickListener(v -> {
                Actions.setTileVisible(this, id, !Actions.tileVisible(this, id));
                for (Runnable r : refreshers) {
                    r.run();
                }
            });
            refreshers.add(() -> {
                boolean visible = Actions.tileVisible(this, id);
                row.status(visible ? "Показывать" : "Скрыта", visible ? Color.WHITE : GRAY_TEXT);
            });
        }
    }

    // --- вёрстка ---

    private TextView section(String text) {
        TextView tv = text(text, 14, GRAY_TEXT);
        tv.setPadding(dp(4), dp(28), 0, dp(8));
        return tv;
    }

    private TextView text(String value, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setPadding(dp(4), dp(4), 0, 0);
        return tv;
    }

    private Row row(String label) {
        Row row = new Row(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.topMargin = dp(6);
        list.addView(row.view, lp);
        return row;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    // строка списка: подпись слева, статус справа
    private final class Row {
        final LinearLayout view;
        final TextView value;

        Row(String label) {
            view = new LinearLayout(SettingsActivity.this);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(20), 0, dp(20), 0);
            view.setFocusable(true);
            view.setClickable(true);
            view.setBackground(background());

            TextView name = new TextView(SettingsActivity.this);
            name.setText(label);
            name.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{0xFF1C1C1E, Color.WHITE}));
            name.setDuplicateParentStateEnabled(true);
            name.setTextSize(16);
            view.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            value = new TextView(SettingsActivity.this);
            value.setTextSize(15);
            value.setDuplicateParentStateEnabled(true);
            view.addView(value);
        }

        void status(String text, int color) {
            value.setText(text);
            value.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{0xFF48484A, color}));
        }
    }

    private StateListDrawable background() {
        StateListDrawable states = new StateListDrawable();
        // фокус как в Android TV: светлая строка и тёмный текст
        states.addState(new int[]{android.R.attr.state_focused}, shape(0xFFE5E5EA));
        states.addState(new int[]{}, shape(ROW));
        return states;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(14));
        return d;
    }
}
