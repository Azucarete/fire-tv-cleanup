package com.gathin.tvhome;

import android.content.res.TypedArray;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Screensaver: the screensaver_photos full screen, each slowly zooming in, crossfading every SLIDE_MS, with a
 * small clock in the corner and, while music plays, what is playing (NowPlaying) in the other. Chosen with:
 *   settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.PhotoDream
 * and Amazon's back with:
 *   settings put secure screensaver_components com.amazon.ftv.screensaver/.app.services.ScreensaverService
 */
public class PhotoDream extends DreamService {

    private static final long SLIDE_MS = 20_000;
    private static final long FADE_MS = 2_500;
    private static final float ZOOM = 1.08f;   // reached over one slide, then the next photo takes over
    private static final float NOW_PLAYING_SCALE = 1.5f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Integer> order = new ArrayList<>();
    private ImageView front, back;
    private TextClock clock;
    private View nowPlayingCard;
    private NowPlaying nowPlaying;
    private int next;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            showNext();
            handler.postDelayed(this, SLIDE_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        front = photoView();
        back = photoView();
        root.addView(back);
        root.addView(front);
        clock = clock();
        root.addView(clock);
        // What Spotify (or any app) is playing, bottom left across from the clock; hidden while nothing plays.
        nowPlayingCard = LayoutInflater.from(this).inflate(R.layout.now_playing, root, false);
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        np.setMargins((int) (48 * dp), 0, 0, (int) (36 * dp));
        root.addView(nowPlayingCard, np);
        NowPlaying.style(nowPlayingCard);
        // Seen from across the room here, so larger than on the home screen; grows from its bottom-left corner.
        nowPlayingCard.setScaleX(NOW_PLAYING_SCALE);
        nowPlayingCard.setScaleY(NOW_PLAYING_SCALE);
        nowPlayingCard.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            v.setPivotX(0f);
            v.setPivotY(b - t);
        });
        nowPlaying = new NowPlaying(this, playing -> NowPlaying.show(nowPlayingCard, playing));
        setContentView(root);

        // The photos come from res/values/config.xml (screensaver_photos).
        TypedArray photos = getResources().obtainTypedArray(R.array.screensaver_photos);
        for (int i = 0; i < photos.length(); i++) order.add(photos.getResourceId(i, 0));
        photos.recycle();
        Collections.shuffle(order);
        front.setAlpha(0f);
        back.setAlpha(0f);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.post(advance);
        nowPlaying.start();
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(advance);
        nowPlaying.stop();
        super.onDreamingStopped();
    }

    /** Fade the next photo in over the current one, and start its slow zoom. */
    private void showNext() {
        ImageView incoming = back, outgoing = front;
        incoming.setImageResource(order.get(next));
        next = (next + 1) % order.size();
        incoming.animate().cancel();
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);
        incoming.setAlpha(0f);
        incoming.bringToFront();
        incoming.animate().alpha(1f).setDuration(FADE_MS).start();
        incoming.animate().scaleX(ZOOM).scaleY(ZOOM).setDuration(SLIDE_MS + FADE_MS).start();
        outgoing.animate().alpha(0f).setStartDelay(FADE_MS).setDuration(1).start();
        front = incoming;
        back = outgoing;
        clock.bringToFront();
        nowPlayingCard.bringToFront();
    }

    private ImageView photoView() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }

    private TextClock clock() {
        TextClock c = new TextClock(this);
        c.setFormat12Hour("h:mm");
        c.setFormat24Hour("HH:mm");
        c.setTextColor(0xE6FFFFFF);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42);
        c.setTypeface(getResources().getFont(R.font.open_sans));
        c.setFontVariationSettings("'wght' 300");
        c.setShadowLayer(12f, 0f, 2f, 0x99000000);
        float dp = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, (int) (48 * dp), (int) (36 * dp));
        c.setLayoutParams(lp);
        return c;
    }
}
