package com.criit.patchwallactions;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Пункт управления в духе iOS: сетка из 4 колонок, стрелки вверх/вниз сохраняют колонку.
 * ОК выполняет действие, долгий ОК открывает системные настройки этого пункта.
 */
public class MenuActivity extends Activity {

    private static final String TV_APP = "com.mediatek.wwtv.tvcenter";
    private static final String AMNEZIA = "org.amnezia.vpn";
    private static final String HOME = "home";

    private static final int TILE = 0xF02C2C2E;
    private static final int BLUE = 0xFF2C5A8C;
    private static final int GREEN = 0xFF2F6B47;
    private static final int ORANGE = 0xFF8A5A24;
    private static final int GRAY_TEXT = 0xFFAEAEB2;
    // фокус как в Android TV: светлая плитка и тёмный текст, без рамки поверх содержимого
    private static final int FOCUS_BG = 0xFFE5E5EA;
    private static final int FOCUS_TEXT = 0xFF1C1C1E;
    private static final int FOCUS_SUBTEXT = 0xFF48484A;
    private static final float DIM = 0.85f;
    private static final int COLUMNS = 4;
    // пока двигают слайдер, видна только она и картинка без затемнения
    private static final long PREVIEW_MS = 1500;

    // открытое сейчас меню, чтобы служба могла его закрыть
    static MenuActivity current;
    // меню сейчас на переднем плане: служба не должна жать "назад" за PatchWall
    static boolean resumed;

    private float density;
    private SharedPreferences prefs;
    private LinearLayout panel;
    private BrightnessSlider slider;
    private final List<View> fadeOnPreview = new ArrayList<>();
    private final List<List<View>> grid = new ArrayList<>();
    private int activeInput;

    private Tile pictureTile;
    private Tile soundTile;
    private Tile timerTile;
    private Tile nightTile;
    private Tile wifiTile;
    private Tile vpnTile;
    private Tile blueTile;

