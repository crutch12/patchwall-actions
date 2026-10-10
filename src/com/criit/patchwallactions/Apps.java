package com.criit.patchwallactions;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// недавние приложения и закрытие. Недавние запоминаем сами по сменам окон, без доступа к статистике
final class Apps {

    private static final int RECENT_MAX = 8;

    private Apps() {
    }

    // вызывается службой, когда на экране новое приложение
    static void onForeground(Context ctx, String pkg) {
        if (launchIntent(ctx, pkg) == null || isHome(ctx, pkg) || pkg.equals(ctx.getPackageName())
                || Inputs.TV_APP.equals(pkg)) {
            return;
        }
        List<String> list = recent(ctx);
        list.remove(pkg);
        list.add(0, pkg);
        while (list.size() > RECENT_MAX) {
            list.remove(list.size() - 1);
        }
        prefs(ctx).edit().putString("recent", TextUtils.join(",", list)).apply();
    }

    static List<String> recent(Context ctx) {
        String raw = prefs(ctx).getString("recent", "");
        List<String> list = new ArrayList<>(Arrays.asList(raw.isEmpty() ? new String[0] : raw.split(",")));
        // удалённые приложения выкидываем
        for (int i = list.size() - 1; i >= 0; i--) {
            if (launchIntent(ctx, list.get(i)) == null) {
                list.remove(i);
            }
        }
        return list;
    }

    static Intent launchIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static void open(Context ctx, String pkg) {
        Intent intent = launchIntent(ctx, pkg);
        if (intent != null) {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // закрыть можно только фоновое приложение, поэтому текущее сначала уводим на главный экран
    static void close(Context ctx, String pkg) {
        Context app = ctx.getApplicationContext();
        if (pkg.equals(MiKeyService.foregroundApp)) {
            app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            new Handler(Looper.getMainLooper()).postDelayed(() -> kill(app, pkg), 700);
        } else {
            kill(app, pkg);
        }
    }

    // закрывает все фоновые пользовательские приложения. Память освобождается не сразу,
    // поэтому сколько освободилось, считает вызывающий через availMb чуть позже
    static void clearMemory(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo info : ctx.getPackageManager().queryIntentActivities(main, 0)) {
            String pkg = info.activityInfo.packageName;
            if (!pkg.equals(ctx.getPackageName()) && !pkg.equals(MiKeyService.foregroundApp)
                    && (info.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                am.killBackgroundProcesses(pkg);
            }
        }
    }

    private static void kill(Context ctx, String pkg) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        am.killBackgroundProcesses(pkg);
    }

    static long availMb(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.availMem / (1024 * 1024);
    }

    private static boolean isHome(Context ctx, String pkg) {
        ResolveInfo home = ctx.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return home != null && pkg.equals(home.activityInfo.packageName);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("apps", Context.MODE_PRIVATE);
    }
}
