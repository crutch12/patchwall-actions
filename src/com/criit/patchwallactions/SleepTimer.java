package com.criit.patchwallactions;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

// своего таймера сна, который можно запустить извне, у прошивки нет, поэтому считаем сами
public class SleepTimer extends BroadcastReceiver {

    static final int[] STEPS = {0, 30, 60, 90};

    static long endsAt(Context ctx) {
        long end = prefs(ctx).getLong("endsAt", 0);
        return end > System.currentTimeMillis() ? end : 0;
    }

    static int minutesLeft(Context ctx) {
        long end = endsAt(ctx);
        return end == 0 ? 0 : (int) Math.ceil((end - System.currentTimeMillis()) / 60000.0);
    }

    // выкл > 30 > 60 > 90 > выкл
    static void cycle(Context ctx) {
        int left = minutesLeft(ctx);
        int next = 0;
        for (int step : STEPS) {
            if (step > left) {
                next = step;
                break;
            }
        }
        set(ctx, next);
    }

    static void add(Context ctx, int minutes) {
        set(ctx, Math.min(minutesLeft(ctx) + minutes, 240));
    }

    static void set(Context ctx, int minutes) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, SleepTimer.class), 0);
        am.cancel(pi);
        if (minutes <= 0) {
            prefs(ctx).edit().remove("endsAt").apply();
            return;
        }
        long end = System.currentTimeMillis() + minutes * 60000L;
        prefs(ctx).edit().putLong("endsAt", end).apply();
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        prefs(ctx).edit().remove("endsAt").apply();
        // уснуть может только служба; если её нет, хотя бы гасим экран
        if (!MiKeyService.sleepNow()) {
            Backlight.set(ctx, 0);
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("timer", Context.MODE_PRIVATE);
    }
}
