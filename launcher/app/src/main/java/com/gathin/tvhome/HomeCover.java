package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.Random;

/**
 * Covers Amazon's home screen while the launcher is held back. After Home, Android delays activity starts from
 * non-system apps for 5 s, so Amazon's home would show that long; the Home redirect puts one of the background
 * photos over it instead (an accessibility overlay is a window, not an activity, so it is not delayed), and
 * fades it out once the launcher is really in front.
 *
 * The next photo is picked at random (never the same twice in a row) and decoded ahead, so the cover is up within
 * a frame or two of Amazon's screen. It takes the remote's keys, so nothing reaches Amazon's screen underneath.
 */
final class HomeCover {

    private static final String TAG = "HomeCover";
    private static final int[] PHOTOS = {
            R.drawable.bg_morning, R.drawable.bg_afternoon, R.drawable.bg_evening, R.drawable.bg_night};
    private static final long MAX_SHOW_MS = 7000;   // Android's hold-back is 5 s; never leave the cover up for good
    private static final long FADE_MS = 300;

    private static final Random random = new Random();
    private static int lastPhoto = -1;
    private static View shown;
    private static WindowManager shownWm;
    /** The cover built with the next photo, waiting to be shown. */
    private static View ready;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable timeout = () -> hide(false);

    private HomeCover() {
    }

    /** Builds the next cover ahead of time: decoding a photo when Home is pressed would leave Amazon's up longer. */
    static void prepare(Context ctx) {
        if (ready != null) return;
        int pick;
        do {
            pick = random.nextInt(PHOTOS.length);
        } while (pick == lastPhoto && PHOTOS.length > 1);
        lastPhoto = pick;
        try {
            BitmapFactory.Options half = new BitmapFactory.Options();
            half.inSampleSize = 2;   // 960x540 from 1920x1080: 2 MB instead of 8, held while the launcher idles
            Bitmap photo = BitmapFactory.decodeResource(ctx.getResources(), PHOTOS[pick], half);
            ImageView cover = new ImageView(ctx.getApplicationContext());
            cover.setBackgroundColor(Color.BLACK);
            cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            cover.setImageBitmap(photo);
            // Swallow the remote while it is up, so presses do not land on Amazon's screen underneath.
            cover.setFocusable(true);
            cover.setFocusableInTouchMode(true);
            cover.setOnKeyListener((v, keyCode, event) -> true);
            ready = cover;
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "no cover photo", e);
        }
    }

    /** Puts the cover up over whatever is on screen (Amazon's home). */
    static void show(AccessibilityService service) {
        if (shown != null) return;
        if (ready == null) prepare(service);
        if (ready == null) return;
        View cover = ready;
        ready = null;
        cover.setAlpha(1f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSLUCENT);   // translucent so the fade-out shows the launcher through it
        lp.windowAnimations = 0;
        try {
            shownWm = service.getSystemService(WindowManager.class);
            shownWm.addView(cover, lp);
            cover.requestFocus();
            shown = cover;
            handler.postDelayed(timeout, MAX_SHOW_MS);
            Log.i(TAG, "cover up");
        } catch (RuntimeException e) {
            Log.w(TAG, "cannot show the cover", e);
        }
        prepare(service);   // the next one, for the next Home
    }

    /** Takes the cover down once the launcher has drawn its first frame back in front. */
    static void hideAfterNextDraw(View root) {
        if (shown == null) return;
        root.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                // Listeners cannot be removed from inside onDraw; give the frame time to reach the screen.
                root.post(() -> root.getViewTreeObserver().removeOnDrawListener(this));
                handler.postDelayed(() -> hide(true), 250);
            }
        });
        root.invalidate();
    }

    /** Takes the cover down, fading it out over the launcher, or at once when the launcher never came. */
    static void hide(boolean fade) {
        handler.removeCallbacks(timeout);
        if (shown == null) return;
        View v = shown;
        WindowManager wm = shownWm;
        shown = null;
        Runnable remove = () -> {
            try {
                wm.removeView(v);
                Log.i(TAG, "cover down");
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot remove the cover", e);
            }
        };
        if (fade) v.animate().alpha(0f).setDuration(FADE_MS).withEndAction(remove).start();
        else remove.run();
    }
}
