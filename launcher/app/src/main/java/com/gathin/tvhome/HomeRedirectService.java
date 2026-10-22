package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Makes this launcher the home screen on Fire OS, which ignores the default-home setting.
 *
 * Fire OS never passes the Home key to accessibility services, so this watches windows instead: when Amazon's
 * home screen comes up, this launcher opens over it straight away. One exception: Amazon's home screen also
 * flashes up on the way into Settings, right after a window from Amazon's settings app, so a home screen that
 * follows a settings window within SETTINGS_GRACE_MS is left alone. Also opens the launcher once at boot, and
 * when the TV is turned on over Amazon's home screen (see screenOn).
 *
 * Turned on and off from the launcher (Menu on the Settings button) when the app holds WRITE_SECURE_SETTINGS
 * (granted over adb: pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS), or over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/com.gathin.tvhome.HomeRedirectService
 *   settings put secure accessibility_enabled 1
 */
public class HomeRedirectService extends AccessibilityService {

    private static final String TAG = "HomeRedirect";
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "HomeActivity";
    private static final String[] SETTINGS_APPS = {"com.amazon.tv.settings", "com.amazon.tv.quicksettings"};
    private static final long SETTINGS_GRACE_MS = 2000;     // measured: settings window -> home flash ~200 ms
    private static final long BOOT_WINDOW_MS = 3 * 60_000;  // service connecting this soon after boot = boot

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable redirect = this::openLauncher;
    private long settingsSeenAt = -SETTINGS_GRACE_MS;

