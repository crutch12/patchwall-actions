package com.criit.patchwallactions;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// переключение источника: главный экран и HDMI
final class Inputs {

    static final String TV_APP = "com.mediatek.wwtv.tvcenter";
    static final String HOME = "home";

    private Inputs() {
    }

    static List<TvInputInfo> hdmi(Context ctx) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> result = new ArrayList<>();
        if (tim == null) {
            return result;
        }
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) {
                result.add(info);
            }
        }
        Collections.sort(result, (a, b) -> a.getId().compareTo(b.getId()));
        return result;
    }

    static boolean connected(Context ctx, String id) {
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        return tim != null && tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED;
    }

    // текущий источник: HDMI, если сейчас на экране Live TV, иначе главный экран
    static String current(Context ctx) {
        return TV_APP.equals(MiKeyService.foregroundApp) ? prefs(ctx).getString("lastInput", HOME) : HOME;
    }

    // главный > HDMI1 > HDMI2 > ... > главный, только подключённые входы
    static void cycle(Context ctx) {
        List<String> ids = new ArrayList<>();
        for (TvInputInfo info : hdmi(ctx)) {
            if (connected(ctx, info.getId())) {
                ids.add(info.getId());
            }
        }
        String current = current(ctx);
        int next = HOME.equals(current) ? 0 : ids.indexOf(current) + 1;
        switchTo(ctx, next < ids.size() ? ids.get(next) : HOME);
    }

    static void switchTo(Context ctx, String id) {
        prefs(ctx).edit().putString("lastInput", id).apply();
        Intent intent = HOME.equals(id)
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                : new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("menu", Context.MODE_PRIVATE);
    }
}
