package com.criit.patchwallactions;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Кнопка Xiaomi (код 192) до приложений не доходит: прошивка сама её обрабатывает
 * и на отпускание запускает PatchWall. Переназначить это нельзя, поэтому:
 * - нажатия читаем из системного журнала, прошивка пишет туда каждое нажатие и удержание;
 * - PatchWall, который всё равно всплывает, закрываем "назад", под ним остаётся то, что было.
 * Кнопки пульта не перехватываются, поэтому залипать нечему.
 *
 * Одно нажатие делает действие из настроек (по умолчанию подсветка), двойное или удержание открывают меню.
 */
public class MiKeyService extends AccessibilityService {

    static final String PATCHWALL = "com.mitv.tvhome.atv";
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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        logsAvailable = checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
        if (logsAvailable) {
            startReader();
        }
    }

    // --- журнал ---

    private void startReader() {
        Thread reader = new Thread(() -> {
            try {
                logcat = Runtime.getRuntime().exec(new String[]{
                        "logcat", "-T", "1", "-s", "MiTVWindowPolicyInjector:D", "MiTVMiKeyHandler:D"});
                BufferedReader in = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
                String line;
                while ((line = in.readLine()) != null) {
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
        openMenu();
    }

    private void onTap() {
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
        main.removeCallbacks(closePatchWall);
        main.removeCallbacks(menuFallback);
        if (logcat != null) {
            logcat.destroy();
        }
        super.onDestroy();
    }
}
