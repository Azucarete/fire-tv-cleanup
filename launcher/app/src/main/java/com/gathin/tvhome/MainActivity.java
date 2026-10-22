package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.view.animation.LayoutAnimationController;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * tvOS style home: a photo background, a pinned dock row and an app grid, focused like a macOS dock.
 */
public class MainActivity extends Activity implements AppAdapter.TileListener {

    /** Tiles per row and dock slots (res/values/config.xml, columns). Set in onCreate. */
    static int COLUMNS = 4;
    private static final long MINUTE_MS = 60_000L;
    private String liveTvPkg;

    private AppAdapter adapter;
    private RecyclerView grid;
    private FrameLayout root;
    private TextView heroName;
    private View dockDot;
    // macOS dock magnification: focused icon and its neighbours grow from their bottom edge and lift.
    private static final float DOCK_MAGNIFY = 1.30f;
    private static final float DOCK_NEIGHBOUR = 1.10f;
    private static final float DOCK_LIFT_DP = 10f;
    private static final float DOCK_NEIGHBOUR_LIFT_DP = 4f;
    // Amazon's Appstore, opened from the top bar. It has no TV launcher entry of its own (Amazon's home opens it
    // through its "Your apps" grid), so it gets a button instead of a tile.
    private static final ComponentName APPSTORE =
            new ComponentName("com.amazon.venezia", "com.amazon.venezia.ade.ADEHomeActivity");
    private static final String APPSTORE_ACTION = "com.amazon.venezia.ade.SHORTCUT_LAUNCH";
    // One easing for everything that moves along the dock, so the icons, label and dot travel together.
    private static final long DOCK_ANIM_MS = 260;
    private static final Interpolator DOCK_EASE = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private boolean heroShown;
    private TextView greeting;
    private ImageView bgPhoto;
    private int bgRes;
    private TextView btnWifi;
    private TextView btnSleep;
    private View offlineCard;
    private int lastFocused = 0;
    private float density;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int COLOR_WARN = 0xFFFFB340;   // connected, no internet
    private static final int COLOR_ERROR = 0xFFFF5A52;  // no network
    private static final int[] WIFI_ICONS = {R.drawable.ic_wifi_1, R.drawable.ic_wifi_2, R.drawable.ic_wifi};
    // Refresh the Wi-Fi pill as soon as the connection changes, not on the next minute tick.
    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(@NonNull Network n) { handler.post(MainActivity.this::updateWifiLabel); }
        @Override public void onLost(@NonNull Network n) { handler.post(MainActivity.this::updateWifiLabel); }
        @Override public void onCapabilitiesChanged(@NonNull Network n, @NonNull NetworkCapabilities c) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
        @Override public void onLinkPropertiesChanged(@NonNull Network n, @NonNull LinkProperties p) {
            handler.post(MainActivity.this::updateWifiLabel);
        }
    };
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            updateGreeting();
            updateBackground();
            updateWifiLabel();
            updateRestartHint();
            handler.postDelayed(this, MINUTE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;
        COLUMNS = Math.max(1, getResources().getInteger(R.integer.columns));
        liveTvPkg = getString(R.string.live_tv_package).trim();

        root = findViewById(R.id.root);
        heroName = findViewById(R.id.heroName);
        // Same weights as res/layout/activity_main.xml and styles.xml (TopButton), which Android 9 ignores.
        setWeight(heroName, 750);
        setWeight(findViewById(R.id.greeting), 500);
        setWeight(findViewById(R.id.clock), 300);
        setWeight(findViewById(R.id.ampm), 500);
        setWeight(findViewById(R.id.date), 400);
        setWeight(findViewById(R.id.btnWifi), 500);
        setWeight(findViewById(R.id.restartHint), 500);
        setWeight(findViewById(R.id.btnSleep), 500);
        setWeight(findViewById(R.id.offlineTitle), 650);
        setWeight(findViewById(R.id.offlineBody), 450);
        dockDot = findViewById(R.id.dockDot);
        greeting = findViewById(R.id.greeting);
        bgPhoto = findViewById(R.id.bgPhoto);
        btnWifi = findViewById(R.id.btnWifi);
        btnSleep = findViewById(R.id.btnSleep);
        offlineCard = findViewById(R.id.offlineCard);

        grid = findViewById(R.id.grid);
        // Each column is 108dp wide; centre COLUMNS of them (264dp each side for 4 on a 960dp-wide TV).
        int side = Math.max((int) (48 * density),
                (getResources().getDisplayMetrics().widthPixels - (int) (COLUMNS * 108 * density)) / 2);
        grid.setPaddingRelative(side, grid.getPaddingTop(), side, grid.getPaddingBottom());
        // One span unit per cell of any dock size 1..COLUMNS: the dock row splits evenly, grid rows are COLUMNS wide.
        final int spans = lcmUpTo(COLUMNS);
        GridLayoutManager glm = new GridLayoutManager(this, spans) {
            // Keep the rows hidden below the dock laid out, so Down focuses the first row at once and scrolls
            // smoothly like Up does, instead of scroll-searching for it and moving focus afterwards.
            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
                super.calculateExtraLayoutSpace(state, extra);
                extra[0] = Math.max(extra[0], getHeight());
                extra[1] = Math.max(extra[1], getHeight());
            }
        };
        glm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.isDocked(position) ? spans / adapter.dockCount() : spans / COLUMNS;
            }
        });
        grid.setLayoutManager(glm);
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);

        grid.setLayoutAnimation(new LayoutAnimationController(
                AnimationUtils.loadAnimation(this, R.anim.tile_enter), 0.06f));
        adapter = new AppAdapter(this, this, COLUMNS);
        grid.addItemDecoration(new DockDecoration(adapter, COLUMNS, density));
        grid.setAdapter(adapter);

        // tvOS layout: push the dock row to the bottom of the screen; the grid hides below until you press Down.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int h = b - t;
            int dockRow = (int) ((101 + 6 + 12) * density);   // item height plus DockDecoration offsets
            int want = Math.max(0, h - dockRow + (int) (8 * density));
            if (grid.getPaddingTop() != want) {
                grid.setPadding(grid.getPaddingLeft(), want, grid.getPaddingRight(), grid.getPaddingBottom());
            }
        });

        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            // The tooltip is placed where the scroll is expected to end; at the ends of the list the scroll can
            // stop short, so settle it on the tile's real position (a no-op when it already matches).
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE || !heroShown) return;
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(lastFocused);
                View card = vh != null ? vh.itemView.findViewById(R.id.card) : null;
                if (card == null || !card.hasFocus() && !card.isFocused()) return;
                magnifyRow(lastFocused);
                showHero(lastFocused, cardRect(card, 0));
            }
        });

        View btnStore = findViewById(R.id.btnStore);
        View btnInputs = findViewById(R.id.btnInputs);
        View btnSettings = findViewById(R.id.btnSettings);
        Intent store = new Intent(APPSTORE_ACTION).setComponent(APPSTORE);
        boolean hasStore = store.resolveActivity(getPackageManager()) != null;
        btnStore.setVisibility(hasStore ? View.VISIBLE : View.GONE);
        btnStore.setOnClickListener(v -> open(new Intent(store)));
        final View leftmost = hasStore ? btnStore : btnInputs;
        btnInputs.setOnClickListener(v -> showInputs());
        btnSleep.setOnClickListener(v -> showSleepTimer());
        btnSleep.setOnLongClickListener(v -> {
            repeatSleepTimer();
            return true;
        });
        btnWifi.setOnClickListener(v -> open(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        btnSettings.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        btnSettings.setOnLongClickListener(v -> {
            showHomeScreenMenu(v);
            return true;
        });

        // Keep focus on the top bar at its ends and at the top: Android's focus finder would otherwise
        // jump to a tile far below when there is nothing in that direction.
        View.OnKeyListener pillKeys = (v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (keyCode == KeyEvent.KEYCODE_MENU && v.getId() == R.id.btnSettings) {
                showHomeScreenMenu(v);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == leftmost) return true;
            return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && v.getId() == R.id.btnSettings;
        };
        btnStore.setOnKeyListener(pillKeys);
        btnInputs.setOnKeyListener(pillKeys);
        btnSleep.setOnKeyListener(pillKeys);
        btnWifi.setOnKeyListener(pillKeys);
        btnSettings.setOnKeyListener(pillKeys);

        // Hide the name tooltip and reset the magnified row when focus leaves the tiles.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldF, newF) -> {
            boolean onTile = newF != null && newF.getId() == R.id.card;
            if (!onTile) hideHero();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back in front: take the Home cover down once this screen has drawn, so nothing flashes between.
        HomeCover.hideAfterNextDraw(root);
        heroShown = false;
        heroName.setAlpha(0f);
        dockDot.setAlpha(0f);
        adapter.reload();
        grid.scheduleLayoutAnimation();
        updateGreeting();
        updateBackground();
        updateWifiLabel();
        updateRestartHint();
        updateSleepButton();
        handler.postDelayed(minuteTick, MINUTE_MS);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.registerDefaultNetworkCallback(networkCallback);

        // Return to the tile we left from, or the first one.
        grid.post(() -> {
            View focused = getCurrentFocus();
            if (focused != null && grid.findContainingViewHolder(focused) != null) return;
            focusTile(lastFocused);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(sleepTick);
        handler.removeCallbacks(offlineRecheck);
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm != null) cm.unregisterNetworkCallback(networkCallback);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Back from the sleep timer's warning card, which may have added time while this screen stayed resumed.
        if (hasFocus) updateSleepButton();
    }

    @Override
    public void onBackPressed() {
        // Home screen: Back goes nowhere.
    }

    // ---- focus ----------------------------------------------------------------------------------

    /**
     * Every tile focuses like the dock: it and its row neighbours magnify and lift, and the name tooltip and dot
     * follow it. The dock row sits at the bottom of the screen; a grid row scrolls just enough to show
     * the magnified icon with its name above and the dot below.
     */
    @Override
    public void onTileFocused(int position, View card) {
        lastFocused = position;
        View item = (View) card.getParent();
        int dy;
        if (adapter.isDocked(position)) {
            int decoratedTop = item.getTop() - (int) (6 * density);
            dy = decoratedTop - grid.getPaddingTop();
        } else {
            int above = (int) (card.getHeight() * (DOCK_MAGNIFY - 1) + (DOCK_LIFT_DP + 8 + 34) * density);
            int below = (int) (16 * density);
            int top = item.getTop() + card.getTop() - above;
            int bottom = item.getTop() + card.getBottom() + below;
            dy = top < 0 ? top : Math.max(0, bottom - grid.getHeight());
        }
        if (Math.abs(dy) > 1) grid.smoothScrollBy(0, dy);
        else dy = 0;
        magnifyRow(position);
        showHero(position, cardRect(card, dy));
    }

    /** The tile's rect in root coordinates once the grid has scrolled by dy (layout position, unscaled). */
    private Rect cardRect(View card, int dy) {
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        r.offset(0, -dy);
        return r;
    }

    private int rowOf(int position) {
        int dock = adapter.dockCount();
        return position < dock ? -1 : (position - dock) / COLUMNS;
    }

    /** Scale icons by distance from the focused one, within its row; focused < 0 resets them all. */
    private void magnifyRow(int focused) {
        int row = focused < 0 ? Integer.MIN_VALUE : rowOf(focused);
        for (int c = 0; c < grid.getChildCount(); c++) {
            View item = grid.getChildAt(c);
            int i = grid.getChildAdapterPosition(item);
            View card = item.findViewById(R.id.card);
            if (i == RecyclerView.NO_POSITION || card == null) continue;
            int d = focused < 0 || rowOf(i) != row ? Integer.MAX_VALUE : Math.abs(i - focused);
            float s = d == 0 ? DOCK_MAGNIFY : d == 1 ? DOCK_NEIGHBOUR : 1f;
            float lift = d == 0 ? DOCK_LIFT_DP : d == 1 ? DOCK_NEIGHBOUR_LIFT_DP : 0f;
            // Only touch icons that change: restarting animators on every visible tile drops frames.
            if (card.getScaleX() == s && card.getTranslationY() == -lift * density) continue;
            card.setPivotX(card.getWidth() / 2f);
            card.setPivotY(card.getHeight());
            card.animate().scaleX(s).scaleY(s).translationY(-lift * density)
                    .setDuration(DOCK_ANIM_MS).setInterpolator(DOCK_EASE).start();
            // The shadow grows from the same point (the icon's bottom centre) so it stays under the icon.
            View shadow = item.findViewById(R.id.shadow);
            shadow.setPivotX(shadow.getWidth() / 2f);
            shadow.setPivotY(card.getBottom() - shadow.getTop());
            shadow.animate().scaleX(s).scaleY(s).translationY(-lift * density)
                    .setDuration(DOCK_ANIM_MS).setInterpolator(DOCK_EASE).start();
        }
    }

    // Name tooltip above the magnified icon and a dot below it.
    private void showHero(int position, Rect card) {
        heroName.setText(adapter.labelAt(position));
        heroName.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float iconTop = card.bottom - card.height() * DOCK_MAGNIFY - DOCK_LIFT_DP * density;
        float nameX = card.exactCenterX() - heroName.getMeasuredWidth() / 2f;
        float nameY = iconTop - 8 * density - heroName.getMeasuredHeight();
        float dotX = card.exactCenterX() - dockDot.getWidth() / 2f;
        float dotY = card.bottom + 3 * density;
        if (!heroShown) {
            // Appearing: place the label and dot, then fade them in.
            heroName.animate().cancel();
            dockDot.animate().cancel();
            heroName.setX(nameX);
            heroName.setY(nameY);
            dockDot.setX(dotX);
            dockDot.setY(dotY);
            heroName.animate().alpha(1f).setDuration(160).start();
            dockDot.animate().alpha(1f).setDuration(160).start();
            heroShown = true;
        } else {
            // Moving along the dock: glide with the icons instead of jumping.
            heroName.animate().x(nameX).y(nameY).alpha(1f)
                    .setDuration(DOCK_ANIM_MS).setInterpolator(DOCK_EASE).start();
            dockDot.animate().x(dotX).y(dotY).alpha(1f)
                    .setDuration(DOCK_ANIM_MS).setInterpolator(DOCK_EASE).start();
        }
    }

    private void hideHero() {
        if (!heroShown) return;
        heroShown = false;
        heroName.animate().alpha(0f).setDuration(160).start();
        dockDot.animate().alpha(0f).setDuration(160).start();
        magnifyRow(-1);
    }

    // ---- navigation ------------------------------------------------------------------------------

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        int count = adapter.getItemCount();
        int dock = adapter.dockCount();
        int target;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            if (position < dock) return false;             // dock row: let focus move to the top bar
            int col = (position - dock) % COLUMNS;
            if (position - dock < COLUMNS) {
                if (dock == 0) return false;
                target = Math.min(col, dock - 1);          // first grid row: up to the dock
            } else {
                target = position - COLUMNS;
            }
        } else {
            if (position < dock) {
                if (count == dock) return true;            // nothing below the dock
                target = Math.min(dock + position, count - 1);
            } else {
                int lastRowStart = dock + ((count - 1 - dock) / COLUMNS) * COLUMNS;
                if (position >= lastRowStart) return true; // already on the last row: stay put
                target = Math.min(position + COLUMNS, count - 1);
            }
        }
        focusTile(target);
        return true;
    }

    /**
     * Android 9 (Fire OS 7) drops android:fontVariationSettings set in XML on a resource font (it is not even kept
     * for getFontVariationSettings), so every weight a layout asks for shows as regular. Set it again in code.
     */
    static void setWeight(TextView t, int weight) {
        if (t != null) t.setFontVariationSettings("'wght' " + weight);
    }

    private static int lcmUpTo(int n) {
        int l = 1;
        for (int i = 2; i <= n; i++) {
            int a = l, b = i;
            while (b != 0) { int t = a % b; a = b; b = t; }
            l = l / a * i;
        }
        return l;
    }

    // ---- dock editing ----------------------------------------------------------------------------

    @Override
    public void onTileMenu(int position, View card) {
        String pkg = adapter.pkgAt(position);
        boolean docked = adapter.isDocked(position);
        int dock = adapter.dockCount();
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (220 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        if (docked) {
            addInputRow(rows, popup, R.drawable.ic_unpin, getString(R.string.unpin_from_dock), -1, () -> {
                adapter.unpin(pkg);
                focusAfterLayout(pkg);
            });
            if (position > 0) addInputRow(rows, popup, R.drawable.ic_left, getString(R.string.move_left), -1, () -> {
                adapter.move(pkg, -1);
                focusAfterLayout(pkg);
            });
            if (position < dock - 1) addInputRow(rows, popup, R.drawable.ic_right, getString(R.string.move_right), -1, () -> {
                adapter.move(pkg, 1);
                focusAfterLayout(pkg);
            });
        } else {
            addInputRow(rows, popup, R.drawable.ic_pin, getString(R.string.pin_to_dock), -1, () -> {
                if (adapter.pin(pkg)) focusAfterLayout(pkg);
                else Toast.makeText(this, R.string.dock_full, Toast.LENGTH_SHORT).show();
            });
        }
        addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.hide_app), -1, () -> {
            String label = adapter.labelAt(position);
            String next = adapter.pkgAt(position + 1);
            if (next.isEmpty()) next = adapter.pkgAt(position - 1);
            adapter.hide(pkg);
            focusAfterLayout(next);
            Toast.makeText(this, getString(R.string.app_hidden, label), Toast.LENGTH_LONG).show();
        });
        // CLEAR_TASK: Fire OS's app-info screen is single-top, so without it a copy left in the background comes
        // back still showing the app it was opened for last time.
        addInputRow(rows, popup, R.drawable.ic_info, getString(R.string.app_info), -1, () ->
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)));

        // Float the menu just above the tile, centred on it; the name tooltip steps aside while it's open.
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        float top = docked ? r.bottom - r.height() * DOCK_MAGNIFY - DOCK_LIFT_DP * density : r.top;
        int x = (int) (r.exactCenterX() - width / 2f);
        int y = (int) (top - 12 * density - panel.getMeasuredHeight());
        heroName.animate().alpha(0f).setDuration(120).start();
        popup.setOnDismissListener(() -> {
            if (adapter.isDocked(lastFocused) && heroShown) heroName.animate().alpha(1f).setDuration(160).start();
        });
        panel.setAlpha(0f);
        panel.setTranslationY(8 * density);
        popup.showAtLocation(root, Gravity.NO_GRAVITY, x, y);
        panel.animate().alpha(1f).translationY(0f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    // Escape hatch on the TV itself: Menu (or hold OK) on the Settings button switches the Home redirect.
    private void showHomeScreenMenu(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (370 * density);   // fits "Usar la pantalla de inicio de Amazon"
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        boolean on = HomeRedirectService.isEnabled(this);
        addInputRow(rows, popup, R.drawable.ic_settings, getString(on ? R.string.use_amazon_home : R.string.use_this_home), -1, () -> {
            if (!HomeRedirectService.setEnabled(this, !on)) {
                Toast.makeText(this, R.string.redirect_needs_permission, Toast.LENGTH_LONG).show();
            }
        });
        Map<String, String> hiddenApps = adapter.hiddenApps();
        if (!hiddenApps.isEmpty()) {
            addInputRow(rows, popup, R.drawable.ic_show, getString(R.string.show_hidden_apps, hiddenApps.size()), -1,
                    () -> showHiddenApps(anchor, hiddenApps));
        }
        addInputRow(rows, popup, R.drawable.ic_screensaver, getString(R.string.screensaver_after, duration(screensaverMinutes())), -1,
                () -> showScreensaverTimes(anchor));
        addInputRow(rows, popup, R.drawable.ic_restart_tv, getString(R.string.restart_tv), -1, this::restartTv);
        popup.showAsDropDown(anchor, anchor.getWidth() - width, (int) (10 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    /** Amazon's Restart, highlighted; Amazon asks to confirm. See HomeRedirectService.restartTv. */
    private void restartTv() {
        if (!HomeRedirectService.restartTv(this)) {
            Toast.makeText(this, R.string.restart_failed, Toast.LENGTH_LONG).show();
        }
    }

    /** Apps hidden on the TV, under the Settings button: picking one puts it back on the grid. */
    private void showHiddenApps(View anchor, Map<String, String> hiddenApps) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (290 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        for (Map.Entry<String, String> app : hiddenApps.entrySet()) {
            addInputRow(rows, popup, R.drawable.ic_show, app.getValue(), -1, () -> {
                adapter.unhide(app.getKey());
                focusAfterLayout(app.getKey());
            });
        }
        popup.showAsDropDown(anchor, anchor.getWidth() - width, (int) (10 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    /** After the dock changes, focus the app's new tile once the grid has re-laid out. */
    private void focusAfterLayout(String pkg) {
        hideHero();
        ((GridLayoutManager) grid.getLayoutManager()).getSpanSizeLookup().invalidateSpanIndexCache();
        grid.invalidateItemDecorations();
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                int pos = adapter.positionOf(pkg);
                RecyclerView.ViewHolder vh = pos >= 0 ? grid.findViewHolderForAdapterPosition(pos) : null;
                if (vh == null) {
                    focusTile(Math.max(pos, 0));
                    return;
                }
                View c = vh.itemView.findViewById(R.id.card);
                c.requestFocus();
                onTileFocused(pos, c);
            }
        });
    }

    private void focusTile(int position) {
        if (adapter.getItemCount() == 0) return;
        final int pos = Math.max(0, Math.min(position, adapter.getItemCount() - 1));
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(pos);
        if (vh != null) {
            vh.itemView.findViewById(R.id.card).requestFocus();
            return;
        }
        // Not laid out yet: scroll there, then focus once the scroll settles.
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(pos);
                View target = h != null ? h.itemView.findViewById(R.id.card) : rv.getChildAt(0);
                if (target != null) target.requestFocus();
            }
        });
        grid.smoothScrollToPosition(pos);
    }

    // ---- top bar ---------------------------------------------------------------------------------

    // Morning 5-12, afternoon 12-17, evening 17-22, night 22-5. Indexes GREETINGS and BACKGROUNDS.
    private static final int[] GREETINGS = {
            R.string.good_morning, R.string.good_afternoon, R.string.good_evening, R.string.good_night};
    private static final int[] BACKGROUNDS = {
            R.drawable.bg_morning, R.drawable.bg_afternoon, R.drawable.bg_evening, R.drawable.bg_night};

    private static int dayPart() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return 3;
        if (hour < 12) return 0;
        if (hour < 17) return 1;
        if (hour < 22) return 2;
        return 3;
    }

    // After a week switched on (standby counts), memory on this 1.7 GB TV is mostly used up and swapping;
    // a restart frees it. A quiet reminder under the clock, gone once the TV has been restarted.
    private static final int RESTART_HINT_DAYS = 7;

    private void updateRestartHint() {
        TextView hint = findViewById(R.id.restartHint);
        int days = (int) (SystemClock.elapsedRealtime() / 86_400_000L);
        if (days >= RESTART_HINT_DAYS) {
            hint.setText(getString(R.string.restart_hint, days));
            hint.setVisibility(View.VISIBLE);
        } else {
            hint.setVisibility(View.GONE);
        }
    }

    private void updateGreeting() {
        String fixed = getString(R.string.greeting_text).trim();
        if (!fixed.isEmpty()) {
            greeting.setText(fixed);
            return;
        }
        String text = getString(GREETINGS[dayPart()]);
        String name = getString(R.string.user_name).trim();
        greeting.setText(name.isEmpty() ? text : text + ", " + name);
    }

    private void updateBackground() {
        int res = BACKGROUNDS[dayPart()];
        if (res == bgRes) return;
        boolean first = bgRes == 0;
        bgRes = res;
        if (first) {
            bgPhoto.setImageResource(res);
            return;
        }
        // Fade through black so the swap isn't a jump cut.
        bgPhoto.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            bgPhoto.setImageResource(res);
            bgPhoto.animate().alpha(1f).setDuration(900);
        });
    }

    /** Shows the Wi-Fi network name on the button when we can read it, otherwise the connection type. */
    // Signal bars only (no network name, so screenshots stay private); text only when something is wrong.
    private void updateWifiLabel() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network net = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
        if (caps == null) {
            setWifiPill(R.drawable.ic_wifi_off, getString(R.string.offline), COLOR_ERROR);
            showOfflineCard(R.string.offline_title, R.string.offline_body);
            return;
        }
        boolean online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (online) showOfflineCard(0, 0);
        else showOfflineCard(R.string.no_internet_title, R.string.no_internet_body);
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            setWifiPill(R.drawable.ic_ethernet, online ? getString(R.string.ethernet) : getString(R.string.no_internet),
                    online ? 0 : COLOR_WARN);
            return;
        }
        WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = wm != null ? wm.getConnectionInfo() : null;
        int bars = info != null ? WifiManager.calculateSignalLevel(info.getRssi(), WIFI_ICONS.length) : 0;
        if (!online) {
            setWifiPill(WIFI_ICONS[bars], getString(R.string.no_internet), COLOR_WARN);
            return;
        }
        setWifiPill(WIFI_ICONS[bars], getString(R.string.wifi), 0);
    }

    private void setWifiPill(int icon, String label, int alertColor) {
        btnWifi.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        btnWifi.setContentDescription(label);
        // Healthy: a round icon button like Inputs and Settings. Alert: a pill with the status text.
        if (alertColor == 0) {
            setPillText(btnWifi, null);
            return;
        }
        SpannableString text = new SpannableString("\u25CF  " + label);
        text.setSpan(new ForegroundColorSpan(alertColor), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setPillText(btnWifi, text);
    }

    /** A top button with text is a pill; without (null), a round icon button like Inputs and Settings. */
    private void setPillText(TextView button, CharSequence text) {
        boolean iconOnly = text == null;
        ViewGroup.LayoutParams lp = button.getLayoutParams();
        lp.width = iconOnly ? (int) (42 * density) : ViewGroup.LayoutParams.WRAP_CONTENT;
        button.setLayoutParams(lp);
        button.setPaddingRelative((int) ((iconOnly ? 10 : 16) * density), 0, (int) ((iconOnly ? 10 : 20) * density), 0);
        button.setCompoundDrawablePadding(iconOnly ? 0 : (int) (9 * density));
        button.setText(iconOnly ? "" : text);
    }

    // ---- no internet -----------------------------------------------------------------------------

    // The Wi-Fi pill alone is easy to miss, and apps only say they can't load. After a while without internet a
    // card in the middle of the screen says so plainly. The wait covers the TV joining Wi-Fi after turning on.
    private static final long OFFLINE_CARD_DELAY_MS = 20_000;
    private long offlineSince;   // elapsedRealtime when the connection was first seen down, 0 while online
    private final Runnable offlineRecheck = this::updateWifiLabel;

    /** Shows the card with these texts once the connection has been down for a while; title 0 hides it. */
    private void showOfflineCard(int title, int body) {
        handler.removeCallbacks(offlineRecheck);
        if (title == 0) {
            offlineSince = 0;
            if (offlineCard.getVisibility() == View.VISIBLE) {
                offlineCard.animate().alpha(0f).setDuration(300)
                        .withEndAction(() -> offlineCard.setVisibility(View.GONE)).start();
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (offlineSince == 0) offlineSince = now;
        long wait = offlineSince + OFFLINE_CARD_DELAY_MS - now;
        if (wait > 0) {
            handler.postDelayed(offlineRecheck, wait);
            return;
        }
        ((TextView) findViewById(R.id.offlineTitle)).setText(title);
        ((TextView) findViewById(R.id.offlineBody)).setText(body);
        if (offlineCard.getVisibility() != View.VISIBLE || offlineCard.getAlpha() < 1f) {
            offlineCard.setVisibility(View.VISIBLE);
            offlineCard.animate().alpha(1f).setDuration(400).start();   // cancels a fade-out and its GONE
        }
    }

    // ---- sleep timer -----------------------------------------------------------------------------

    private static final int[] SLEEP_MINUTES = {15, 30, 45, 60, 90, 120};
    private final Runnable sleepTick = this::updateSleepButton;

    /** The moon button shows the time left while a timer is set. */
    private void updateSleepButton() {
        handler.removeCallbacks(sleepTick);
        long left = SleepTimer.remainingMs(this);
        if (left < 0) {
            setPillText(btnSleep, null);
            btnSleep.setContentDescription(getString(R.string.sleep_timer));
            return;
        }
        String text = duration(minutesLeft(left));
        setPillText(btnSleep, text);
        btnSleep.setContentDescription(getString(R.string.sleep_timer) + ": " + text);
        handler.postDelayed(sleepTick, left % MINUTE_MS + 50);   // when the minutes shown go down by one
    }

    private static int minutesLeft(long ms) {
        return (int) Math.max(1, (ms + MINUTE_MS - 1) / MINUTE_MS);
    }

    /** "45 min", "1 h", "1 h 30 min". */
    private String duration(int minutes) {
        if (minutes < 60) return getString(R.string.sleep_minutes, minutes);
        if (minutes % 60 == 0) return getString(R.string.sleep_hours, minutes / 60);
        return getString(R.string.sleep_hours_minutes, minutes / 60, minutes % 60);
    }

    // The last time picked from the moon menu, which holding OK on the moon sets again without the menu.
    private static final String PREF_SLEEP_LAST = "sleep_last_minutes";

    private void startSleepTimer(int minutes) {
        SleepTimer.set(this, minutes);
        prefs().edit().putInt(PREF_SLEEP_LAST, minutes).apply();
        updateSleepButton();
        Toast.makeText(this, getString(R.string.sleep_set, SleepTimer.endTimeText(this)), Toast.LENGTH_LONG).show();
    }

    /** Hold OK on the moon: the last timer again, or the menu when none was ever set. */
    private void repeatSleepTimer() {
        int last = prefs().getInt(PREF_SLEEP_LAST, 0);
        if (last > 0) startSleepTimer(last);
        else showSleepTimer();
    }

    /** Dropdown under the moon button: how long until the TV turns off, and a way to call it off. */
    private void showSleepTimer() {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        long left = SleepTimer.remainingMs(this);
        int width = (int) ((left >= 0 ? 340 : 200) * density);   // fits "Quitar temporizador (quedan 1 h 30 min)"
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        if (left >= 0) {
            addInputRow(rows, popup, R.drawable.ic_close, getString(R.string.sleep_cancel, duration(minutesLeft(left))), -1, () -> {
                SleepTimer.cancel(this);
                updateSleepButton();
                Toast.makeText(this, R.string.sleep_cancelled, Toast.LENGTH_SHORT).show();
            });
            addSeparator(rows);
        }
        for (int minutes : SLEEP_MINUTES) {
            addInputRow(rows, popup, R.drawable.ic_schedule, duration(minutes), -1, () -> startSleepTimer(minutes));
        }
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(btnSleep, dropDownX(btnSleep, width), (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    // ---- screensaver start time ------------------------------------------------------------------

    private static final int[] SCREENSAVER_MINUTES = {5, 10, 15, 30};
    private static final int DEFAULT_SCREEN_OFF_MS = 300_000;
    // Amazon's copy of screen_off_timeout (secure table), kept in step by its own Settings app.
    private static final String CONTEXTUAL_SCREEN_OFF = "contextual_screen_off_timeout";
    // Settings.Secure.SLEEP_TIMEOUT, hidden from apps: standby this long after the last key press.
    private static final String SLEEP_TIMEOUT = "sleep_timeout";

    private int screensaverMinutes() {
        return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_MS)
                / (int) MINUTE_MS;
    }

    /** Under the Settings button's menu: how long without a key press before the screensaver starts. */
    private void showScreensaverTimes(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = (int) (200 * density);
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        int current = screensaverMinutes();
        int focus = 0;
        for (int minutes : SCREENSAVER_MINUTES) {
            if (minutes == current) focus = rows.getChildCount();
            addInputRow(rows, popup, minutes == current ? R.drawable.ic_check : R.drawable.ic_schedule, duration(minutes), -1, () -> {
                if (setScreensaverMinutes(minutes)) {
                    Toast.makeText(this, getString(R.string.screensaver_set, duration(minutes)), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.redirect_needs_permission, Toast.LENGTH_LONG).show();
                }
            });
        }
        popup.showAsDropDown(anchor, anchor.getWidth() - width, (int) (10 * density), Gravity.START);
        rows.getChildAt(focus).requestFocus();
    }

    /**
     * Sets the screensaver start time. The TV goes to standby sleep_timeout after the last key press, screensaver
     * or not, so that moves by the same amount: the screensaver then runs as long as before. Needs
     * WRITE_SECURE_SETTINGS, which also covers the system table; false without it.
     */
    private boolean setScreensaverMinutes(int minutes) {
        ContentResolver cr = getContentResolver();
        int old = Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, DEFAULT_SCREEN_OFF_MS);
        int ms = minutes * (int) MINUTE_MS;
        try {
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, ms);
            if (Settings.Secure.getString(cr, CONTEXTUAL_SCREEN_OFF) != null) Settings.Secure.putInt(cr, CONTEXTUAL_SCREEN_OFF, ms);
            int sleep = Settings.Secure.getInt(cr, SLEEP_TIMEOUT, -1);
            if (sleep > 0) Settings.Secure.putInt(cr, SLEEP_TIMEOUT, Math.max(ms, sleep + ms - old));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    /** Dropdown menu under the Inputs pill: HDMI ports (with connection state), AV and Live TV. */
    private void showInputs() {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);

        // Hanging right below the Inputs pill, wide enough for a renamed input ("Consola de juegos").
        int width = Math.max(anchor.getWidth(), (int) (210 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));   // needed for Back to dismiss
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        boolean sawNonHdmi = false;
        Set<String> hiddenInputs = hiddenInputs();
        Map<String, String> hiddenNames = new LinkedHashMap<>();   // id -> name shown, for the hidden-inputs menu
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            // HDMI ports first in port order, then AV and the rest.
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this)));
            });
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(this);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                if (info.getType() == TvInputInfo.TYPE_COMPOSITE) label = getString(R.string.av);
                final String id = info.getId();
                final String portLabel = label;
                String custom = prefs().getString(PREF_INPUT_NAME + id, null);
                final String rowLabel = custom != null ? custom : label;
                if (hiddenInputs.contains(id)) {
                    hiddenNames.put(id, rowLabel);
                    continue;
                }
                int state = tim.getInputState(id);
                if (!hdmi && rows.getChildCount() > 0 && !sawNonHdmi) addSeparator(rows);
                if (!hdmi) sawNonHdmi = true;
                addInputRow(rows, popup, hdmi ? R.drawable.ic_input : R.drawable.ic_av, rowLabel, state,
                        () -> open(new Intent(this, InputPlayerActivity.class)
                                .putExtra(InputPlayerActivity.EXTRA_INPUT_ID, id)
                                .putExtra(InputPlayerActivity.EXTRA_LABEL, rowLabel)),
                        () -> showInputOptions(id, portLabel, rowLabel, custom != null));
            }
        }
        Intent liveTv = liveTvPkg.isEmpty() ? null : getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_livetv, getString(R.string.live_tv), -1, () -> open(liveTv));
        }
        if (!hiddenNames.isEmpty()) {
            if (rows.getChildCount() > 0) addSeparator(rows);
            addInputRow(rows, popup, R.drawable.ic_show, getString(R.string.hidden_inputs, hiddenNames.size()), -1,
                    () -> showHiddenInputs(hiddenNames));
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }

        // Drop in: fade and unfold from the top edge.
        panel.setAlpha(0f);
        panel.setPivotY(0f);
        panel.setScaleY(0.92f);
        panel.setTranslationY(-8 * density);
        popup.showAsDropDown(anchor, dropDownX(anchor, width), (int) (10 * density), Gravity.START);
        panel.animate().alpha(1f).scaleY(1f).translationY(0f).setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
        rows.getChildAt(0).requestFocus();
    }

    private void addSeparator(LinearLayout rows) {
        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    // ---- inputs: names and hiding, edited on the TV ----------------------------------------------

    // What is plugged into each port changes, so names and hidden ports are set from the Inputs menu (Menu or
    // hold OK on a port) and saved on the device rather than in config.xml.
    private static final String PREF_INPUT_NAME = "input_name:";   // + input id
    private static final String PREF_INPUTS_HIDDEN = "inputs_hidden";

    private SharedPreferences prefs() {
        return getSharedPreferences("tvhome", MODE_PRIVATE);
    }

    private Set<String> hiddenInputs() {
        String saved = prefs().getString(PREF_INPUTS_HIDDEN, "");
        return saved.isEmpty() ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(saved.split(",")));
    }

    private void setInputHidden(String id, boolean hide) {
        Set<String> hidden = hiddenInputs();
        if (hide) hidden.add(id);
        else hidden.remove(id);
        prefs().edit().putString(PREF_INPUTS_HIDDEN, String.join(",", hidden)).apply();
    }

    /** Menu on one input: rename it, go back to its port name, or hide it. Reopens the Inputs menu after. */
    private void showInputOptions(String id, String portLabel, String shown, boolean renamed) {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = Math.max(anchor.getWidth(), (int) (280 * density));   // fits "Nombre original (HDMI 1)"
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        addInputRow(rows, popup, R.drawable.ic_edit, getString(R.string.rename_input), -1,
                () -> renameInput(id, portLabel, shown));
        if (renamed) {
            addInputRow(rows, popup, R.drawable.ic_input, getString(R.string.reset_name) + " (" + portLabel + ")", -1, () -> {
                prefs().edit().remove(PREF_INPUT_NAME + id).apply();
                showInputs();
            });
        }
        addInputRow(rows, popup, R.drawable.ic_hide, getString(R.string.hide_input), -1, () -> {
            setInputHidden(id, true);
            showInputs();
        });
        popup.showAsDropDown(anchor, dropDownX(anchor, width), (int) (10 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    /** A text box for the input's name, typed with the TV's on-screen keyboard. Empty goes back to the port name. */
    private void renameInput(String id, String portLabel, String shown) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setText(shown);
        edit.setSelection(shown.length());
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE);
        FrameLayout box = new FrameLayout(this);
        int pad = (int) (20 * density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(edit);
        Runnable save = () -> {
            String name = edit.getText().toString().trim();
            if (name.isEmpty() || name.equals(portLabel)) prefs().edit().remove(PREF_INPUT_NAME + id).apply();
            else prefs().edit().putString(PREF_INPUT_NAME + id, name).apply();
            showInputs();
        };
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(getString(R.string.rename_title, portLabel))
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> save.run())
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // The TV keyboard's own button ("Siguiente") saves too: closing the keyboard leaves focus on Cancel.
        edit.setOnEditorActionListener((v, actionId, event) -> {
            dialog.dismiss();
            save.run();
            return true;
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        edit.requestFocus();
    }

    /** x offset that keeps a dropdown of this width on screen when its anchor sits near the right edge. */
    private int dropDownX(View anchor, int width) {
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int margin = (int) (24 * density);
        int overflow = at[0] + width - (root.getWidth() - margin);
        return overflow > 0 ? -overflow : 0;
    }

    /** Hidden inputs, at the end of the Inputs menu: picking one shows it again. */
    private void showHiddenInputs(Map<String, String> hiddenNames) {
        View anchor = findViewById(R.id.btnInputs);
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, root, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int width = Math.max(anchor.getWidth(), (int) (210 * density));
        final PopupWindow popup = new PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setElevation(0f);
        for (Map.Entry<String, String> input : hiddenNames.entrySet()) {
            addInputRow(rows, popup, R.drawable.ic_show, input.getValue(), -1, () -> {
                setInputHidden(input.getKey(), false);
                showInputs();
            });
        }
        popup.showAsDropDown(anchor, dropDownX(anchor, width), (int) (10 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action) {
        addInputRow(rows, popup, iconRes, title, state, action, null);
    }

    /** A menu row; onMenu, if given, runs on the remote's Menu key or a long press of OK. */
    private void addInputRow(LinearLayout rows, PopupWindow popup, int iconRes, String title, int state, Runnable action,
                             Runnable onMenu) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        setWeight(row.findViewById(R.id.title), 500);   // as res/layout/item_input.xml
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(iconRes);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        TextView status = row.findViewById(R.id.status);
        View dot = row.findViewById(R.id.dot);
        // This TV reports "standby" for every port whether or not something is plugged in, so only
        // definite states are shown.
        if (state == TvInputManager.INPUT_STATE_CONNECTED) {
            status.setText(R.string.connected);
            dot.setVisibility(View.VISIBLE);
        } else if (state == TvInputManager.INPUT_STATE_DISCONNECTED) {
            status.setText(R.string.not_connected);
            dot.setVisibility(View.GONE);
        } else {
            status.setVisibility(View.GONE);
            dot.setVisibility(View.GONE);
        }
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
        if (onMenu != null) {
            row.setOnLongClickListener(v -> {
                popup.dismiss();
                onMenu.run();
                return true;
            });
            row.setOnKeyListener((v, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    popup.dismiss();
                    onMenu.run();
                }
                return true;
            });
        }
        rows.addView(row);
    }
}
