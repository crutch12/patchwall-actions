package com.criit.patchwallactions;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Кнопка Xiaomi (код 192) до приложений не доходит: прошивка сама её обрабатывает
 * и на отпускание запускает PatchWall. Переназначить это нельзя, поэтому:
 * - нажатия читаем из системного журнала, прошивка пишет туда каждое нажатие и удержание;
 * - PatchWall, который всё равно всплывает, закрываем "назад", под ним остаётся то, что было.
 * Кнопки пульта не перехватываются, поэтому залипать нечему.
 *
 * Одно нажатие и удержание делают действия из настроек (по умолчанию подсветка и переключение
 * источника), двойное открывает меню.
 */
public class MiKeyService extends AccessibilityService {

    static final String PATCHWALL = "com.mitv.tvhome.atv";
    // pid в формате brief: "D/Tag(  881): ..."
    private static final Pattern PID = Pattern.compile("\\(\\s*(\\d+)\\)");
    // сколько ждём старую строку, которую logcat -T 1 отдаёт сразу при старте
    private static final long HISTORY_MS = 300;
    private static final long DOUBLE_PRESS_MS = 400;
    // если потеряли строку про отпускание, через столько считаем кнопку отпущенной
    private static final long STUCK_MS = 5000;
    // PatchWall не реагирует на "назад", пока грузится, поэтому пробуем несколько раз
    private static final long CLOSE_DELAY_MS = 200;
    private static final long CLOSE_RETRY_MS = 400;
    private static final int CLOSE_ATTEMPTS = 5;
    // если PatchWall так и не всплыл, меню всё равно откроется
    private static final long MENU_FALLBACK_MS = 3000;

    // последнее приложение на экране, кроме нас и PatchWall
    static String foregroundApp;
    private static MiKeyService instance;
    // когда последний раз всплывал PatchWall, меню не должно из-за него закрываться
    static long patchWallAt;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable singlePress = () -> {
        pending = false;
        if (Actions.runSinglePress(this)) {
            openMenu();
        }
    };
    private final Runnable closePatchWall = this::closePatchWall;
    private final Runnable menuFallback = this::openRequestedMenu;
    private boolean pending;
    // на двойное нажатие меню открываем только после того, как убрали PatchWall,
    // иначе после "назад" система возвращает прошлое приложение, а не меню
    private boolean menuRequested;
    private boolean patchWallClosed;
    private boolean down;
    private boolean longPressed;
    private long downAt;
    private int closeAttempts;
    // без READ_LOGS (не выдали через adb) считаем нажатием само появление PatchWall
    private boolean logsAvailable;
    private boolean patchWallCounted;

