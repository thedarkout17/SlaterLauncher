package com.redline.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import java.util.List;

/** Tracks whatever app is currently playing music and exposes its song, artwork and controls. */
public class MediaHelper {

    public interface Listener { void onMediaChanged(); }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private boolean started;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { notifyChanged(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { notifyChanged(); }
        @Override public void onSessionDestroyed() { pickSession(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> controllers) {
                    pickSession();
                }
            };

    public MediaHelper(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public boolean hasAccess() {
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(ctx.getPackageName());
    }

    public void openAccessSettings() {
        Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!Slots.start(ctx, i)) {
            Intent s = new Intent(Settings.ACTION_SETTINGS);
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Slots.start(ctx, s);
        }
    }

    public void start() {
        if (started || !hasAccess()) { notifyChanged(); return; }
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(), main);
            started = true;
            pickSession();
        } catch (SecurityException e) {
            started = false;
        }
    }

    public void stop() {
        if (msm != null && started) {
            try { msm.removeOnActiveSessionsChangedListener(sessionsListener); } catch (Exception ignored) { }
        }
        started = false;
        setController(null);
    }

    private ComponentName listenerComponent() {
        return new ComponentName(ctx, MediaListener.class);
    }

    private void pickSession() {
        if (msm == null) return;
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(listenerComponent());
        } catch (SecurityException e) {
            setController(null);
            return;
        }
        MediaController best = null;
        for (MediaController mc : list) {
            PlaybackState st = mc.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = mc; break; }
            if (best == null) best = mc;
        }
        setController(best);
    }

    private void setController(MediaController mc) {
        if (controller != null) {
            try { controller.unregisterCallback(cb); } catch (Exception ignored) { }
        }
        controller = mc;
        if (controller != null) controller.registerCallback(cb, main);
        notifyChanged();
    }

    private void notifyChanged() {
        if (listener != null) listener.onMediaChanged();
    }

    // ------------------------------------------------------------------ state

    public boolean hasSession() { return controller != null; }

    public String title() {
        if (controller == null) return null;
        MediaMetadata md = controller.getMetadata();
        if (md == null) return null;
        String t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (t == null || t.length() == 0) t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        return t;
    }

    public String artist() {
        if (controller == null) return null;
        MediaMetadata md = controller.getMetadata();
        if (md == null) return null;
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null || a.length() == 0) a = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        return a;
    }

    public Bitmap art() {
        if (controller == null) return null;
        MediaMetadata md = controller.getMetadata();
        if (md == null) return null;
        Bitmap b = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        return b;
    }

    public boolean isPlaying() {
        if (controller == null) return false;
        PlaybackState st = controller.getPlaybackState();
        return st != null && (st.getState() == PlaybackState.STATE_PLAYING
                || st.getState() == PlaybackState.STATE_BUFFERING);
    }

    public long duration() {
        if (controller == null || controller.getMetadata() == null) return 0;
        return controller.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
    }

    public long position() {
        if (controller == null) return 0;
        PlaybackState st = controller.getPlaybackState();
        if (st == null) return 0;
        long pos = st.getPosition();
        if (st.getState() == PlaybackState.STATE_PLAYING) {
            long dt = SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime();
            pos += (long) (dt * st.getPlaybackSpeed());
        }
        long d = duration();
        if (d > 0 && pos > d) pos = d;
        return Math.max(0, pos);
    }

    // ------------------------------------------------------------------ controls

    public void playPause() {
        if (controller != null) {
            if (isPlaying()) controller.getTransportControls().pause();
            else controller.getTransportControls().play();
        } else {
            sendKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    public void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
        else sendKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
        else sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    public void seekTo(long ms) {
        if (controller != null) controller.getTransportControls().seekTo(ms);
    }

    /** Works without notification access: broadcasts a media button press to the active player. */
    private void sendKey(int code) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    /** Bring the playing app to the front. Returns false if there is nothing to open. */
    public boolean openPlayer() {
        if (controller == null) return false;
        PendingIntent pi = controller.getSessionActivity();
        if (pi != null) {
            try { pi.send(); return true; } catch (PendingIntent.CanceledException ignored) { }
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(controller.getPackageName());
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return Slots.start(ctx, i);
        }
        return false;
    }
}
