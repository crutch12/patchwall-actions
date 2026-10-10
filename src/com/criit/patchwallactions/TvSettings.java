package com.criit.patchwallactions;

import android.content.Context;
import android.provider.Settings;

// настройки картинки и звука MediaTek/Xiaomi лежат в Settings.Global,
// TVMenuSettingsService следит за ними и сразу применяет
final class TvSettings {

    static final String PICTURE_MODE = "picture_mode";
    static final String SOUND_STYLE = "sound_style";
    static final String BLUE_LIGHT = "picture_blue_light";

    // значения подобраны на MiTV-MOOQ1 по экрану настроек
    static final int[] PICTURE_VALUES = {7, 3, 2, 9};
    static final String[] PICTURE_LABELS = {"Стандарт", "Яркий", "Спорт", "Кино"};
    static final int[] SOUND_VALUES = {0, 1, 2, 3};
    static final String[] SOUND_LABELS = {"Стандарт", "Кино", "Новости", "Игра"};

    private TvSettings() {
    }

    static int get(Context ctx, String key, int fallback) {
        return Settings.Global.getInt(ctx.getContentResolver(), key, fallback);
    }

    static void put(Context ctx, String key, int value) {
        if (Backlight.canWrite(ctx)) {
            Settings.Global.putInt(ctx.getContentResolver(), key, value);
        }
    }

    static String label(int[] values, String[] labels, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return labels[i];
            }
        }
        return "Свой";
    }

    // следующий режим по кругу, неизвестное значение начинает с первого
    static int next(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return values[(i + 1) % values.length];
            }
        }
        return values[0];
    }
}
