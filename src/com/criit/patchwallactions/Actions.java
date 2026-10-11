package com.criit.patchwallactions;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

// действие одиночного нажатия, сцена Ночь и переход в системные настройки
final class Actions {

    static final String SINGLE_BACKLIGHT = "backlight";
    static final String SINGLE_TIMER = "timer";
    static final String SINGLE_NIGHT = "night";
    static final String SINGLE_MENU = "menu";

    static final String HOLD_SOURCE = "source";

    // ничего не делать: при нажатии только подсказка, где это настроить
    static final String NONE = "none";

    static final String[] SINGLE_VALUES = {SINGLE_BACKLIGHT, SINGLE_TIMER, SINGLE_NIGHT, SINGLE_MENU, NONE};
    static final int[] SINGLE_LABELS = {R.string.action_backlight, R.string.action_timer, R.string.action_night,
            R.string.action_menu, R.string.action_none};

    static final String[] HOLD_VALUES = {HOLD_SOURCE, SINGLE_MENU, SINGLE_BACKLIGHT, SINGLE_TIMER, SINGLE_NIGHT, NONE};
    static final int[] HOLD_LABELS = {R.string.action_source, R.string.action_menu, R.string.action_backlight,
            R.string.action_timer, R.string.action_night, R.string.action_none};

    private static final int NIGHT_BACKLIGHT = 20;
    private static final int NIGHT_TIMER = 60;

    private Actions() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("actions", Context.MODE_PRIVATE);
    }

    static String singlePress(Context ctx) {
        return prefs(ctx).getString("single", SINGLE_BACKLIGHT);
    }

    static String singleLabel(Context ctx) {
        String value = singlePress(ctx);
        for (int i = 0; i < SINGLE_VALUES.length; i++) {
            if (SINGLE_VALUES[i].equals(value)) {
                return ctx.getString(SINGLE_LABELS[i]);
            }
        }
        return ctx.getString(SINGLE_LABELS[0]);
    }

    static String hold(Context ctx) {
        return prefs(ctx).getString("hold", HOLD_SOURCE);
    }

    // возвращает true, если нужно открыть меню
    static boolean runSinglePress(Context ctx) {
        return run(ctx, singlePress(ctx));
    }

    static String triple(Context ctx) {
        return prefs(ctx).getString("triple", NONE);
    }

    static boolean runTriple(Context ctx) {
        return run(ctx, triple(ctx));
    }

    static boolean runHold(Context ctx) {
        return run(ctx, hold(ctx));
    }

    private static boolean run(Context ctx, String action) {
        switch (action) {
            case NONE:
                Toast.makeText(ctx, Lang.wrap(ctx).getString(R.string.action_unassigned), Toast.LENGTH_SHORT).show();
                return false;
            case HOLD_SOURCE:
                Inputs.cycle(ctx);
                return false;
            case SINGLE_TIMER:
                SleepTimer.add(ctx, SleepTimer.STEP);
                return false;
            case SINGLE_NIGHT:
                toggleNight(ctx);
                return false;
            case SINGLE_MENU:
                return true;
            default:
                Backlight.toggle(ctx);
                return false;
        }
    }

    static boolean nightOn(Context ctx) {
        return prefs(ctx).getBoolean("night", false);
    }

    // ночь: подсветка ниже, синий фильтр, таймер; при выключении возвращаем как было
    static void toggleNight(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (nightOn(ctx)) {
            Backlight.set(ctx, p.getInt("nightPrevBacklight", 50));
            TvSettings.put(ctx, TvSettings.BLUE_LIGHT, p.getInt("nightPrevBlue", 0));
            SleepTimer.set(ctx, 0);
            p.edit().putBoolean("night", false).apply();
            return;
        }
        int backlight = Backlight.get(ctx);
        p.edit()
                .putBoolean("night", true)
                .putInt("nightPrevBacklight", backlight)
                .putInt("nightPrevBlue", TvSettings.get(ctx, TvSettings.BLUE_LIGHT, 0))
                .apply();
        Backlight.set(ctx, NIGHT_BACKLIGHT);
        TvSettings.put(ctx, TvSettings.BLUE_LIGHT, 1);
        SleepTimer.set(ctx, NIGHT_TIMER);
    }

    // --- сцены ---

    static final String SCENE_CINEMA = "cinema";
    static final String SCENE_GAME = "game";
    static final String SCENE_MY = "my";

    // картинка, звук, уровень подсветки (ниже нуля затемнение), синий свет
    static void applyScene(Context ctx, String scene) {
        switch (scene) {
            case SCENE_CINEMA:
                applyScene(ctx, 9, 1, 60, 0);
                break;
            case SCENE_GAME:
                applyScene(ctx, 7, 3, 80, 0);
                break;
            default:
                SharedPreferences p = prefs(ctx);
                applyScene(ctx, p.getInt("myPicture", 7), p.getInt("mySound", 0),
                        p.getInt("myLevel", 50), p.getInt("myBlue", 0));
                break;
        }
        prefs(ctx).edit().putBoolean("night", false).apply();
    }

    private static void applyScene(Context ctx, int picture, int sound, int level, int blue) {
        TvSettings.put(ctx, TvSettings.PICTURE_MODE, picture);
        TvSettings.put(ctx, TvSettings.SOUND_STYLE, sound);
        TvSettings.put(ctx, TvSettings.BLUE_LIGHT, blue);
        Backlight.set(ctx, level);
    }

    static boolean mySceneSaved(Context ctx) {
        return prefs(ctx).contains("myLevel");
    }

    static void saveMyScene(Context ctx) {
        prefs(ctx).edit()
                .putInt("myPicture", TvSettings.get(ctx, TvSettings.PICTURE_MODE, 7))
                .putInt("mySound", TvSettings.get(ctx, TvSettings.SOUND_STYLE, 0))
                .putInt("myLevel", Backlight.get(ctx))
                .putInt("myBlue", TvSettings.get(ctx, TvSettings.BLUE_LIGHT, 0))
                .apply();
    }

    static boolean tileVisible(Context ctx, String id) {
        return !prefs(ctx).getBoolean("hide_" + id, false);
    }

    static void setTileVisible(Context ctx, String id, boolean visible) {
        prefs(ctx).edit().putBoolean("hide_" + id, !visible).apply();
    }

    // открываем первый экран, который есть на этой прошивке
    static boolean open(Context ctx, Intent... intents) {
        for (Intent intent : intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(ctx.getPackageManager()) == null) {
                continue;
            }
            try {
                ctx.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // пробуем следующий
            }
        }
        return false;
    }

    static Intent action(String action) {
        return new Intent(action);
    }

    static Intent component(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }
}
