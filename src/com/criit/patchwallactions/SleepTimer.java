package com.criit.patchwallactions;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.view.KeyEvent;

// своего таймера сна, который можно запустить извне, у прошивки нет, поэтому считаем сами.
// За минуту до сна предупреждаем, экран плавно темнеет, кнопка Xiaomi откладывает на 15 минут
public class SleepTimer extends BroadcastReceiver {

    static final int STEP = 15;
    static final int MAX = 120;
    static final long WARNING_MS = 60000;

    private static final String ACTION_WARN = "warn";
    private static final String ACTION_SLEEP = "sleep";

    static long endsAt(Context ctx) {
        long end = prefs(ctx).getLong("endsAt", 0);
        return end > System.currentTimeMillis() ? end : 0;
    }

    static int minutesLeft(Context ctx) {
        long end = endsAt(ctx);
        return end == 0 ? 0 : (int) Math.ceil((end - System.currentTimeMillis()) / 60000.0);
    }

    // выкл > 15 > 30 > ... > 120 > выкл
    static void cycle(Context ctx) {
        int left = minutesLeft(ctx);
        int next = (left / STEP + 1) * STEP;
        set(ctx, next > MAX ? 0 : next);
    }

    static void add(Context ctx, int minutes) {
        set(ctx, Math.min(minutesLeft(ctx) + minutes, MAX));
    }

    static void set(Context ctx, int minutes) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(ctx, ACTION_WARN));
        am.cancel(pending(ctx, ACTION_SLEEP));
        MiKeyService.hideSleepWarning();
        if (minutes <= 0) {
            prefs(ctx).edit().remove("endsAt").apply();
            return;
        }
        long end = System.currentTimeMillis() + minutes * 60000L;
        prefs(ctx).edit().putLong("endsAt", end).apply();
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end - WARNING_MS, pending(ctx, ACTION_WARN));
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(ctx, ACTION_SLEEP));
    }

    // идёт ли сейчас последняя минута с предупреждением
    static boolean warning(Context ctx) {
        long end = endsAt(ctx);
        return end != 0 && end - System.currentTimeMillis() <= WARNING_MS;
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (ACTION_WARN.equals(intent.getAction())) {
            MiKeyService.showSleepWarning(WARNING_MS);
            return;
        }
        prefs(ctx).edit().remove("endsAt").apply();
        MiKeyService.hideSleepWarning();
        pauseMedia(ctx);
        // уснуть может только служба; если её нет, хотя бы гасим экран
        if (!MiKeyService.sleepNow()) {
            Backlight.set(ctx, 0);
        }
    }

    private static void pauseMedia(Context ctx) {
        AudioManager audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
    }

    private static PendingIntent pending(Context ctx, String action) {
        Intent intent = new Intent(ctx, SleepTimer.class).setAction(action);
        return PendingIntent.getBroadcast(ctx, 0, intent, 0);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("timer", Context.MODE_PRIVATE);
    }
}
