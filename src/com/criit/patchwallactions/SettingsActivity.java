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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки в духе системных настроек Android TV: разделы слева, содержимое справа.
 * Фокус на разделе сразу показывает его, стрелка вправо переходит к пунктам.
 */
public class SettingsActivity extends Activity {

    private static final int BG = 0xFF101012;
    private static final int GROUP = 0xFF1C1C1E;
    private static final int DIVIDER = 0xFF2C2C2E;
    private static final int FOCUS_BG = 0xFFE5E5EA;
    private static final int FOCUS_TEXT = 0xFF1C1C1E;
    private static final int FOCUS_SUBTEXT = 0xFF48484A;
    private static final int GRAY_TEXT = 0xFFAEAEB2;
    private static final int OK = 0xFF30D158;
    private static final int BAD = 0xFFFF9F0A;

    private static final String[] TILE_IDS = {"media", "recent", "picture", "sound", "timer", "night", "cinema", "game",
            "myscene", "wifi", "vpn", "blue", "closeapp", "memory"};
    private static final String[] TILE_LABELS = {"Сейчас играет", "Недавние приложения", "Картинка", "Звук",
            "Таймер сна", "Ночь", "Кино", "Игра", "Моя сцена", "Wi-Fi", "Amnezia VPN", "Синий свет",
            "Закрыть приложение", "Очистить память"};

    private float density;
    private LinearLayout nav;
    private LinearLayout content;
    private final List<View> sections = new ArrayList<>();
    private final List<View> navItems = new ArrayList<>();
    private final List<View> firstRows = new ArrayList<>();
    private final List<Runnable> refreshers = new ArrayList<>();
    // группа, в которую сейчас добавляются строки
    private LinearLayout group;
    private TextView statusLine;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;

        nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(dp(40), dp(44), dp(20), dp(32));
        TextView title = text("PatchWall Actions", 24, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        nav.addView(title);
        statusLine = text("", 14, GRAY_TEXT);
        statusLine.setPadding(0, dp(6), 0, dp(28));
        nav.addView(statusLine);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(44), dp(56), dp(44));

        buildAccess();
        buildButton();
        buildBacklight();
        buildMenu();

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.addView(content);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        root.addView(nav, new LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);

        showSection(0);
        navItems.get(0).post(navItems.get(0)::requestFocus);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void refreshAll() {
        for (Runnable r : refreshers) {
            r.run();
        }
        boolean ready = setupReady();
        statusLine.setText(ready ? "Всё настроено" : "Нужна настройка");
        statusLine.setTextColor(ready ? OK : BAD);
    }

    // --- разделы ---

