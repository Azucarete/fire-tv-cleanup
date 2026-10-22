package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows what is playing in other apps (Spotify, mostly) through their media sessions, for the "Now playing" card
 * on the home screen and the screensaver (res/layout/now_playing.xml). Reports a song only while it plays: paused or
 * stopped hides the card. Needs NowPlayingListener enabled; without it, it reports nothing.
 */
final class NowPlaying {

    interface Listener {
        /** The song playing now, or null when nothing is. */
        void onNowPlaying(MediaMetadata playing);
    }

    private final Listener listener;
    private final ComponentName access;
    private final MediaSessionManager sessions;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<MediaController> controllers = new ArrayList<>();
    private boolean started;

    private final MediaController.Callback changes = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { report(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { report(); }
        @Override public void onSessionDestroyed() { report(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::follow;

    NowPlaying(Context ctx, Listener listener) {
        this.listener = listener;
        access = new ComponentName(ctx, NowPlayingListener.class);
        sessions = ctx.getSystemService(MediaSessionManager.class);
    }

    void start() {
        if (started || sessions == null) return;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, access, handler);
            started = true;
            follow(sessions.getActiveSessions(access));
        } catch (SecurityException e) {
            listener.onNowPlaying(null);   // no notification access: the card just never shows
        }
    }

    void stop() {
        if (!started) return;
        started = false;
        sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        follow(null);
    }

    private void follow(List<MediaController> active) {
        for (MediaController c : controllers) c.unregisterCallback(changes);
        controllers.clear();
        if (active != null) {
            for (MediaController c : active) {
                c.registerCallback(changes, handler);
                controllers.add(c);
            }
        }
        report();
    }

    private void report() {
        MediaMetadata playing = null;
        for (MediaController c : controllers) {
            PlaybackState state = c.getPlaybackState();
            MediaMetadata m = c.getMetadata();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING
                    && m != null && m.getDescription().getTitle() != null) {
                playing = m;
                break;
            }
        }
        listener.onNowPlaying(playing);
    }

    // ---- the card ---------------------------------------------------------------------------------

    /** Fills a res/layout/now_playing.xml card, and fades it in or out; null hides it. */
    static void show(View card, MediaMetadata m) {
        if (m == null) {
            if (card.getVisibility() == View.VISIBLE) {
                card.animate().alpha(0f).setDuration(300).withEndAction(() -> card.setVisibility(View.GONE)).start();
            }
            return;
        }
        ((TextView) card.findViewById(R.id.npTitle)).setText(m.getDescription().getTitle());
        CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (artist == null) artist = m.getDescription().getSubtitle();
        TextView artistView = card.findViewById(R.id.npArtist);
        artistView.setText(artist);
        artistView.setVisibility(artist == null ? View.GONE : View.VISIBLE);
        ImageView art = card.findViewById(R.id.npArt);
        Bitmap cover = cover(m);
        if (cover != null) {
            art.setImageBitmap(cover);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else {
            art.setImageResource(R.drawable.ic_music);
            art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
        if (card.getVisibility() != View.VISIBLE || card.getAlpha() < 1f) {
            card.setVisibility(View.VISIBLE);
            card.animate().alpha(1f).setDuration(400).start();   // cancels a fade-out and its GONE
        }
    }

    /** Same weights and rounded cover as the layout asks for, which Android 9 ignores or can't express. */
    static void style(View card) {
        MainActivity.setWeight(card.findViewById(R.id.npLabel), 600);
        MainActivity.setWeight(card.findViewById(R.id.npTitle), 650);
        MainActivity.setWeight(card.findViewById(R.id.npArtist), 450);
        float radius = 10 * card.getResources().getDisplayMetrics().density;
        View art = card.findViewById(R.id.npArt);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        art.setClipToOutline(true);
    }

    private static Bitmap cover(MediaMetadata m) {
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            Bitmap b = m.getBitmap(key);
            if (b != null) return b;
        }
        return m.getDescription().getIconBitmap();
    }
}