    // Turning the TV off brings Amazon's home screen to the front while the screen is dark, and no window event
    // reaches this service then, so on turning it back on Amazon's home would stay. The usage stats (granted
    // over adb: appops set com.gathin.tvhome GET_USAGE_STATS allow) say what is in front; if it is Amazon's
    // home, the launcher opens. Anything else (an HDMI input, an app) is left alone. Checked a few seconds after
    // the screen goes off, so the launcher is already in front when the TV comes back on, and again on screen on
    // in case that missed (SCREEN_ON reaches this app up to ~2.5 s late, after Fire OS's own receivers).
    private static final long SCREEN_OFF_CHECK_MS = 4000;
    private final Runnable homeCheck = () -> {
        if (amazonHomeInFront()) {
            Log.i(TAG, "home screen in front with the screen on/off: opening launcher");
            openLauncher();
        }
    };
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(homeCheck);
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                SleepTimer.cancel(context);   // turned off by hand: the timer is done with
                handler.postDelayed(homeCheck, SCREEN_OFF_CHECK_MS);
            } else {
                homeCheck.run();
            }
        }
    };

    /** The running service, for the sleep timer; null when it is off. */
    private static HomeRedirectService running;

    /** Puts the TV to sleep (standby), as the remote's power button does. False when the service isn't running. */
    static boolean sleepNow() {
        return running != null && running.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    // ---- restart ---------------------------------------------------------------------------------

    // Only Amazon's Settings may restart the TV (android.permission.REBOOT), and its list only reacts to the
    // remote's OK key, not to an accessibility click. So the launcher opens its "Device & Software" screen with
    // the highlight already on Restart; OK there brings Amazon's own "Restart / Cancel" question, where this moves
    // the highlight from Cancel to Restart. Two presses of OK, and nothing is pressed for the user. Both are found
    // by their exact label, never by position: Factory Reset is the next item down. If a label doesn't match
    // (another language, a Fire OS update), the highlight stays where Amazon put it.
    private static final String AMAZON_SETTINGS = "com.amazon.tv.settings.v2";
    private static final String DEVICE_SETTINGS = "com.amazon.device.settings.action.DEVICE";
    private static final String[] RESTART_LABELS = {"Reiniciar", "Restart"};
    private static final long RESTART_TRY_MS = 400;
    private static final long FIND_RESTART_MS = 10_000;     // for the screen to open and the item to turn up
    private static final long WAIT_FOR_OK_MS = 60_000;      // for the user to press OK on it
    private long restartUntil;
    private boolean restartFocused;
    private final Runnable restartStep = this::restartStep;

    /** Opens Amazon's restart option, highlighted. False when the service isn't running. */
    static boolean restartTv(Context ctx) {
        if (running == null) return false;
        running.restartUntil = SystemClock.uptimeMillis() + FIND_RESTART_MS;
        running.restartFocused = false;
        ctx.startActivity(new Intent(DEVICE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        running.handler.removeCallbacks(running.restartStep);
        running.handler.postDelayed(running.restartStep, RESTART_TRY_MS);
        return true;
    }

    private void restartStep() {
        if (SystemClock.uptimeMillis() > restartUntil) {
            Log.w(TAG, "restart: gave up, " + (restartFocused ? "no Restart dialog" : "Restart not found"));
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        boolean inSettings = root != null && AMAZON_SETTINGS.contentEquals(String.valueOf(root.getPackageName()));
        if (!inSettings) {
            if (restartFocused) {
                Log.i(TAG, "restart: left Amazon's Settings");   // Back, or Home
                return;
            }
        } else if (!restartFocused) {
            restartFocused = focusRestartItem(root);
            if (restartFocused) restartUntil = SystemClock.uptimeMillis() + WAIT_FOR_OK_MS;
        } else {
            // Amazon's question: its title is the same label; button1 is the positive button.
            AccessibilityNodeInfo title = first(root, "android:id/alertTitle");
            if (title != null) {
                AccessibilityNodeInfo ok = first(root, "android:id/button1");
                if (isRestartLabel(title.getText()) && ok != null && isRestartLabel(ok.getText())) {
                    Log.i(TAG, "restart: highlighting Restart in the dialog");
                    ok.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                }
                return;
            }
        }
        handler.postDelayed(restartStep, RESTART_TRY_MS);
    }

    /** Highlights Restart in the list, scrolling down a page while it isn't there yet. */
    private static boolean focusRestartItem(AccessibilityNodeInfo root) {
        for (AccessibilityNodeInfo item : root.findAccessibilityNodeInfosByViewId(AMAZON_SETTINGS + ":id/action_title")) {
            if (!isRestartLabel(item.getText())) continue;
            AccessibilityNodeInfo row = item;
            while (row != null && !row.isClickable()) row = row.getParent();
            if (row != null && row.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                Log.i(TAG, "restart: highlighted Restart");
                return true;
            }
            return false;
        }
        AccessibilityNodeInfo list = first(root, AMAZON_SETTINGS + ":id/list");
        if (list != null) list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        return false;
    }

    private static AccessibilityNodeInfo first(AccessibilityNodeInfo root, String viewId) {
        java.util.List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(viewId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean isRestartLabel(CharSequence text) {
        if (text == null) return false;
        for (String label : RESTART_LABELS) if (label.contentEquals(text.toString().trim())) return true;
        return false;
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOn, screen);
        HomeCover.prepare(this);
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openLauncher();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
            // Never registered: the service was not connected.
        }
        super.onDestroy();
    }

    /** True when the last app to come to the front is Amazon's home screen; false without usage access. */
    private boolean amazonHomeInFront() {
        UsageStatsManager usm = getSystemService(UsageStatsManager.class);
        if (usm == null) return false;
        long now = System.currentTimeMillis();
        UsageEvents events = usm.queryEvents(now - 60 * 60_000, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null, cls = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                pkg = e.getPackageName();
                cls = e.getClassName();
            }
        }
        return AMAZON_LAUNCHER.equals(pkg) && cls != null && cls.contains(AMAZON_HOME);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName(), cls = event.getClassName();
        if (pkg == null || cls == null) return;
        long now = SystemClock.uptimeMillis();
        for (String app : SETTINGS_APPS) if (pkg.toString().startsWith(app)) settingsSeenAt = now;
        handler.removeCallbacks(redirect);
        if (!AMAZON_LAUNCHER.contentEquals(pkg) || !cls.toString().contains(AMAZON_HOME)) return;
        if (now - settingsSeenAt < SETTINGS_GRACE_MS) {
            Log.i(TAG, "home screen on the way into Settings: left alone");
            return;
        }
        Log.i(TAG, "home screen: opening launcher");
        HomeCover.show(this);
        handler.post(redirect);
    }

    @Override
    public void onInterrupt() {
    }

    private void openLauncher() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    // ---- on/off from the launcher's UI -----------------------------------------------------------

    static boolean isEnabled(Context ctx) {
        String on = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(component(ctx).flattenToString());
    }

    /** False when the app lacks WRITE_SECURE_SETTINGS. */
    static boolean setEnabled(Context ctx, boolean on) {
        try {
            Settings.Secure.putString(ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    on ? component(ctx).flattenToString() : "");
            Settings.Secure.putInt(ctx.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, on ? 1 : 0);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static ComponentName component(Context ctx) {
        return new ComponentName(ctx, HomeRedirectService.class);
    }
}