    private final Handler handler = new Handler();
    private final Runnable endPreview = () -> setPreview(false);
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            bindTimer();
            handler.postDelayed(this, 30000);
        }
    };

    private final ContentObserver settingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            bindAll();
        }
    };

    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            bindWifi();
            bindVpn();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        density = getResources().getDisplayMetrics().density;
        // у прозрачной темы затемнение из стиля на этой прошивке не применяется
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(DIM);
        prefs = getSharedPreferences("menu", MODE_PRIVATE);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);
        panel.setClipToPadding(false);

        buildBrightness();
        buildInputs();
        buildControls();
        buildFooter();
        wireFocus();

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(560), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_VERTICAL | Gravity.END);
        lp.rightMargin = dp(48);
        root.addView(panel, lp);
        setContentView(root);

        bindAll();
        slider.requestFocus();
        current = this;
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        for (String key : new String[]{Backlight.KEY, TvSettings.PICTURE_MODE, TvSettings.SOUND_STYLE, TvSettings.BLUE_LIGHT}) {
            getContentResolver().registerContentObserver(Settings.Global.getUriFor(key), false, settingsObserver);
        }
        IntentFilter filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(networkReceiver, filter);
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        getContentResolver().unregisterContentObserver(settingsObserver);
        unregisterReceiver(networkReceiver);
        handler.removeCallbacks(tick);
    }

    // меню перекрыли другим приложением или нажали Home, больше оно не нужно.
    // PatchWall от кнопки не в счёт
    @Override
    protected void onStop() {
        super.onStop();
        if (SystemClock.uptimeMillis() - MiKeyService.patchWallAt > 2500) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (current == this) {
            current = null;
        }
        super.onDestroy();
    }

    // --- строки меню ---

    private void buildBrightness() {
        panel.addView(caption("Яркость"));
        slider = new BrightnessSlider(this);
        slider.setListener(value -> {
            if (!requireAccess()) {
                return;
            }
            Backlight.set(this, value);
            setPreview(true);
            handler.removeCallbacks(endPreview);
            handler.postDelayed(endPreview, PREVIEW_MS);
        });
        slider.setOnClickListener(v -> {
            if (requireAccess()) {
                Backlight.toggle(this);
            }
        });
        slider.setOnLongClickListener(v -> openSystem(
                Actions.action("com.mitv.settings.PICTURE_SETTINGS"),
                Actions.action(Settings.ACTION_DISPLAY_SETTINGS)));
        panel.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        grid.add(Collections.singletonList(slider));
    }

    private void buildInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> hdmi = new ArrayList<>();
        List<TvInputInfo> all = tim == null ? Collections.<TvInputInfo>emptyList() : tim.getTvInputList();
        for (TvInputInfo info : all) {
            if (info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) {
                hdmi.add(info);
            }
        }
        if (hdmi.isEmpty()) {
            return;
        }
        Collections.sort(hdmi, (a, b) -> a.getId().compareTo(b.getId()));

        View label = caption("Источник");
        panel.addView(label);
        fadeOnPreview.add(label);
        LinearLayout row = row(dp(72));
        String current = TV_APP.equals(MiKeyService.foregroundApp) ? prefs.getString("lastInput", HOME) : HOME;
        List<View> cells = new ArrayList<>();

        cells.add(inputTile(row, "Главный", HOME, current, true));
        for (int i = 0; i < hdmi.size() && cells.size() < COLUMNS; i++) {
            String id = hdmi.get(i).getId();
            boolean connected = tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED;
            cells.add(inputTile(row, "HDMI " + (i + 1), id, current, connected));
        }
        for (int i = 0; i < cells.size(); i++) {
            if (cells.get(i).isSelected()) {
                activeInput = i;
            }
        }
        grid.add(cells);
    }

    private View inputTile(LinearLayout row, String title, String id, String current, boolean connected) {
        Tile t = new Tile(title);
        boolean active = id.equals(current);
        t.bind(connected ? "" : "нет сигнала", active ? BLUE : TILE);
        t.view.setSelected(active);
        if (!connected) {
            t.title.setTextColor(textColors(GRAY_TEXT, FOCUS_TEXT));
        }
        t.view.setOnClickListener(v -> {
            if (connected) {
                switchInput(id);
            } else {
                toast("Нет сигнала на " + title);
            }
        });
        t.view.setOnLongClickListener(v -> openSystem(
                Actions.action("com.android.tv.action.VIEW_INPUTS"),
                Actions.action(Settings.ACTION_SETTINGS)));
        addCell(row, t.view);
        return t.view;
    }

    private void buildControls() {
        View label = caption("Управление");
        panel.addView(label);
        fadeOnPreview.add(label);

        List<Tile> tiles = new ArrayList<>();
        if (Actions.tileVisible(this, "picture")) {
            pictureTile = new Tile("Картинка");
            pictureTile.view.setOnClickListener(v -> cycle(TvSettings.PICTURE_MODE, TvSettings.PICTURE_VALUES, 7));
            pictureTile.view.setOnLongClickListener(v -> openSystem(
                    Actions.action("com.mitv.settings.PICTURE_SETTINGS"),
                    Actions.action(Settings.ACTION_DISPLAY_SETTINGS)));
            tiles.add(pictureTile);
        }
        if (Actions.tileVisible(this, "sound")) {
            soundTile = new Tile("Звук");
            soundTile.view.setOnClickListener(v -> cycle(TvSettings.SOUND_STYLE, TvSettings.SOUND_VALUES, 0));
            soundTile.view.setOnLongClickListener(v -> openSystem(
                    Actions.action(Settings.ACTION_SOUND_SETTINGS)));
            tiles.add(soundTile);
        }
        if (Actions.tileVisible(this, "timer")) {
            timerTile = new Tile("Таймер сна");
            timerTile.view.setOnClickListener(v -> {
                SleepTimer.cycle(this);
                bindTimer();
            });
            timerTile.view.setOnLongClickListener(v -> openSystem(
                    Actions.component("com.android.tv.settings",
                            "com.android.tv.settings.partnercustomizer.timer.TimerActivity"),
                    Actions.action(Settings.ACTION_DATE_SETTINGS)));
            tiles.add(timerTile);
        }
        if (Actions.tileVisible(this, "night")) {
            nightTile = new Tile("Ночь");
            nightTile.view.setOnClickListener(v -> {
                if (requireAccess()) {
                    Actions.toggleNight(this);
                    bindAll();
                }
            });
            nightTile.view.setOnLongClickListener(v -> openSystem(
                    Actions.action("com.mitv.settings.PICTURE_SETTINGS"),
                    Actions.action(Settings.ACTION_DISPLAY_SETTINGS)));
            tiles.add(nightTile);
        }
        if (Actions.tileVisible(this, "wifi")) {
            wifiTile = new Tile("Wi-Fi");
            wifiTile.view.setOnClickListener(v -> toggleWifi());
            wifiTile.view.setOnLongClickListener(v -> openSystem(Actions.action(Settings.ACTION_WIFI_SETTINGS)));
            tiles.add(wifiTile);
        }
        if (Actions.tileVisible(this, "vpn")) {
            vpnTile = new Tile("Amnezia VPN");
            vpnTile.view.setOnClickListener(v -> openAmnezia());
            vpnTile.view.setOnLongClickListener(v -> openSystem(
                    Actions.action(Settings.ACTION_VPN_SETTINGS),
                    Actions.component("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity")));
            tiles.add(vpnTile);
        }
        if (Actions.tileVisible(this, "blue")) {
            blueTile = new Tile("Синий свет");
            blueTile.view.setOnClickListener(v -> {
                if (requireAccess()) {
                    TvSettings.put(this, TvSettings.BLUE_LIGHT,
                            TvSettings.get(this, TvSettings.BLUE_LIGHT, 0) == 0 ? 1 : 0);
                }
            });
            blueTile.view.setOnLongClickListener(v -> openSystem(
                    Actions.action("com.mitv.settings.PICTURE_SETTINGS"),
                    Actions.action(Settings.ACTION_DISPLAY_SETTINGS)));
            tiles.add(blueTile);
        }
        Tile settings = new Tile("Настройки");
        settings.bind("", TILE);
        settings.view.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        });
        settings.view.setOnLongClickListener(v -> openSystem(Actions.action(Settings.ACTION_SETTINGS)));
        tiles.add(settings);

        // по 4 в строке, неполная строка остаётся выровненной по левому краю
        for (int start = 0; start < tiles.size(); start += COLUMNS) {
            LinearLayout row = row(dp(84));
            List<View> cells = new ArrayList<>();
            for (int i = start; i < Math.min(start + COLUMNS, tiles.size()); i++) {
                addCell(row, tiles.get(i).view);
                cells.add(tiles.get(i).view);
            }
            for (int i = cells.size(); i < COLUMNS; i++) {
                addCell(row, new View(this));
            }
            grid.add(cells);
        }
    }

    private void buildFooter() {
        TextView hint = new TextView(this);
        hint.setText("Кнопка Xiaomi: " + Actions.singleLabel(this).toLowerCase()
                + "\nДолгий ОК на пункте открывает его настройки");
        hint.setTextColor(GRAY_TEXT);
        hint.setTextSize(13);
        hint.setPadding(dp(6), dp(16), 0, 0);
        panel.addView(hint);
        fadeOnPreview.add(hint);
    }

    // явные соседи по сетке, чтобы фокус не прыгал
    private void wireFocus() {
        for (List<View> row : grid) {
            for (View v : row) {
                v.setId(View.generateViewId());
            }
        }
        for (int r = 0; r < grid.size(); r++) {
            List<View> row = grid.get(r);
            for (int c = 0; c < row.size(); c++) {
                View v = row.get(c);
                if (r > 0) {
                    List<View> up = grid.get(r - 1);
                    v.setNextFocusUpId(up.get(Math.min(c, up.size() - 1)).getId());
                }
                if (r < grid.size() - 1) {
                    List<View> down = grid.get(r + 1);
                    int target = r == 0 ? activeInput : c;
                    v.setNextFocusDownId(down.get(Math.min(target, down.size() - 1)).getId());
                }
                v.setNextFocusLeftId(row.get(Math.max(c - 1, 0)).getId());
                v.setNextFocusRightId(row.get(Math.min(c + 1, row.size() - 1)).getId());
            }
        }
    }

    // --- состояние ---

    private void bindAll() {
        slider.setValue(Backlight.get(this));
        if (pictureTile != null) {
            pictureTile.bind(TvSettings.label(TvSettings.PICTURE_VALUES, TvSettings.PICTURE_LABELS,
                    TvSettings.get(this, TvSettings.PICTURE_MODE, 7)), TILE);
        }
        if (soundTile != null) {
            soundTile.bind(TvSettings.label(TvSettings.SOUND_VALUES, TvSettings.SOUND_LABELS,
                    TvSettings.get(this, TvSettings.SOUND_STYLE, 0)), TILE);
        }
        if (nightTile != null) {
            boolean on = Actions.nightOn(this);
            nightTile.bind(on ? "Вкл" : "Выкл", on ? ORANGE : TILE);
        }
        if (blueTile != null) {
            boolean on = TvSettings.get(this, TvSettings.BLUE_LIGHT, 0) != 0;
            blueTile.bind(on ? "Вкл" : "Выкл", on ? ORANGE : TILE);
        }
        bindTimer();
        bindWifi();
        bindVpn();
    }

    private void bindTimer() {
        if (timerTile == null) {
            return;
        }
        int left = SleepTimer.minutesLeft(this);
        timerTile.bind(left == 0 ? "Выкл" : left + " мин", left == 0 ? TILE : ORANGE);
    }

    private void bindWifi() {
        if (wifiTile == null) {
            return;
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        boolean on = wm != null && wm.isWifiEnabled();
        String subtitle = "Выкл";
        if (on) {
            WifiInfo info = wm.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null || ssid.contains("unknown ssid")) {
                subtitle = info != null && info.getNetworkId() != -1 ? "Подключён" : "Не подключён";
            } else {
                subtitle = ssid.replace("\"", "");
            }
        }
        wifiTile.bind(subtitle, on ? BLUE : TILE);
    }

    private void bindVpn() {
        if (vpnTile == null) {
            return;
        }
        boolean on = false;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                on = true;
                break;
            }
        }
        vpnTile.bind(on ? "Подключено" : "Не подключено", on ? GREEN : TILE);
    }

    // --- действия ---

    private void cycle(String key, int[] values, int fallback) {
        if (requireAccess()) {
            TvSettings.put(this, key, TvSettings.next(values, TvSettings.get(this, key, fallback)));
        }
    }

    private void setPreview(boolean on) {
        getWindow().setDimAmount(on ? 0f : DIM);
        for (int r = 1; r < grid.size(); r++) {
            for (View v : grid.get(r)) {
                v.animate().alpha(on ? 0f : 1f).setDuration(150).start();
            }
        }
        for (View v : fadeOnPreview) {
            v.animate().alpha(on ? 0f : 1f).setDuration(150).start();
        }
    }

    private void switchInput(String id) {
        prefs.edit().putString("lastInput", id).apply();
        Intent intent = HOME.equals(id)
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                : new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @SuppressWarnings("deprecation")
    private void toggleWifi() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wm.setWifiEnabled(!wm.isWifiEnabled());
        }
    }

    private void openAmnezia() {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(AMNEZIA);
        if (intent == null) {
            intent = getPackageManager().getLaunchIntentForPackage(AMNEZIA);
        }
        if (intent == null) {
            toast("Amnezia VPN не установлен");
            return;
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    private boolean openSystem(Intent... intents) {
        if (Actions.open(this, intents)) {
            finish();
        } else {
            toast("На этом TV нет такого экрана настроек");
        }
        return true;
    }

    // без WRITE_SECURE_SETTINGS настройки TV менять нельзя, отправляем в наши настройки
    private boolean requireAccess() {
        if (Backlight.canWrite(this)) {
            return true;
        }
        toast("Нет доступа к настройкам TV, см. Настройки");
        return false;
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // --- вёрстка ---

    private TextView caption(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(GRAY_TEXT);
        tv.setTextSize(14);
        tv.setPadding(dp(6), dp(16), 0, dp(8));
        return tv;
    }

    private LinearLayout row(int height) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        if (panel.getChildCount() > 0 && !(panel.getChildAt(panel.getChildCount() - 1) instanceof TextView)) {
            lp.topMargin = dp(10);
        }
        // увеличенная при фокусе плитка не должна обрезаться краями строки
        row.setClipChildren(false);
        row.setClipToPadding(false);
        panel.addView(row, lp);
        return row;
    }

    private void addCell(LinearLayout row, View cell) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (row.getChildCount() > 0) {
            lp.leftMargin = dp(10);
        }
        row.addView(cell, lp);
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    // плитка: заголовок и подпись со значением
    private final class Tile {
        final LinearLayout view;
        final TextView title;
        final TextView subtitle;

        Tile(String text) {
            view = new LinearLayout(MenuActivity.this);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(18), 0, dp(14), 0);
            view.setFocusable(true);
            view.setClickable(true);
            view.setLongClickable(true);

            title = new TextView(MenuActivity.this);
            title.setText(text);
            title.setTextColor(textColors(Color.WHITE, FOCUS_TEXT));
            title.setDuplicateParentStateEnabled(true);
            title.setTextSize(16);
            title.setSingleLine();
            view.addView(title);

            subtitle = new TextView(MenuActivity.this);
            subtitle.setTextSize(13);
            subtitle.setDuplicateParentStateEnabled(true);
            subtitle.setSingleLine();
            view.addView(subtitle);

            view.setStateListAnimator(lift());
            bind("", TILE);
        }

        // выключенная плитка в фокусе светлеет с тёмным текстом, включённая остаётся своего цвета
        void bind(String text, int color) {
            boolean neutral = color == TILE;
            subtitle.setText(text);
            subtitle.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            subtitle.setTextColor(neutral ? textColors(GRAY_TEXT, FOCUS_SUBTEXT) : ColorStateList.valueOf(0xE6FFFFFF));
            title.setTextColor(neutral ? textColors(Color.WHITE, FOCUS_TEXT) : ColorStateList.valueOf(Color.WHITE));
            view.setBackground(background(color));
        }
    }

    private static ColorStateList textColors(int normal, int focused) {
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{focused, normal});
    }

    // фон: в фокусе светлее (нейтральная плитка становится светлой), при нажатии чуть темнее фокуса
    private StateListDrawable background(int color) {
        int focused = color == TILE ? FOCUS_BG : blend(color, Color.WHITE, 0.22f);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(blend(focused, Color.BLACK, 0.12f)));
        states.addState(new int[]{android.R.attr.state_focused}, shape(focused));
        states.addState(new int[]{}, shape(color));
        return states;
    }

    // подъём в фокусе с тенью и "вдавливание" на нажатие ОК
    private StateListAnimator lift() {
        StateListAnimator animator = new StateListAnimator();
        animator.addState(new int[]{android.R.attr.state_pressed}, scaleTo(1.0f, 2));
        animator.addState(new int[]{android.R.attr.state_focused}, scaleTo(1.06f, 14));
        animator.addState(new int[]{}, scaleTo(1f, 0));
        return animator;
    }

    private Animator scaleTo(float scale, int liftDp) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(null, View.SCALE_X, scale),
                ObjectAnimator.ofFloat(null, View.SCALE_Y, scale),
                ObjectAnimator.ofFloat(null, View.TRANSLATION_Z, dp(liftDp)));
        set.setDuration(140);
        set.setInterpolator(new DecelerateInterpolator(2f));
        return set;
    }

    private static int blend(int from, int to, float amount) {
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * amount);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * amount);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount);
        return Color.argb(0xFF, r, g, b);
    }

    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(22));
        return d;
    }
}
