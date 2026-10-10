package com.criit.patchwallactions;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

// настройка через adb самого TV, чтобы всё делалось из приложения, без компьютера
final class Setup {

    interface Callback {
        void done(String error);
    }

    // сколько ждём, пока на TV подтвердят "Разрешить отладку"
    private static final int AUTH_WAIT_MS = 60000;

    private Setup() {
    }

    // права, служба, работа в фоне
    static void grantAll(Context ctx, Callback callback) {
        String pkg = ctx.getPackageName();
        ComponentName component = new ComponentName(ctx, MiKeyService.class);
        String service = component.flattenToString();
        String serviceShort = component.flattenToShortString();
        run(ctx, callback, adb -> {
            adb.shell("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS");
            adb.shell("pm grant " + pkg + " android.permission.READ_LOGS");
            adb.shell("pm grant " + pkg + " android.permission.ACCESS_FINE_LOCATION");
            adb.shell("dumpsys deviceidle whitelist +" + pkg);
            adb.shell("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");

            // READ_LOGS процесс получает только при запуске, поэтому перезапускаем приложение
            // и перевключаем службу. Делает это отдельный процесс на TV: наш при этом умрёт.
            // Чужие службы не трогаем
            String current = adb.shell("settings get secure enabled_accessibility_services");
            StringBuilder others = new StringBuilder();
            if (!current.isEmpty() && !"null".equals(current)) {
                for (String s : current.split(":")) {
                    if (!s.isEmpty() && !s.equalsIgnoreCase(service) && !s.equalsIgnoreCase(serviceShort)) {
                        others.append(others.length() > 0 ? ":" : "").append(s);
                    }
                }
            }
            String without = others.length() > 0
                    ? "settings put secure enabled_accessibility_services " + others
                    : "settings delete secure enabled_accessibility_services";
            String with = "settings put secure enabled_accessibility_services "
                    + (others.length() > 0 ? others + ":" : "") + service;
            // setsid: в отдельной сессии команда переживёт обрыв нашего adb-соединения
            adb.shell("setsid -d sh -c '"
                    + "sleep 1; " + without + "; am force-stop " + pkg + "; " + with + "; "
                    + "settings put secure accessibility_enabled 1; am start -n " + pkg + "/.SettingsActivity"
                    + "' </dev/null >/dev/null 2>&1");
        });
    }

    // PatchWall удаляется только для пользователя, вернуть можно в любой момент
    static void setPatchWall(Context ctx, boolean installed, Callback callback) {
        run(ctx, callback, adb -> {
            if (installed) {
                adb.shell("cmd package install-existing " + MiKeyService.PATCHWALL);
            } else {
                // при false прошивка вместо PatchWall открывает Live TV
                adb.shell("settings put global patchwall_enable true");
                adb.shell("pm uninstall -k --user 0 " + MiKeyService.PATCHWALL);
            }
        });
    }

    private interface Commands {
        void run(LocalAdb adb) throws Exception;
    }

    private static void run(Context ctx, Callback callback, Commands commands) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String error = null;
            try (LocalAdb adb = LocalAdb.connect(app, AUTH_WAIT_MS)) {
                commands.run(adb);
            } catch (Exception e) {
                error = LocalAdb.reachable()
                        ? "TV не разрешил отладку. Повторите и нажмите Разрешить"
                        : "Включите отладку по сети в настройках для разработчиков";
            }
            String result = error;
            main.post(() -> callback.done(result));
        }, "setup-adb").start();
    }
}
