package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * Turns the TV off after a chosen time, set from the moon button on the home screen. Keeps running over other
 * apps: an alarm shows a warning a minute before, then puts the TV to sleep through HomeRedirectService (an
 * accessibility service may lock the screen, which on Android 9 is sleep; nothing else lets an app turn the TV
 * off). Turning the TV off by hand, or restarting it, cancels the timer.
 */
public class SleepTimer extends BroadcastReceiver {

    private static final String TAG = "SleepTimer";
    private static final String ACTION_WARN = "com.gathin.tvhome.SLEEP_WARN";
    private static final String ACTION_SLEEP = "com.gathin.tvhome.SLEEP";
    private static final long WARN_MS = 60_000L;
    private static final String PREF_AT = "sleep_at";          // elapsedRealtime when the TV turns off
    private static final String PREF_BOOT = "sleep_boot";      // boot count it was set in

    /** Turns the TV off in this many minutes, replacing any timer already set. */
    static void set(Context ctx, int minutes) {
        long at = SystemClock.elapsedRealtime() + minutes * 60_000L;
        prefs(ctx).edit().putLong(PREF_AT, at).putInt(PREF_BOOT, bootCount(ctx)).apply();
        schedule(ctx, at);
    }

    static void cancel(Context ctx) {
        if (!prefs(ctx).contains(PREF_AT)) return;
        prefs(ctx).edit().remove(PREF_AT).remove(PREF_BOOT).apply();
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        am.cancel(pending(ctx, ACTION_WARN));
        am.cancel(pending(ctx, ACTION_SLEEP));
    }

    /** Milliseconds until the TV turns off, or -1 when no timer is set. */
    static long remainingMs(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!p.contains(PREF_AT)) return -1;
        // Alarms do not survive a restart, and elapsedRealtime starts again from 0.
        if (p.getInt(PREF_BOOT, -1) != bootCount(ctx)) {
            cancel(ctx);
            return -1;
        }
        return Math.max(0, p.getLong(PREF_AT, 0) - SystemClock.elapsedRealtime());
    }

    private static void schedule(Context ctx, long at) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        long now = SystemClock.elapsedRealtime();
        if (at - WARN_MS > now) {
            am.setExact(AlarmManager.ELAPSED_REALTIME, at - WARN_MS, pending(ctx, ACTION_WARN));
        } else {
            am.cancel(pending(ctx, ACTION_WARN));
        }
        am.setExact(AlarmManager.ELAPSED_REALTIME, at, pending(ctx, ACTION_SLEEP));
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (remainingMs(ctx) < 0) return;
        // Already off (standby, or off by hand and the cancel missed): nothing to do.
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            cancel(ctx);
            return;
        }
        if (ACTION_WARN.equals(intent.getAction())) {
            Toast.makeText(ctx, R.string.sleep_warning, Toast.LENGTH_LONG).show();
            return;
        }
        cancel(ctx);
        if (!HomeRedirectService.sleepNow()) {
            Log.w(TAG, "Home redirect service not running: can't turn the TV off");
            Toast.makeText(ctx, R.string.sleep_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static PendingIntent pending(Context ctx, String action) {
        Intent i = new Intent(action).setClass(ctx, SleepTimer.class);
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static int bootCount(Context ctx) {
        return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("tvhome", Context.MODE_PRIVATE);
    }
}