    private void buildAccess() {
        section("Настройка");
        hint("При первой настройке TV спросит, разрешить ли отладку. Отметьте Всегда и нажмите Разрешить. "
                + "После настройки приложение перезапустится само");

        startGroup();
        Row setup = row("Настроить всё автоматически");
        setup.chevron();
        setup.view.setOnClickListener(v -> {
            setup.status("Ждём TV...", GRAY_TEXT);
            Setup.grantAll(this, error -> {
                toast(error == null ? "Готово" : error);
                refreshAll();
            });
        });
        refreshers.add(() -> setup.status(setupReady() ? "Готово" : "Запустить", setupReady() ? OK : BAD));

        Row patchWall = row("PatchWall");
        patchWall.chevron();
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

        Row debug = row("Отладка по сети");
        debug.chevron();
        debug.view.setOnClickListener(v -> {
            toast("Включите Отладку по USB и Отладку по сети, затем вернитесь сюда");
            Actions.open(this, Actions.action(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                    Actions.action(Settings.ACTION_DEVICE_INFO_SETTINGS));
        });
        refreshers.add(() -> {
            debug.status("...", GRAY_TEXT);
            new Thread(() -> {
                boolean on = LocalAdb.reachable();
                runOnUiThread(() -> debug.status(on ? "Вкл" : "Выкл", on ? OK : BAD));
            }).start();
        });

        caption("Доступы");
        startGroup();
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
    }

    private void buildButton() {
        section("Кнопка Xiaomi");
        caption("Одно нажатие");
        startGroup();
        for (int i = 0; i < Actions.SINGLE_VALUES.length; i++) {
            choice(Actions.SINGLE_LABELS[i], Actions.SINGLE_VALUES[i], "single", Actions.SINGLE_BACKLIGHT);
        }
        caption("Удержание");
        startGroup();
        for (int i = 0; i < Actions.HOLD_VALUES.length; i++) {
            choice(Actions.HOLD_LABELS[i], Actions.HOLD_VALUES[i], "hold", Actions.HOLD_SOURCE);
        }
        hint("Двойное нажатие всегда открывает меню, повторное нажатие закрывает его");
    }

    private void buildBacklight() {
        section("Подсветка");
        hint("Одно нажатие кнопки переключает между этими уровнями. Ниже нуля подсветка выключена, "
                + "а экран дополнительно затемняется. Стрелки меняют значение");
        startGroup();
        Row low = row("Нижний уровень");
        Row high = row("Верхний уровень");
        low.stepper();
        high.stepper();
        presetRow(low, true);
        presetRow(high, false);
        refreshers.add(() -> {
            int l = Backlight.low(this);
            int h = Backlight.high(this);
            low.status(l + "%", Color.WHITE);
            high.status(h == Backlight.LAST ? "Последний" : h + "%", Color.WHITE);
        });
    }

    private void buildMenu() {
        section("Меню");
        startGroup();
        Row scene = row("Сохранить текущее как мою сцену");
        scene.chevron();
        scene.view.setOnClickListener(v -> {
            Actions.saveMyScene(this);
            toast("Сцена сохранена: картинка, звук, яркость и синий свет");
            refreshAll();
        });
        refreshers.add(() -> scene.status(Actions.mySceneSaved(this) ? "Сохранена" : "", GRAY_TEXT));

        caption("Пункты меню");
        startGroup();
        for (int i = 0; i < TILE_IDS.length; i++) {
            String id = TILE_IDS[i];
            Row row = row(TILE_LABELS[i]);
            row.view.setOnClickListener(v -> {
                Actions.setTileVisible(this, id, !Actions.tileVisible(this, id));
                refreshAll();
            });
            refreshers.add(() -> row.check(Actions.tileVisible(this, id)));
        }
    }

    // --- логика ---

    private void choice(String label, String value, String key, String fallback) {
        Row row = row(label);
        row.view.setOnClickListener(v -> {
            Actions.prefs(this).edit().putString(key, value).apply();
            refreshAll();
        });
        refreshers.add(() -> row.check(value.equals(Actions.prefs(this).getString(key, fallback))));
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

    // нижний: MIN..100, верхний: последний, MIN..100. Шаг 5, отрицательные это затемнение
    private void stepPreset(boolean isLow, int dir, boolean wrap) {
        List<Integer> values = new ArrayList<>();
        if (!isLow) {
            values.add(Backlight.LAST);
        }
        for (int v = Backlight.MIN; v <= 100; v += 5) {
            values.add(v);
        }
        int low = Backlight.low(this);
        int high = Backlight.high(this);
        int index = Math.max(values.indexOf(isLow ? low : high), 0) + dir;
        if (wrap) {
            index = (index + values.size()) % values.size();
        } else {
            index = Math.max(0, Math.min(values.size() - 1, index));
        }
        int next = values.get(index);
        if (isLow) {
            Backlight.setPreset(this, next, high);
        } else {
            Backlight.setPreset(this, low, next);
        }
        refreshAll();
    }

    private boolean setupReady() {
        return serviceEnabled() && ignoringBattery() && granted(Manifest.permission.WRITE_SECURE_SETTINGS)
                && granted(Manifest.permission.READ_LOGS);
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
        ComponentName me = new ComponentName(this, MiKeyService.class);
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me.flattenToString()) || s.equalsIgnoreCase(me.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }

    private boolean ignoringBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    // --- вёрстка ---

    // новый раздел: пункт слева и его содержимое справа
    private void section(String title) {
        int index = sections.size();
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        TextView heading = text(title, 30, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(dp(4), 0, 0, dp(16));
        body.addView(heading);
        content.addView(body);
        sections.add(body);

        TextView item = text(title, 18, Color.WHITE);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(18), 0, dp(18), 0);
        item.setFocusable(true);
        item.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_selected}, {}},
                new int[]{FOCUS_TEXT, Color.WHITE, GRAY_TEXT}));
        item.setBackground(navBackground());
        item.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                showSection(index);
            }
        });
        // вправо с раздела сразу к его первому пункту
        item.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && firstRows.get(index) != null) {
                firstRows.get(index).requestFocus();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(4);
        nav.addView(item, lp);
        navItems.add(item);
        firstRows.add(null);
        group = null;
    }

    private void showSection(int index) {
        for (int i = 0; i < sections.size(); i++) {
            sections.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
            navItems.get(i).setSelected(i == index);
        }
    }

    private LinearLayout currentSection() {
        return (LinearLayout) sections.get(sections.size() - 1);
    }

    private void caption(String text) {
        TextView tv = text(text, 14, GRAY_TEXT);
        tv.setPadding(dp(8), dp(24), 0, dp(8));
        currentSection().addView(tv);
        group = null;
    }

    private void hint(String text) {
        TextView tv = text(text, 14, GRAY_TEXT);
        tv.setPadding(dp(8), dp(12), dp(8), dp(4));
        tv.setLineSpacing(0, 1.15f);
        currentSection().addView(tv);
        group = null;
    }

    // строки внутри группы идут одной карточкой с тонкими разделителями
    private void startGroup() {
        group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(GROUP);
        bg.setCornerRadius(dp(18));
        group.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        currentSection().addView(group, lp);
    }

    private Row row(String label) {
        if (group == null) {
            startGroup();
        }
        if (group.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(DIVIDER);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dlp.leftMargin = dp(18);
            dlp.rightMargin = dp(18);
            group.addView(divider, dlp);
        }
        Row row = new Row(label);
        group.addView(row.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        int index = sections.size() - 1;
        if (firstRows.get(index) == null) {
            firstRows.set(index, row.view);
        }
        // влево из пунктов назад к разделу
        View navItem = navItems.get(index);
        row.view.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                navItem.requestFocus();
                return true;
            }
            return false;
        });
        return row;
    }

    private TextView text(String value, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    private static ColorStateList focusColors(int normal, int focused) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, normal});
    }

    // строка: подпись слева, справа значение и значок (галочка, стрелка или стрелки шага)
    private final class Row {
        final LinearLayout view;
        final TextView value;
        final ImageView icon;
        ImageView left;

        Row(String label) {
            view = new LinearLayout(SettingsActivity.this);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(18), 0, dp(14), 0);
            view.setFocusable(true);
            view.setClickable(true);
            view.setBackground(rowBackground());

            TextView name = text(label, 17, Color.WHITE);
            name.setTextColor(focusColors(Color.WHITE, FOCUS_TEXT));
            name.setDuplicateParentStateEnabled(true);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            view.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            value = text("", 16, GRAY_TEXT);
            value.setDuplicateParentStateEnabled(true);
            view.addView(value);

            icon = new ImageView(SettingsActivity.this);
            icon.setDuplicateParentStateEnabled(true);
            icon.setVisibility(View.GONE);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
            ilp.leftMargin = dp(10);
            view.addView(icon, ilp);
        }

        void status(String text, int color) {
            value.setText(text);
            value.setTextColor(focusColors(color, FOCUS_SUBTEXT));
        }

        // переход дальше: стрелка вправо
        void chevron() {
            icon.setImageResource(R.drawable.ic_chevron);
            icon.setImageTintList(focusColors(GRAY_TEXT, FOCUS_SUBTEXT));
            icon.setVisibility(View.VISIBLE);
        }

        // выбор или включено: галочка, у невыбранного пустое место, чтобы текст не прыгал
        void check(boolean on) {
            icon.setImageResource(R.drawable.ic_check);
            icon.setImageTintList(focusColors(OK, FOCUS_TEXT));
            icon.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }

        // значение со стрелками по бокам: влево меньше, вправо больше
        void stepper() {
            left = new ImageView(SettingsActivity.this);
            left.setImageResource(R.drawable.ic_chevron);
            left.setRotation(180);
            left.setDuplicateParentStateEnabled(true);
            left.setImageTintList(focusColors(GRAY_TEXT, FOCUS_SUBTEXT));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(22), dp(22));
            llp.rightMargin = dp(10);
            view.addView(left, view.indexOfChild(value), llp);
            chevron();
        }
    }

    private StateListDrawable rowBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(0xFFC7C7CC, 13));
        states.addState(new int[]{android.R.attr.state_focused}, shape(FOCUS_BG, 13));
        states.addState(new int[]{}, shape(Color.TRANSPARENT, 13));
        return states;
    }

    private StateListDrawable navBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(FOCUS_BG, 14));
        states.addState(new int[]{android.R.attr.state_selected}, shape(0xFF2C2C2E, 14));
        states.addState(new int[]{}, shape(Color.TRANSPARENT, 14));
        return states;
    }

    private GradientDrawable shape(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }
}
