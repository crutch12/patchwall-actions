package com.criit.patchwallactions;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

// язык приложения: системный (русский или английский по умолчанию) либо выбранный в настройках
final class Lang {

    static final String SYSTEM = "system";
    static final String[] VALUES = {SYSTEM, "en", "ru"};

    private Lang() {
    }

    static String get(Context ctx) {
        return ctx.getSharedPreferences("lang", Context.MODE_PRIVATE).getString("lang", SYSTEM);
    }

    static void set(Context ctx, String lang) {
        ctx.getSharedPreferences("lang", Context.MODE_PRIVATE).edit().putString("lang", lang).apply();
    }

    // для attachBaseContext: при системном языке ресурсы выбирает сама система
    static Context wrap(Context base) {
        String lang = get(base);
        if (SYSTEM.equals(lang)) {
            return base;
        }
        Configuration conf = new Configuration(base.getResources().getConfiguration());
        conf.setLocale(new Locale(lang));
        return base.createConfigurationContext(conf);
    }
}
