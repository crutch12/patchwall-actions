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
import android.view.KeyEvent;
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
    private View firstRow;
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
        list.addView(text("Пункт управления на кнопке Xiaomi. Двойное нажатие открывает меню", 14, GRAY_TEXT));

        buildAccess();
        buildSinglePress();
        buildHold();
        buildBacklightPreset();
        buildTiles();

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        scroll.setBackgroundColor(BG);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.addView(scroll, new FrameLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));
        setContentView(root);
        // иначе фокус встаёт на сам список и первое нажатие стрелки теряется
        scroll.setFocusable(false);
        firstRow.post(firstRow::requestFocus);
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
        list.addView(text("Двойное нажатие всегда открывает меню, повторное нажатие закрывает его", 13, GRAY_TEXT));
    }

    // --- удержание ---

    private void buildHold() {
        list.addView(section("Удержание кнопки"));
        for (int i = 0; i < Actions.HOLD_VALUES.length; i++) {
            String value = Actions.HOLD_VALUES[i];
            Row row = row(Actions.HOLD_LABELS[i]);
            row.view.setOnClickListener(v -> {
                Actions.prefs(this).edit().putString("hold", value).apply();
                refreshAll();
            });
            refreshers.add(() -> {
                boolean chosen = value.equals(Actions.hold(this));
                row.status(chosen ? "●" : "○", chosen ? Color.WHITE : GRAY_TEXT);
            });
        }
    }

    // --- уровни подсветки для кнопки ---

    private void buildBacklightPreset() {
        list.addView(section("Подсветка по кнопке"));
        Row low = row("Нижний уровень");
        Row high = row("Верхний уровень");
        // ОК листает по кругу, стрелки влево/вправо двигают на шаг
        presetRow(low, true);
        presetRow(high, false);
        refreshers.add(() -> {
            int l = Backlight.low(this);
            int h = Backlight.high(this);
            low.status(l + "%", Color.WHITE);
            high.status(h == Backlight.LAST ? "Последний" : h + "%", Color.WHITE);
        });
        list.addView(text("Кнопка переключает между этими уровнями. Последний это уровень, который был до переключения вниз", 13, GRAY_TEXT));
    }

    private void presetRow(Row row, boolean isLow) {
        row.view.setOnClickListener(v -> stepPreset(isLow, 1, true));
        row.view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                stepPreset(isLow, 1, false);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                stepPreset(isLow, -1, false);
                return true;
            }
            return false;
        });
    }

    // нижний: 0..100, верхний: последний, 0..100. Шаг 5
    private void stepPreset(boolean isLow, int dir, boolean wrap) {
        int low = Backlight.low(this);
        int high = Backlight.high(this);
        int value = isLow ? low : high;
        int next;
        if (value == Backlight.LAST) {
            next = dir > 0 ? 0 : (wrap ? 100 : Backlight.LAST);
        } else {
            next = value + dir * 5;
        }
        int min = isLow ? 0 : Backlight.LAST;
        if (next > 100) {
            next = wrap ? min : 100;
        } else if (next < 0) {
            next = isLow ? (wrap ? 100 : 0) : Backlight.LAST;
        }
        if (isLow) {
            Backlight.setPreset(this, next, high);
        } else {
            Backlight.setPreset(this, low, next);
        }
        refreshAll();
    }

    // --- доступы ---

    private void buildAccess() {
        list.addView(section("Доступ"));
        list.addView(text("Всё настраивается через отладку по сети самого TV. При первой настройке TV спросит "
                + "Разрешить отладку: отметьте Всегда и нажмите Разрешить", 13, GRAY_TEXT));

        Row debug = row("Отладка по сети");
        debug.view.setOnClickListener(v -> {
            toast("Включите Отладку по USB и Отладку по сети, затем вернитесь сюда");
            Actions.open(this, Actions.action(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    Actions.action(Settings.ACTION_DEVICE_INFO_SETTINGS));
        });
        refreshers.add(() -> {
            debug.status("...", GRAY_TEXT);
            new Thread(() -> {
                boolean on = LocalAdb.reachable();
                runOnUiThread(() -> debug.status(on ? "Вкл" : "Включить", on ? OK : BAD));
            }).start();
        });

        Row setup = row("Настроить всё автоматически");
        setup.view.setOnClickListener(v -> {
            setup.status("Ждём TV...", GRAY_TEXT);
            Setup.grantAll(this, error -> {
                toast(error == null ? "Готово" : error);
                refreshAll();
            });
        });
        refreshers.add(() -> {
            boolean ready = serviceEnabled() && ignoringBattery() && granted(Manifest.permission.WRITE_SECURE_SETTINGS)
                    && granted(Manifest.permission.READ_LOGS);
            setup.status(ready ? "Готово" : "Запустить", ready ? OK : BAD);
        });

        Row service = row("Служба специальных возможностей");
        service.view.setOnClickListener(v -> Actions.open(this, Actions.action(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        refreshers.add(() -> service.status(serviceEnabled() ? "Вкл" : "Выкл", serviceEnabled() ? OK : BAD));

        Row battery = row("Не выгружать из памяти");
        battery.view.setOnClickListener(v -> Actions.open(this,
                new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())),
                Actions.action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        refreshers.add(() -> battery.status(ignoringBattery() ? "Да" : "Нет", ignoringBattery() ? OK : BAD));

        Row secure = row("Менять настройки TV");
        refreshers.add(() -> grantedStatus(secure, Manifest.permission.WRITE_SECURE_SETTINGS));

        Row logs = row("Видеть нажатия кнопки");
        refreshers.add(() -> grantedStatus(logs, Manifest.permission.READ_LOGS));

        Row location = row("Имя сети Wi-Fi");
        location.view.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1));
        refreshers.add(() -> grantedStatus(location, Manifest.permission.ACCESS_FINE_LOCATION));

        Row patchWall = row("PatchWall");
        patchWall.view.setOnClickListener(v -> {
            boolean installed = MiKeyService.patchWallInstalled(this);
            patchWall.status("Ждём TV...", GRAY_TEXT);
            Setup.setPatchWall(this, !installed, error -> {
                toast(error == null ? (installed ? "PatchWall удалён, кнопка больше не мелькает" : "PatchWall возвращён") : error);
                refreshAll();
            });
        });
        refreshers.add(() -> {
            boolean installed = MiKeyService.patchWallInstalled(this);
            patchWall.status(installed ? "Мелькает, удалить" : "Удалён, вернуть", installed ? BAD : OK);
        });
    }

    private void refreshAll() {
        for (Runnable r : refreshers) {
            r.run();
        }
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void grantedStatus(Row row, String permission) {
        boolean ok = granted(permission);
        row.status(ok ? "Есть" : "Нет", ok ? OK : BAD);
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
        if (firstRow == null) {
            firstRow = row.view;
        }
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
