package com.criit.patchwallactions;

import android.content.ComponentName;
import android.content.Context;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

import java.util.List;

// что сейчас играет и управление им
final class Media {

    private Media() {
    }

    // активная сессия; без доступа к уведомлениям вернёт null
    static MediaController session(Context ctx) {
        MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            List<MediaController> sessions = msm.getActiveSessions(new ComponentName(ctx, MediaListener.class));
            for (MediaController c : sessions) {
                PlaybackState state = c.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    return c;
                }
            }
            return sessions.isEmpty() ? null : sessions.get(0);
        } catch (SecurityException e) {
            return null;
        }
    }

    // название трека, а если плеер его не отдаёт, то название приложения
    static String title(Context ctx, MediaController c) {
        MediaMetadata meta = c.getMetadata();
        String title = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        return title == null || title.isEmpty() ? Apps.label(ctx, c.getPackageName()) : title;
    }

    static boolean playing(MediaController c) {
        PlaybackState state = c == null ? null : c.getPlaybackState();
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    // кнопки медиа работают и без доступа к сессиям: система отдаёт их активному плееру
    static void key(Context ctx, int keyCode) {
        AudioManager audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }
}
