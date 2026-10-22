package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The card a minute before the sleep timer turns the TV off, over whatever is playing: OK on its button gives
 * EXTEND_MINUTES more, Back closes it and the timer runs on. Shown as an accessibility overlay of
 * HomeRedirectService, which needs no extra permission and leaves the app underneath running (an activity would
 * pause the video). Gone by itself when the timer ends or is called off.
 */
final class SleepWarning {

    static final int EXTEND_MINUTES = 15;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static WindowManager wm;
    private static View card;

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (card == null) return;
            long left = SleepTimer.remainingMs(card.getContext());
            if (left <= 0) {
                hide();
                return;
            }
            long s = (left + 999) / 1000;
            ((TextView) card.findViewById(R.id.warningTitle)).setText(card.getContext()
                    .getString(R.string.sleep_warning_title, s / 60 + ":" + String.format("%02d", s % 60)));
            handler.postDelayed(this, left % 1000 + 20);   // when the seconds shown change
        }
    };

    private SleepWarning() {
    }

    /** False when it can't be shown; the caller falls back to a toast. */
    static boolean show(AccessibilityService service) {
        hide();
        Context ctx = new ContextThemeWrapper(service, R.style.Theme_TvHome);
        View v = LayoutInflater.from(ctx).inflate(R.layout.sleep_warning, null);
        // Same weights as res/layout/sleep_warning.xml, which Android 9 ignores.
        MainActivity.setWeight(v.findViewById(R.id.warningTitle), 650);
        MainActivity.setWeight(v.findViewById(R.id.warningHint), 450);
        MainActivity.setWeight(v.findViewById(R.id.warningExtend), 600);
        View extend = v.findViewById(R.id.warningExtend);
        extend.setOnClickListener(b -> {
            SleepTimer.set(service, EXTEND_MINUTES);
            hide();
            Toast.makeText(service, service.getString(R.string.sleep_set, SleepTimer.endTimeText(service)),
                    Toast.LENGTH_LONG).show();
        });
        // Back closes the card; every other key stays with it, so nothing reaches the app underneath by mistake.
        View.OnKeyListener back = (view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) hide();
            return true;
        };
        extend.setOnKeyListener(back);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (56 * service.getResources().getDisplayMetrics().density);
        wm = service.getSystemService(WindowManager.class);
        try {
            wm.addView(v, lp);
        } catch (RuntimeException e) {
            return false;
        }
        card = v;
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(300).start();
        extend.requestFocus();
        tick.run();
        return true;
    }

    static void hide() {
        handler.removeCallbacks(tick);
        if (card == null) return;
        View v = card;
        card = null;
        try {
            wm.removeView(v);
        } catch (RuntimeException ignored) {
            // Already gone with the service.
        }
    }
}
