package com.criit.patchwallactions;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;

// подсветка панели MediaTek живёт в Settings.Global, у каждого входа своё значение
final class Backlight {

    static final String KEY = "picture_backlight";

    private Backlight() {
    }

    // WRITE_SECURE_SETTINGS выдаётся только через adb, без него подсветку не трогаем
    static boolean canWrite(Context ctx) {
        return ctx.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static int get(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), KEY, 50);
    }

    static void set(Context ctx, int value) {
        if (!canWrite(ctx)) {
            return;
        }
        Settings.Global.putInt(ctx.getContentResolver(), KEY, value);
        if (value > 0) {
            prefs(ctx).edit().putInt("lastLevel", value).apply();
        }
    }

    // последний ненулевой уровень <-> 0
    static void toggle(Context ctx) {
        if (!canWrite(ctx)) {
            return;
        }
        int cur = get(ctx);
        if (cur > 0) {
            prefs(ctx).edit().putInt("lastLevel", cur).apply();
            Settings.Global.putInt(ctx.getContentResolver(), KEY, 0);
        } else {
            set(ctx, prefs(ctx).getInt("lastLevel", 50));
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("backlight", Context.MODE_PRIVATE);
    }
}
