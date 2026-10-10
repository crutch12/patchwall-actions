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

    // ниже нуля подсветка остаётся на 0, а поверх экрана кладётся чёрный слой (как у Twilight)
    static final int MIN = -80;

    // "последний" уровень для верхнего пресета. Уровни кратны 5, поэтому -1 не пересекается с ними
    static final int LAST = -1;

    // уровень от MIN до 100: отрицательный значит дополнительное затемнение
    static int get(Context ctx) {
        int dim = dim(ctx);
        return dim > 0 ? -dim : Settings.Global.getInt(ctx.getContentResolver(), KEY, 50);
    }

    static void set(Context ctx, int value) {
        if (!canWrite(ctx)) {
            return;
        }
        apply(ctx, value);
        rememberLast(ctx, value);
    }

    static int dim(Context ctx) {
        return prefs(ctx).getInt("dim", 0);
    }

    private static void apply(Context ctx, int value) {
        value = Math.max(MIN, Math.min(100, value));
        Settings.Global.putInt(ctx.getContentResolver(), KEY, Math.max(value, 0));
        prefs(ctx).edit().putInt("dim", Math.max(-value, 0)).apply();
        MiKeyService.applyDim(Math.max(-value, 0));
    }

    static int low(Context ctx) {
        return prefs(ctx).getInt("low", 0);
    }

    static int high(Context ctx) {
        return prefs(ctx).getInt("high", LAST);
    }

    static void setPreset(Context ctx, int low, int high) {
        prefs(ctx).edit().putInt("low", low).putInt("high", high).apply();
    }

    static int lastLevel(Context ctx) {
        return prefs(ctx).getInt("lastLevel", 50);
    }

    // нижний пресет <-> верхний (по умолчанию 0 <-> последний уровень)
    static void toggle(Context ctx) {
        if (!canWrite(ctx)) {
            return;
        }
        int cur = get(ctx);
        int low = low(ctx);
        if (cur != low) {
            rememberLast(ctx, cur);
            apply(ctx, low);
            return;
        }
        int high = high(ctx);
        int target = high == LAST ? lastLevel(ctx) : high;
        // если верхний совпал с нижним, переключать некуда, берём последний
        if (target == low) {
            target = lastLevel(ctx);
        }
        apply(ctx, target);
    }

    // нижний пресет не считается "последним", иначе вернуться было бы некуда
    private static void rememberLast(Context ctx, int value) {
        if (value != low(ctx)) {
            prefs(ctx).edit().putInt("lastLevel", value).apply();
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("backlight", Context.MODE_PRIVATE);
    }
}