    private Process logcat;
    // чёрный слой поверх экрана для уровней ниже нуля
    private View dimView;
    private TextView sleepBanner;
    private ValueAnimator sleepFade;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        showDim(Backlight.dim(this));
        logsAvailable = checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
        if (logsAvailable) {
            startReader();
        }
    }

    // --- журнал ---

    // журнал читаем только у system_server: logd фильтрует по pid сам, и TV не грузится разбором
    // всего журнала. pid берём из уже записанных строк системы, ждать нажатия для этого не нужно
    private void startReader() {
        Thread reader = new Thread(() -> {
            try {
                String pid = systemServerPid();
                List<String> args = new ArrayList<>(Arrays.asList("logcat", "-v", "brief", "-T", "1"));
                if (pid != null) {
                    args.add("--pid=" + pid);
                }
                args.addAll(Arrays.asList("-s", "MiTVWindowPolicyInjector:D", "MiTVMiKeyHandler:D"));
                logcat = Runtime.getRuntime().exec(args.toArray(new String[0]));
                long startedAt = SystemClock.uptimeMillis();
                BufferedReader in = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
                boolean first = true;
                String line;
                while ((line = in.readLine()) != null) {
                    // -T 1 сразу отдаёт последнюю старую строку, если она есть. Живое нажатие так быстро не придёт
                    if (first) {
                        first = false;
                        if (SystemClock.uptimeMillis() - startedAt < HISTORY_MS) {
                            continue;
                        }
                    }
                    if (line.contains("handleAction192")) {
                        main.post(this::onKeyLine);
                    } else if (line.contains("onLongPress:192")) {
                        main.post(this::onLongPress);
                    }
                }
            } catch (Exception ignored) {
                // без READ_LOGS или если процесс убили, просто перестаём слушать
            }
        }, "mikey-logcat");
        reader.setDaemon(true);
        reader.start();
    }

    // pid system_server по свежим строкам его тегов; null, если не нашли (тогда читаем без фильтра)
    private static String systemServerPid() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "brief", "-t", "300", "-s",
                    "ActivityManager:*", "ActivityTaskManager:*", "MiTVWindowPolicyInjector:*", "WindowManager:*"});
            BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            String pid = null;
            while ((line = in.readLine()) != null) {
                Matcher m = PID.matcher(line);
                if (m.find()) {
                    pid = m.group(1);
                }
            }
            p.destroy();
            return pid;
        } catch (Exception e) {
            return null;
        }
    }

    // строки идут парами: нажата, отпущена
    private void onKeyLine() {
        long now = SystemClock.uptimeMillis();
        if (down && now - downAt > STUCK_MS) {
            down = false;
        }
        if (!down) {
            down = true;
            downAt = now;
            longPressed = false;
            return;
        }
        down = false;
        if (!longPressed) {
            onTap();
        }
    }

    private void onLongPress() {
        if (!down) {
            return;
        }
        longPressed = true;
        cancelPending();
        if (MenuActivity.current != null) {
            MenuActivity.current.finish();
        } else if (Actions.runHold(this)) {
            openMenu();
        }
    }

    private void onTap() {
        // в последнюю минуту таймера кнопка откладывает сон
        if (SleepTimer.warning(this)) {
            cancelPending();
            SleepTimer.add(this, SleepTimer.STEP);
            return;
        }
        // нажатие при открытом меню просто закрывает его
        if (MenuActivity.current != null) {
            cancelPending();
            MenuActivity.current.finish();
            return;
        }
        if (pending) {
            cancelPending();
            if (!patchWallInstalled(this)) {
                openMenu();
                return;
            }
            menuRequested = true;
            patchWallClosed = false;
            main.postDelayed(menuFallback, MENU_FALLBACK_MS);
            return;
        }
        pending = true;
        main.postDelayed(singlePress, DOUBLE_PRESS_MS);
    }

    private void cancelPending() {
        pending = false;
        main.removeCallbacks(singlePress);
    }

    // если PatchWall удалить для пользователя (pm uninstall --user 0), прошивка по кнопке
    // ничего не открывает, и мелькать нечему. Флаг patchwall_enable при этом должен остаться true,
    // иначе прошивка откроет Live TV
    static boolean patchWallInstalled(android.content.Context ctx) {
        try {
            ctx.getPackageManager().getApplicationInfo(PATCHWALL, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openRequestedMenu() {
        if (menuRequested) {
            menuRequested = false;
            main.removeCallbacks(menuFallback);
            openMenu();
        }
    }

    private void openMenu() {
        if (MenuActivity.current == null) {
            startActivity(new Intent(this, MenuActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // --- окна ---

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) {
            return;
        }
        String pkg = event.getPackageName().toString();

        if (PATCHWALL.equals(pkg)) {
            // PatchWall ещё грузится: меню откроем после него, а не поверх
            if (menuRequested) {
                main.removeCallbacks(menuFallback);
                main.postDelayed(menuFallback, MENU_FALLBACK_MS);
            }
            if (!logsAvailable && !patchWallCounted) {
                patchWallCounted = true;
                onTap();
            }
            patchWallAt = SystemClock.uptimeMillis();
            closeAttempts = 0;
            main.removeCallbacks(closePatchWall);
            main.postDelayed(closePatchWall, CLOSE_DELAY_MS);
            return;
        }
        patchWallCounted = false;
        if (getPackageName().equals(pkg) || "com.android.systemui".equals(pkg)) {
            return;
        }
        foregroundApp = pkg;
        Apps.onForeground(this, pkg);
    }

    // жмём "назад", только когда сверху действительно PatchWall.
    // он может ещё грузиться, поэтому проверяем несколько раз
    private void closePatchWall() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence active = root == null ? null : root.getPackageName();
        if (root != null) {
            root.recycle();
        }
        if (active != null && PATCHWALL.contentEquals(active) && !MenuActivity.resumed) {
            patchWallAt = SystemClock.uptimeMillis();
            performGlobalAction(GLOBAL_ACTION_BACK);
            patchWallClosed = true;
        } else if (patchWallClosed) {
            openRequestedMenu();
        }
        if (++closeAttempts < CLOSE_ATTEMPTS) {
            main.postDelayed(closePatchWall, CLOSE_RETRY_MS);
        }
    }

    // затемнение: 0 убирает слой, 1..80 это процент черноты
    static void applyDim(int percent) {
        MiKeyService service = instance;
        if (service != null) {
            service.main.post(() -> service.showDim(percent));
        }
    }

    // последняя минута таймера: баннер внизу и плавное затемнение до конца
    static void showSleepWarning(long durationMs) {
        MiKeyService service = instance;
        if (service != null) {
            service.main.post(() -> service.startSleepWarning(durationMs));
        }
    }

    static void hideSleepWarning() {
        MiKeyService service = instance;
        if (service != null) {
            service.main.post(service::stopSleepWarning);
        }
    }

    private void startSleepWarning(long durationMs) {
        stopSleepWarning();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        float d = getResources().getDisplayMetrics().density;
        sleepBanner = new TextView(this);
        sleepBanner.setText(Lang.wrap(getApplicationContext()).getString(R.string.sleep_warning));
        sleepBanner.setTextColor(Color.WHITE);
        sleepBanner.setTextSize(16);
        sleepBanner.setPadding((int) (24 * d), (int) (14 * d), (int) (24 * d), (int) (14 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF02C2C2E);
        bg.setCornerRadius(18 * d);
        sleepBanner.setBackground(bg);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (48 * d);
        wm.addView(sleepBanner, lp);

        sleepFade = ValueAnimator.ofInt(Backlight.dim(this), Backlight.MIN * -1);
        sleepFade.setDuration(durationMs);
        sleepFade.addUpdateListener(a -> showDim((int) a.getAnimatedValue()));
        sleepFade.start();
    }

    private void stopSleepWarning() {
        if (sleepFade != null) {
            sleepFade.cancel();
            sleepFade = null;
            showDim(Backlight.dim(this));
        }
        if (sleepBanner != null) {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(sleepBanner);
            sleepBanner = null;
        }
    }

    private void showDim(int percent) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (percent <= 0) {
            if (dimView != null) {
                wm.removeView(dimView);
                dimView = null;
            }
            return;
        }
        if (dimView == null) {
            dimView = new View(this);
            dimView.setBackgroundColor(Color.BLACK);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            wm.addView(dimView, lp);
        }
        dimView.setAlpha(percent / 100f);
    }

    // для таймера сна: "заблокировать экран" на TV уводит его в сон
    static boolean sleepNow() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
        }
        cancelPending();
        stopSleepWarning();
        showDim(0);
        main.removeCallbacks(closePatchWall);
        main.removeCallbacks(menuFallback);
        if (logcat != null) {
            logcat.destroy();
        }
        super.onDestroy();
    }
}
