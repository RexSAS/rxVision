package com.andrea.tvlauncher;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.widget.HorizontalScrollView;
import android.net.Uri;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.ContextThemeWrapper;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

public class MainActivity extends Activity {

    static final String NAME = "Andrea";

    /** Default dock apps, left to right, until it is rearranged. Apps that aren't installed are skipped. */
    static final String[] DOCK = {
        // RxFilms: release and debug builds of the Jellyfin fork (~/git/project/jellyfin-plus-TV); only the installed one shows.
        "org.rxfilms.tv",
        "org.rxfilms.tv.debug",
        "com.spotify.tv.android",
        "org.smarttube.stable",
        "com.amazon.smarthomemapviewapp",
    };

    /** Line icons drawn in the clock's accent, used instead of the apps' own tiles. */
    static final Map<String, Integer> DOCK_ICONS = new HashMap<>();
    static {
        DOCK_ICONS.put("org.rxfilms.tv", R.drawable.ic_dock_jellyfin);
        DOCK_ICONS.put("org.rxfilms.tv.debug", R.drawable.ic_dock_jellyfin);
        DOCK_ICONS.put("com.spotify.tv.android", R.drawable.ic_dock_spotify);
        DOCK_ICONS.put("org.smarttube.stable", R.drawable.ic_dock_youtube);
        DOCK_ICONS.put("com.amazon.smarthomemapviewapp", R.drawable.ic_dock_smarthome);
    }

    /** Themes offered in Settings: name, style (which sets the accent) and wallpaper in assets. */
    // Only add to the end: the chosen theme is saved by its position.
    static final String[] THEME_NAMES = {
        "Ghost", "Black Hole", "Moon", "Dusk", "Summit", "Tokyo", "Charger", "Leopard", "Night Peak", "Knight",
    };
    static final int[] THEME_STYLES = {
        R.style.Theme_Launcher_Ghost, R.style.Theme_Launcher_BlackHole, R.style.Theme_Launcher_Moon,
        R.style.Theme_Launcher_Dusk, R.style.Theme_Launcher_Summit, R.style.Theme_Launcher_Tokyo,
        R.style.Theme_Launcher_Charger, R.style.Theme_Launcher_Leopard, R.style.Theme_Launcher_NightPeak,
        R.style.Theme_Launcher_Knight,
    };
    static final String[] THEME_WALLPAPERS = {
        "wallpapers/ghost.jpg", "wallpapers/blackhole.jpg", "wallpapers/moon.jpg", "wallpapers/dusk.jpg",
        "wallpapers/summit.jpg", "wallpapers/tokyo.jpg", "wallpapers/charger.jpg", "wallpapers/leopard.jpg",
        "wallpapers/nightpeak.jpg", "wallpapers/knight.jpg",
    };

    /** Shuffle modes; the "shuffle" pref holds the position. */
    static final String[] SHUFFLE_NAMES = {"Off", "Every hour", "Every day", "Every power on"};
    static final int SHUFFLE_OFF = 0, SHUFFLE_HOURLY = 1, SHUFFLE_DAILY = 2, SHUFFLE_POWER_ON = 3;

    private SharedPreferences prefs;
    private View nowPlaying;
    private ImageView mediaArt, playPause;
    private TextView mediaTitle, mediaArtist;
    /** The track whose art is on show, so updates that drop the art don't swap in the app icon. */
    private String artTrack;
    private final int playPauseId = View.generateViewId();
    private MediaSessionManager sessions;
    /** The session the widget shows and controls, or null when nothing is playing. */
    private MediaController media;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> pickMedia(list);
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { showMedia(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { showMedia(); }
        @Override public void onSessionDestroyed() { setMedia(null); }
    };
    private int theme;
    private ImageView wallpaper;
    /** The theme's accent: clock colon, divider and focus glow. */
    private int accent;

    private TextView greeting, clock, date;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            if (!asleep && !saving && shuffle(false)) return;
            // Wake at the start of the next minute.
            ticker.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };
    private LinearLayout dock;
    /** The floating card of the dock app being held, or null. */
    private View popup, popupFrom;
    /** The RxHome logo while it plays; Home's intro waits for it. */
    private Splash splash;

    /** Synced lyrics in the top-right corner, shown only while the screensaver is up. */
    private View lyricsView;
    private final TextView[] lyricLines = new TextView[3];
    /** Which of the three lines is the one being sung. */
    private int lyricTop;
    private final int LYRIC_LINE = Math.round(32 * android.content.res.Resources.getSystem().getDisplayMetrics().density);
    private Lyrics lyrics;
    /** The track the lyrics are for (or being fetched for), so late answers for old songs are dropped. */
    private String lyricsTrack;
    private int lyricIndex = -2;
    private final Map<String, Lyrics> lyricsCache = new HashMap<>();
    private final Runnable lyricTick = new Runnable() {
        @Override public void run() {
            showLyricLine();
            ticker.postDelayed(this, 200);
        }
    };
    static final float DOCK_FOCUS_SCALE = 1.3f;
    /** Dock icon sizes by steps from the focused one: big beside it, small further out. */
    static final float[] DOCK_SCALES = {DOCK_FOCUS_SCALE, 0.8f, 0.5f, 0.42f, 0.38f};
    static final long DOCK_ANIM = 360;

    /** Size of a dock icon d steps from the focused one: big beside it, tapering to small at the ends. */
    static float dockScale(int d) {
        return DOCK_SCALES[Math.min(d, DOCK_SCALES.length - 1)];
    }
    /** The dock item being moved with left/right, or null. */
    private View moving;
    private final int gearId = View.generateViewId();
    /** The middle dock item: where focus lands on Home and when coming down from the top. */
    private final int middleDockId = View.generateViewId();

    /** Views the power-on intro reveals, in order; the dock's items follow them. */
    private View backdrop, greetingView, clockView, dateRow, buttons;
    /** Set while the screen is off, so the intro plays when the TV comes back on. */
    private boolean introPending = true;

    /** Idle time on Home before the screensaver starts, and how often it changes the theme. */
    static final long SAVER_DELAY = 2 * 60000, SAVER_INTERVAL = 60000;
    /** Idle time on Home before the stick offers to go to sleep, and how long the offer stands. */
    static final long SLEEP_AFTER = 90 * 60000;
    static final int SLEEP_WARNING_SECONDS = 15;
    private View sleepWarning, stayButton;
    private TextView sleepCountdown;
    private int sleepSecondsLeft;
    private final Runnable offerSleep = this::offerSleep;
    private final Runnable sleepTick = this::sleepTick;
    private boolean saving;
    /** The theme the screensaver is showing; the saved theme is left alone. */
    private int saverTheme;
    private ImageView wallpaperNext, nextButton;
    private View divider;
    private final Runnable startSaver = this::startSaver;
    private final Runnable saverCycle = new Runnable() {
        @Override public void run() {
            int next = new Random().nextInt(THEME_NAMES.length - 1);
            showTheme(next >= saverTheme ? next + 1 : next);
            ticker.postDelayed(this, SAVER_INTERVAL);
        }
    };
    private boolean resumed;
    /** From screen-off until the picture is back; onResume must not start the intro meanwhile. */
    private boolean asleep;
    /** The TV panel takes a moment to show the HDMI picture after waking; don't play to a dark screen. */
    static final long WAKE_DELAY = 1200;
    /** Dark pause before the logo draws, after the screen comes on. */
    static final long SPLASH_DELAY = 1000;

    private final Runnable wake = () -> {
        asleep = false;
        // The logo first (it was put up when the screen went off); a power-on theme shuffle
        // rebuilds the screen, so it waits until the logo is done.
        if (splash != null) startSplash(() -> { if (!shuffle(true)) maybePlayIntro(); });
        else if (!shuffle(true)) maybePlayIntro();
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                introPending = true;
                asleep = true;
                stopSaver(false);
                dock.removeCallbacks(wake);
                hideForIntro();
                showSplash(); // so the TV wakes to the logo, not to Home
            } else {
                dock.postDelayed(wake, WAKE_DELAY);
            }
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { buildDock(); }
    };

    @Override
    protected void onCreate(Bundle state) {
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        theme = Math.min(prefs.getInt("theme", 0), THEME_NAMES.length - 1);
        setTheme(THEME_STYLES[theme]);
        super.onCreate(state);
        TypedArray attrs = obtainStyledAttributes(new int[] {R.attr.accent});
        accent = attrs.getColor(0, 0xFFFF3B3B);
        attrs.recycle();
        saverTheme = theme;

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);

        // Wallpaper and scrims sit together so the intro can fade and zoom them as one.
        FrameLayout back = new FrameLayout(this);
        backdrop = back;
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        back.addView(wallpaper, match());
        // The screensaver fades each new wallpaper in on this, over the current one.
        wallpaperNext = new ImageView(this);
        wallpaperNext.setScaleType(ImageView.ScaleType.CENTER_CROP);
        wallpaperNext.setAlpha(0f);
        back.addView(wallpaperNext, match());

        back.addView(scrim(GradientDrawable.Orientation.TOP_BOTTOM, 0x99000000),
            new FrameLayout.LayoutParams(-1, dp(220), Gravity.TOP));
        back.addView(scrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xAA000000),
            new FrameLayout.LayoutParams(-1, dp(200), Gravity.BOTTOM));
        root.addView(back, match());
        loadWallpaper();

        root.addView(header(), wrap(Gravity.TOP | Gravity.START, dp(48), dp(28), 0, 0));
        buttons = topButtons();
        root.addView(buttons, wrap(Gravity.TOP | Gravity.END, 0, dp(40), dp(48), 0));
        nowPlaying = nowPlaying();
        root.addView(nowPlaying, nowPlayingSpot(false));

        dock = new LinearLayout(this);
        // Fading draws through a buffer cut to the view's box, shaving the arc's dipped icons; fade each part instead.
        dock.forceHasOverlappingRendering(false);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setGravity(Gravity.BOTTOM);
        root.addView(dock, wrap(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, dockLift()));

        root.addView(lyricsPanel(), wrap(Gravity.TOP | Gravity.END, 0, dp(48), dp(56), 0));

        sleepWarning = sleepWarning();
        root.addView(sleepWarning, match());

        // A fresh start (not a theme change, which recreates with saved state) opens on the logo.
        if (state == null) {
            showSplash();
            splash.post(() -> startSplash(this::maybePlayIntro));
        }

        setContentView(root);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);
        buildDock();

        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenReceiver, screenFilter);
        hideForIntro();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        // Opened from Amazon's app row (HomeService's own launches carry no category): Home is ours again.
        if (getIntent().hasCategory(Intent.CATEGORY_LAUNCHER)) HomeService.stockMode = false;
        tick.run();
        watchMedia();
        buildDock(); // picks up apps added or removed in All apps
        dock.post(this::focusDock);
        dock.post(this::maybePlayIntro);
        // Home has its own screensaver; this keeps Fire OS's from taking over after its idle timeout.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        idle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        closePopup();
        resumed = false;
        ticker.removeCallbacks(tick);
        ticker.removeCallbacks(startSaver);
        stopSaver(false);
        unwatchMedia();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        unregisterReceiver(screenReceiver);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        focusDock();
    }

    /**
     * The remote's media keys drive the now-playing widget and leave the screensaver alone. Any
     * other press restarts the idle countdown; while the screensaver is up, it only ends it.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (sleepWarning.getVisibility() == View.VISIBLE) {
            // Any press means someone's here: stay awake, back to Home.
            if (event.getAction() == KeyEvent.ACTION_UP) {
                hideSleepWarning();
                stopSaver(true);
            }
            return true;
        }
        if (media != null && mediaKey(event)) return true;
        if (saving) {
            if (event.getAction() == KeyEvent.ACTION_UP) stopSaver(true);
            return true;
        }
        idle();
        if (popup != null && popupKey(event)) return true;
        if (moving != null) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) moveKey(event.getKeyCode());
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean mediaKey(KeyEvent event) {
        boolean press = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (press) playPause.performClick();
                return true;
            // The Fire TV remote has no next/previous keys; its fast-forward and rewind keys skip instead.
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (press) media.getTransportControls().skipToNext();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (press) media.getTransportControls().skipToPrevious();
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onBackPressed() {
        // This is Home: there is nothing to go back to.
    }

    // --- Header ---

    private View header() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        greeting = text(13, 0xCCFFFFFF, 0.25f);
        // Never wrap: when the clock is narrower than the greeting, the name would drop out of sight.
        greeting.setSingleLine(true);
        greeting.setEllipsize(null);
        // Wrap, not the column default of match_parent, or it's cut to the clock's width.
        col.addView(greeting, new LinearLayout.LayoutParams(-2, -2));
        greetingView = greeting;

        clock = text(72, 0xFFFFFFFF, 0.02f);
        clock.setTypeface(thin(200));
        clock.setShadowLayer(dp(10), 0, 0, 0x66FFFFFF);
        // Room for the glow, which is otherwise clipped to a visible box around the digits.
        clock.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.setMargins(-dp(12), -dp(12), 0, -dp(12));
        col.addView(clock, clp);
        clockView = clock;

        // Accent bar and date, on a line under the clock.
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        divider = new View(this);
        divider.setBackgroundColor(accent);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(2), dp(18));
        dlp.setMargins(dp(3), 0, dp(12), 0);
        row.addView(divider, dlp);

        date = text(13, 0xE6FFFFFF, 0.3f);
        date.setTypeface(digital(500));
        row.addView(date);

        col.addView(row);
        dateRow = row;
        return col;
    }

    private void updateClock() {
        Date now = new Date();
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String hello = hour < 5 ? "Good night" : hour < 12 ? "Good morning"
            : hour < 18 ? "Good afternoon" : hour < 22 ? "Good evening" : "Good night";
        greeting.setText((hello + ", " + NAME).toUpperCase(Locale.ENGLISH));

        String hm = new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(now);
        SpannableString s = new SpannableString(hm);
        s.setSpan(new ForegroundColorSpan(accent), 2, 3, 0);
        clock.setText(s);
        date.setText(new SimpleDateFormat("EEE  dd . MM", Locale.ENGLISH).format(now).toUpperCase(Locale.ENGLISH));
    }

    private Typeface digital(int weight) {
        return font("orbitron.ttf", weight);
    }

    /** Thin techy digits for the big clock; Orbitron bottoms out too heavy. */
    private Typeface thin(int weight) {
        return font("exo2.ttf", weight);
    }

    private Typeface font(String asset, int weight) {
        if (Build.VERSION.SDK_INT < 26) return Typeface.createFromAsset(getAssets(), asset);
        return new Typeface.Builder(getAssets(), asset)
            .setFontVariationSettings("'wght' " + weight).build();
    }

    // --- Power-on intro ---

    /** Puts every intro view at its starting point: black screen, content just off its resting place. */
    private void hideForIntro() {
        ticker.post(() -> {
            backdrop.animate().cancel();
            backdrop.setAlpha(0f);
            backdrop.setScaleX(1.12f);
            backdrop.setScaleY(1.12f);
            start(greetingView, -dp(28), 0);
            start(clockView, 0, dp(18));
            start(dateRow, -dp(28), 0);
            start(buttons, 0, -dp(16));
            start(nowPlaying, 0, -dp(16));
            for (int i = 0; i < dock.getChildCount(); i++) start(dock.getChildAt(i), 0, dp(48));
        });
    }

    private static void start(View v, float dx, float dy) {
        v.animate().cancel();
        v.setAlpha(0f);
        v.setTranslationX(dx);
        v.setTranslationY(dy);
    }

    /** Covers the screen with the logo, not yet drawing; startSplash runs it. */
    private void showSplash() {
        if (splash != null) return;
        splash = new Splash(this, accent, digital(600));
        ((ViewGroup) dock.getParent()).addView(splash, match());
    }

    /**
     * Draws the logo after a beat, as the TV takes a moment to show a picture once it wakes,
     * then hands over to `then` once it has lifted away.
     */
    private void startSplash(Runnable then) {
        Splash logo = splash;
        logo.play(SPLASH_DELAY, () -> {
            if (splash == logo) splash = null;
            then.run();
        });
    }

    private void maybePlayIntro() {
        if (!introPending || !resumed || asleep || splash != null) return; // the splash calls back when done
        // The activity can resume while the TV is still off; SCREEN_ON will call back.
        if (!((PowerManager) getSystemService(POWER_SERVICE)).isInteractive()) return;
        introPending = false;

        DecelerateInterpolator ease = new DecelerateInterpolator(2.2f);
        backdrop.animate().alpha(1f).setDuration(1100).setInterpolator(new DecelerateInterpolator()).withLayer().start();
        // The zoom runs on past the fade so the picture keeps settling as the rest arrives.
        backdrop.animate().scaleX(1f).scaleY(1f).setDuration(2600).setInterpolator(ease).withLayer().start();

        reveal(greetingView, 350, ease);
        reveal(clockView, 480, ease);
        reveal(dateRow, 620, ease);
        reveal(buttons, 720, ease);
        reveal(nowPlaying, 720, ease);
        for (int i = 0; i < dock.getChildCount(); i++) reveal(dock.getChildAt(i), 800 + i * 90, ease);
    }

    private void reveal(View v, long delay, DecelerateInterpolator ease) {
        ViewPropertyAnimator a = v.animate().alpha(1f).translationX(0).translationY(0)
            .setStartDelay(delay).setDuration(750).setInterpolator(ease);
        // A layer is cut to the view's own box; dock icons reach outside theirs in the arc.
        if (v.getParent() != dock) a.withLayer();
        a.start();
    }

    // --- Now playing ---

    private View nowPlaying() {
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setClipChildren(false);
        pill.setClipToPadding(false);
        pill.setPadding(dp(8), dp(8), dp(12), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x8C1A1A1E);
        bg.setCornerRadius(dp(14));
        pill.setBackground(bg);
        pill.setVisibility(View.GONE);

        mediaArt = new ImageView(this);
        mediaArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mediaArt.setClipToOutline(true);
        GradientDrawable artShape = new GradientDrawable();
        artShape.setCornerRadius(dp(8));
        mediaArt.setBackground(artShape);
        pill.addView(mediaArt, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        mediaTitle = text(14, 0xFFFFFFFF, 0.01f);
        mediaArtist = text(12, 0xB3FFFFFF, 0.02f);
        for (TextView t : new TextView[] {mediaTitle, mediaArtist}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setMaxWidth(dp(240));
            // Wrap, not the column's default match_parent: a matched TextView never re-measures on
            // new text, so the widget kept the width of whatever title it first laid out.
            text.addView(t, new LinearLayout.LayoutParams(-2, -2));
        }
        // A fixed width keeps the widget one size whatever plays; long titles end in an ellipsis.
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(110), -2);
        tlp.setMargins(dp(12), 0, dp(10), 0);
        pill.addView(text, tlp);

        playPause = mediaButton(R.drawable.ic_media_pause, v -> {
            if (media == null) return;
            if (isPlaying(media.getPlaybackState())) media.getTransportControls().pause();
            else media.getTransportControls().play();
        });
        playPause.setId(playPauseId);
        pill.addView(playPause);
        ImageView next = nextButton = mediaButton(R.drawable.ic_media_next, v -> {
            if (media != null) media.getTransportControls().skipToNext();
        });
        ((LinearLayout.LayoutParams) next.getLayoutParams()).leftMargin = dp(6);
        pill.addView(next);
        return pill;
    }

    private ImageView mediaButton(int icon, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setFocusable(true);
        b.setNextFocusDownId(middleDockId);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, focused) -> setGlow(b, focused, 1.2f));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        setGlow(b, false, 1f);
        return b;
    }

    private void watchMedia() {
        sessions = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName listener = new ComponentName(this, MediaListener.class);
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener);
            pickMedia(sessions.getActiveSessions(listener));
        } catch (SecurityException e) {
            // Notification access not granted (see MediaListener): no widget.
            setMedia(null);
        }
    }

    private void unwatchMedia() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        setMedia(null);
    }

    /** Shows whatever is playing; a paused app stays only if it was already the one on show. */
    private void pickMedia(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) for (MediaController c : list) {
            if (isPlaying(c.getPlaybackState())) { pick = c; break; }
            if (media != null && c.getSessionToken().equals(media.getSessionToken()) && isPaused(c.getPlaybackState())) pick = c;
        }
        setMedia(pick);
    }

    private void setMedia(MediaController c) {
        if (media != null) media.unregisterCallback(mediaCallback);
        media = c;
        if (media != null) media.registerCallback(mediaCallback, ticker);
        showMedia();
    }

    private void showMedia() {
        PlaybackState state = media == null ? null : media.getPlaybackState();
        boolean show = isPlaying(state) || isPaused(state);
        if (show) {
            MediaMetadata meta = media.getMetadata();
            String title = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            mediaTitle.setText(title);
            String artist = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            mediaArtist.setText(artist);
            mediaArtist.setVisibility(TextUtils.isEmpty(artist) ? View.GONE : View.VISIBLE);
            Bitmap art = meta == null ? null : meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null && meta != null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            // Spotify briefly re-sends the track without its art on play/pause; keep what's shown.
            String track = media.getPackageName() + "|" + title + "|" + artist;
            if (art != null) mediaArt.setImageBitmap(art);
            else if (!track.equals(artTrack)) mediaArt.setImageDrawable(appIcon(media.getPackageName()));
            artTrack = track;
            playPause.setImageResource(isPlaying(state) ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
        }
        updateLyrics();
        int visibility = show ? View.VISIBLE : View.GONE;
        if (nowPlaying.getVisibility() == visibility) return;
        if (!show && nowPlaying.hasFocus()) focusDock();
        nowPlaying.setVisibility(visibility);
        linkDockUp();
    }

    private Drawable appIcon(String pkg) {
        try {
            return getPackageManager().getApplicationIcon(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPlaying(PlaybackState s) {
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static boolean isPaused(PlaybackState s) {
        return s != null && s.getState() == PlaybackState.STATE_PAUSED;
    }

    // --- Screensaver ---

    private void idle() {
        ticker.removeCallbacks(startSaver);
        ticker.postDelayed(startSaver, SAVER_DELAY);
    }

    /** Clears the controls off screen, leaving the clock and wallpaper, which changes every minute. */
    /**
     * Three lines that take turns, like a teleprompter: the one being sung, in the accent; the next,
     * in white beneath it; and a spare waiting below to rise in as the next-but-one.
     */
    private View lyricsPanel() {
        FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        for (int i = 0; i < 3; i++) {
            TextView t = text(18, 0xFFFFFFFF, 0.01f);
            t.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            t.setLetterSpacing(0.08f); // the greeting's plain system font, spaced out a little like it
            t.setMaxLines(1);
            if (Build.VERSION.SDK_INT >= 26) {
                t.setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            } else {
                t.setEllipsize(TextUtils.TruncateAt.END);
            }
            t.setAlpha(0f);
            box.addView(t, new FrameLayout.LayoutParams(dp(600), LYRIC_LINE));
            lyricLines[i] = t;
        }
        box.setAlpha(0f);
        lyricsView = box;
        return box;
    }

    /** Shows or hides the lyrics for what's playing; fetches them once per song. */
    private void updateLyrics() {
        PlaybackState state = media == null ? null : media.getPlaybackState();
        MediaMetadata meta = media == null ? null : media.getMetadata();
        String title = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta == null ? null : meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (!saving || !isPlaying(state) || TextUtils.isEmpty(title) || TextUtils.isEmpty(artist)) {
            ticker.removeCallbacks(lyricTick);
            lyricsView.animate().alpha(0f).setDuration(400).start();
            return;
        }
        String track = artist + "|" + title;
        if (!track.equals(lyricsTrack)) {
            lyricsTrack = track;
            lyrics = lyricsCache.get(track);
            lyricIndex = -2;
            if (lyrics == null && !lyricsCache.containsKey(track)) {
                Lyrics.fetch(artist, title, meta.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    meta.getLong(MediaMetadata.METADATA_KEY_DURATION), ticker, found -> {
                        lyricsCache.put(track, found);
                        if (!track.equals(lyricsTrack)) return;
                        lyrics = found;
                        lyricIndex = -2;
                        updateLyrics();
                    });
            }
        }
        ticker.removeCallbacks(lyricTick);
        if (lyrics == null) {
            lyricsView.animate().alpha(0f).setDuration(400).start();
            return;
        }
        lyricTick.run();
        lyricsView.animate().alpha(1f).setDuration(600).start();
    }

    /** Puts up the line at the song's current position, scrolling the lines up when it moves on. */
    private void showLyricLine() {
        PlaybackState state = media == null ? null : media.getPlaybackState();
        if (lyrics == null || state == null) return;
        long pos = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        int i = lyrics.indexAt(pos);
        if (i == lyricIndex) return;
        boolean step = i == lyricIndex + 1 && lyricIndex >= -1;
        lyricIndex = i;
        TextView now = lyricLines[lyricTop], next = lyricLines[(lyricTop + 1) % 3], spare = lyricLines[(lyricTop + 2) % 3];
        if (step) {
            // The sung line rises away; the next takes its place and lights up; the spare rises in under it.
            slideLyric(now, -LYRIC_LINE, 0f, 0xFFFFFFFF);
            slideLyric(next, 0, 1f, accent);
            spare.setText(lyricText(i + 1));
            spare.setTranslationY(2 * LYRIC_LINE);
            spare.setAlpha(0f);
            spare.setTextColor(0xFFFFFFFF);
            slideLyric(spare, LYRIC_LINE, 0.75f, 0xFFFFFFFF);
            lyricTop = (lyricTop + 1) % 3;
        } else {
            // A seek or a fresh start: no scroll to show, just set the two lines in place.
            for (TextView t : lyricLines) t.animate().cancel();
            now.setText(lyricText(i));
            now.setTranslationY(0);
            now.setTextColor(accent);
            now.setAlpha(i < 0 ? 0f : 1f);
            next.setText(lyricText(i + 1));
            next.setTranslationY(LYRIC_LINE);
            next.setTextColor(0xFFFFFFFF);
            next.setAlpha(0.75f);
            spare.setAlpha(0f);
        }
    }

    private void slideLyric(TextView t, float y, float alpha, int color) {
        t.animate().translationY(y).alpha(alpha).setDuration(500).setInterpolator(new DecelerateInterpolator(1.6f)).start();
        ValueAnimator tint = ValueAnimator.ofArgb(t.getCurrentTextColor(), color).setDuration(500);
        tint.addUpdateListener(a -> t.setTextColor((int) a.getAnimatedValue()));
        tint.start();
    }

    /** Line i's words; instrumental gaps come as empty lines and show a note instead. */
    private String lyricText(int i) {
        if (i < 0 || i >= lyrics.lines.length) return "";
        return lyrics.lines[i].isEmpty() ? "\u266A" : lyrics.lines[i];
    }

    private void startSaver() {
        closePopup();
        // Not over an open menu or while the TV is off; try again after another idle stretch.
        if (!resumed || asleep || !hasWindowFocus()) {
            idle();
            return;
        }
        saving = true;
        AccelerateInterpolator in = new AccelerateInterpolator(1.6f);
        buttons.animate().translationY(-dp(160)).alpha(0f).setStartDelay(0).setDuration(900).setInterpolator(in).start();
        dock.animate().translationY(dp(220)).alpha(0f).setStartDelay(0).setDuration(900).setInterpolator(in).start();
        moveNowPlaying(true);
        updateLyrics();
        ticker.postDelayed(saverCycle, SAVER_INTERVAL);
        ticker.postDelayed(offerSleep, SLEEP_AFTER - SAVER_DELAY);
    }

    // --- Auto sleep ---

    private View sleepWarning() {
        FrameLayout shade = new FrameLayout(this);
        shade.setBackgroundColor(0xB3000000);
        shade.setVisibility(View.GONE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(48), dp(36), dp(48), dp(36));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61A1A1E);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);

        sleepCountdown = text(26, 0xFFFFFFFF, 0.02f);
        sleepCountdown.setTypeface(thin(300));
        // Wrap, not the column default of match_parent, or it's cut to the button's width.
        card.addView(sleepCountdown, new LinearLayout.LayoutParams(-2, -2));

        TextView stay = text(16, 0xFFFFFFFF, 0.04f);
        stay.setText("Stay awake?");
        stay.setPadding(dp(28), dp(10), dp(28), dp(10));
        stayButton = stay;
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.topMargin = dp(24);
        card.addView(stay, slp);

        shade.addView(card, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        return shade;
    }

    /** After a long idle stretch, offer to sleep; not while something is playing, though. */
    private void offerSleep() {
        PlaybackState state = media == null ? null : media.getPlaybackState();
        if (isPlaying(state)) {
            ticker.postDelayed(offerSleep, 60000);
            return;
        }
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(accent);
        pill.setCornerRadius(dp(22));
        stayButton.setBackground(pill);
        sleepSecondsLeft = SLEEP_WARNING_SECONDS;
        updateSleepCountdown();
        sleepWarning.setAlpha(0f);
        sleepWarning.setVisibility(View.VISIBLE);
        sleepWarning.animate().alpha(1f).setStartDelay(0).setDuration(400).start();
        ticker.postDelayed(sleepTick, 1000);
    }

    private void sleepTick() {
        if (--sleepSecondsLeft > 0) {
            updateSleepCountdown();
            ticker.postDelayed(sleepTick, 1000);
            return;
        }
        hideSleepWarning();
        // Without the Home service, let Fire OS's own timeout put it to sleep instead.
        if (!HomeService.sleep()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void updateSleepCountdown() {
        sleepCountdown.setText("TV will shut down in " + sleepSecondsLeft
            + (sleepSecondsLeft == 1 ? " second" : " seconds"));
    }

    private void hideSleepWarning() {
        ticker.removeCallbacks(sleepTick);
        sleepWarning.animate().cancel();
        sleepWarning.setVisibility(View.GONE);
    }

    /** The widget sits top centre normally, and in the bottom-right corner during the screensaver. */
    private FrameLayout.LayoutParams nowPlayingSpot(boolean saver) {
        return saver ? wrap(Gravity.BOTTOM | Gravity.END, 0, 0, dp(48), dp(40))
            : wrap(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(32), 0, 0);
    }

    /**
     * Into the screensaver the widget leaves off the top and rises into the corner from below;
     * out of it, the reverse.
     */
    private void moveNowPlaying(boolean saver) {
        int h = nowPlaying.getHeight();
        float toTop = -(dp(32) + h + dp(24)), toBottom = dp(40) + h + dp(24);
        nowPlaying.animate().cancel();
        nowPlaying.animate().translationY(saver ? toTop : toBottom).setStartDelay(0).setDuration(500)
            .setInterpolator(new AccelerateInterpolator(1.6f))
            .withEndAction(() -> {
                nowPlaying.setLayoutParams(nowPlayingSpot(saver));
                nowPlaying.setTranslationY(saver ? toBottom : toTop);
                nowPlaying.animate().translationY(0).setStartDelay(150).setDuration(750)
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
            }).start();
    }

    /** Back to the saved theme, with the controls slid back into place. */
    private void stopSaver(boolean animate) {
        ticker.removeCallbacks(saverCycle);
        ticker.removeCallbacks(offerSleep);
        hideSleepWarning();
        if (!saving) return;
        saving = false;
        updateLyrics();
        if (animate) {
            showTheme(theme);
            moveNowPlaying(false);
            DecelerateInterpolator out = new DecelerateInterpolator(2f);
            buttons.animate().translationY(0).alpha(1f).setStartDelay(0).setDuration(700).setInterpolator(out).start();
            dock.animate().translationY(0).alpha(1f).setStartDelay(0).setDuration(700).setInterpolator(out).start();
            idle();
        } else {
            saverTheme = theme;
            setAccent(THEME_STYLES[theme]);
            wallpaperNext.animate().cancel();
            wallpaperNext.setAlpha(0f);
            loadWallpaper();
            nowPlaying.setLayoutParams(nowPlayingSpot(false));
            for (View v : new View[] {buttons, dock, nowPlaying}) {
                v.animate().cancel();
                v.setTranslationY(0);
                v.setAlpha(1f);
            }
        }
    }

    /** Fades to another theme's wallpaper and accent without rebuilding, as the screensaver needs. */
    private void showTheme(int which) {
        if (which == saverTheme) return;
        saverTheme = which;
        final String asset = THEME_WALLPAPERS[which];
        new Thread(() -> {
            Bitmap bmp = decode(asset, 1920);
            if (bmp == null) return;
            runOnUiThread(() -> {
                if (saverTheme != which) return;
                wallpaperNext.animate().cancel();
                wallpaperNext.setImageBitmap(bmp);
                wallpaperNext.setAlpha(0f);
                wallpaperNext.animate().alpha(1f).setStartDelay(0).setDuration(1500).withEndAction(() -> {
                    wallpaper.setImageBitmap(bmp);
                    wallpaperNext.setAlpha(0f);
                    wallpaperNext.setImageDrawable(null);
                }).start();
                setAccent(THEME_STYLES[which]);
            });
        }).start();
    }

    /** Recolours what stays visible during the screensaver: clock colon, divider, skip button. */
    private void setAccent(int style) {
        TypedArray a = obtainStyledAttributes(style, new int[] {R.attr.accent});
        accent = a.getColor(0, accent);
        a.recycle();
        divider.setBackgroundColor(accent);
        nextButton.setImageDrawable(new ContextThemeWrapper(this, style).getDrawable(R.drawable.ic_media_next));
        updateClock();
    }

    // --- Top-right buttons ---

    private View topButtons() {
        LinearLayout row = new LinearLayout(this);
        row.addView(roundButton(R.drawable.ic_wifi, v -> Apps.start(this, settingsScreen("network.NetworkActivity"))));
        View gear = roundButton(R.drawable.ic_settings, v -> showSettingsMenu());
        gear.setId(gearId);
        ((LinearLayout.LayoutParams) gear.getLayoutParams()).leftMargin = dp(14);
        row.addView(gear);
        return row;
    }

    private View roundButton(int icon, View.OnClickListener click) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setFocusable(true);
        b.setNextFocusDownId(middleDockId);
        b.setOnClickListener(click);
        b.setOnFocusChangeListener((v, focused) -> setGlow(b, focused, 1.25f));
        setGlow(b, false, 1f);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    /** Background-free icons show focus by growing and catching a glow in the accent. */
    private void setGlow(ImageView icon, boolean focused, float scale) {
        icon.animate().alpha(focused ? 1f : 0.85f).setDuration(DOCK_ANIM).start(); // the dock refines this per icon
        GradientDrawable g = focused ? glow(icon.getLayoutParams().width / 2f) : null;
        icon.setBackground(g);
        if (g != null) ObjectAnimator.ofInt(g, "alpha", 0, 255).setDuration(250).start(); // fade the glow in
        float s = focused ? scale : 1f;
        icon.animate().scaleX(s).scaleY(s).setDuration(150).start();
    }

    /** Soft accent-coloured disc that fades out before the view's edges, so no box shows. */
    private GradientDrawable glow(float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {accent & 0x59FFFFFF, accent & 0x1AFFFFFF, 0x00000000});
        g.setGradientRadius(radius);
        return g;
    }

    /**
     * Shortcuts into Fire OS Settings. Display & Sounds is left out: its screen needs a permission
     * only Amazon's apps hold, so it's reached through the full Settings page under "My Fire TV".
     */
    private void showSettingsMenu() {
        final String[] names = {"Theme", "Network", "Applications", "My Fire TV"};
        final Intent[] screens = {
            null,
            settingsScreen("network.NetworkActivity"),
            settingsScreen("applications.ApplicationsActivity"),
            // Amazon's main Settings page, opened directly: ACTION_SETTINGS goes through Amazon's
            // home, which HomeService turns back to us before the page shows.
            new Intent("amazon.intent.action.MAINSETTINGS").setClassName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity"),
        };
        Menus.style(this, new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Settings")
            .setItems(names, (d, which) -> {
                if (which == 0) showThemeMenu();
                else Apps.start(this, screens[which]);
            })
            .show());
    }

    private void showThemeMenu() {
        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Theme")
            .setSingleChoiceItems(THEME_NAMES, theme, (d, which) -> {
                d.dismiss();
                if (which == theme) return;
                applyTheme(which);
            })
            .setNeutralButton("Shuffle: " + SHUFFLE_NAMES[prefs.getInt("shuffle", SHUFFLE_OFF)],
                (d, w) -> showShuffleMenu())
            .setNegativeButton(dockLabel(), null)
            .show();
        Menus.style(this, dialog);
        // Set after show() so pressing it doesn't close the dialog: the label flips to show the change.
        Button toggle = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        toggle.setOnClickListener(v -> {
            prefs.edit().putBoolean("arcDock", !arcDock()).apply();
            toggle.setText(dockLabel());
            // Glide to the new height while the icons bend into (or out of) the arc.
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dock.getLayoutParams();
            ValueAnimator lift = ValueAnimator.ofInt(lp.bottomMargin, dockLift()).setDuration(DOCK_ANIM * 2);
            lift.setInterpolator(new DecelerateInterpolator(1.5f));
            lift.addUpdateListener(a -> {
                lp.bottomMargin = (int) a.getAnimatedValue();
                dock.requestLayout();
            });
            lift.start();
            updateDockScale(dock.getFocusedChild());
        });
        // The dialog fills the screen's height, so give its buttons some room off the bottom edge...
        View bar = (View) toggle.getParent();
        bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop(), bar.getPaddingRight(), bar.getPaddingBottom() + dp(24));
        // Centre the two buttons: drop the gap that pushes them apart and pack them in the middle.
        View spacer = dialog.findViewById(getResources().getIdentifier("spacer", "id", "android"));
        if (spacer != null) spacer.setVisibility(View.GONE);
        if (bar instanceof LinearLayout) ((LinearLayout) bar).setGravity(Gravity.CENTER_HORIZONTAL);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
        // Fire OS's button row keeps unexplained room on its right; measure the pair once laid out
        // and slide it so it sits mid-screen, under the title.
        Button shuffle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        bar.post(() -> {
            int[] l = new int[2], r = new int[2];
            shuffle.getLocationOnScreen(l);
            toggle.getLocationOnScreen(r);
            float mid = (l[0] + r[0] + toggle.getWidth()) / 2f;
            bar.setTranslationX(getResources().getDisplayMetrics().widthPixels / 2f - mid);
        });
        // Same off the top edge, above the title.
        View title = dialog.findViewById(getResources().getIdentifier("alertTitle", "id", "android"));
        if (title != null) {
            View top = (View) title.getParent();
            top.setPadding(top.getPaddingLeft(), top.getPaddingTop() + dp(24), top.getPaddingRight(), top.getPaddingBottom());
        }
    }

    private String dockLabel() {
        return "Dock: " + (arcDock() ? "Arc" : "Straight");
    }

    /** The saved theme's accent, for screens that don't hold it themselves. */
    static int accent(Context c) {
        int t = Math.min(c.getSharedPreferences("launcher", MODE_PRIVATE).getInt("theme", 0), THEME_STYLES.length - 1);
        TypedArray a = c.obtainStyledAttributes(THEME_STYLES[t], new int[] {R.attr.accent});
        int color = a.getColor(0, 0xFFFF3B3B);
        a.recycle();
        return color;
    }

    /** The dock bends into an arc around the focused app, or stays a straight row. */
    private boolean arcDock() {
        return prefs.getBoolean("arcDock", true);
    }

    /** The arc needs more room below it, as its ends dip. */
    private int dockLift() {
        return dp(arcDock() ? 55 : 40);
    }

    private void showShuffleMenu() {
        Menus.style(this, new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Random theme")
            .setSingleChoiceItems(SHUFFLE_NAMES, prefs.getInt("shuffle", SHUFFLE_OFF), (d, which) -> {
                d.dismiss();
                // Count from now, so turning it on doesn't switch the theme straight away.
                prefs.edit().putInt("shuffle", which).putString("shufflePeriod", period(which)).apply();
            })
            .show());
    }

    private void applyTheme(int which) {
        prefs.edit().putInt("theme", which).apply();
        // Rebuilding picks up the new accent everywhere, and replays the intro into it.
        recreate();
    }

    /**
     * Switches to a random other theme when the shuffle mode says it's time: a new hour or
     * day has begun since the last pick, or the TV has just been turned on.
     */
    private boolean shuffle(boolean poweredOn) {
        int mode = prefs.getInt("shuffle", SHUFFLE_OFF);
        if (mode == SHUFFLE_OFF || mode == SHUFFLE_POWER_ON && !poweredOn) return false;
        if (mode != SHUFFLE_POWER_ON) {
            String now = period(mode);
            if (now.equals(prefs.getString("shufflePeriod", now))) return false;
            prefs.edit().putString("shufflePeriod", now).apply();
        }
        int next = new Random().nextInt(THEME_NAMES.length - 1);
        applyTheme(next >= theme ? next + 1 : next);
        return true;
    }

    /** The current hour or day, as a key that changes when the next one starts. */
    private static String period(int mode) {
        String pattern = mode == SHUFFLE_HOURLY ? "yyyyMMddHH" : mode == SHUFFLE_DAILY ? "yyyyMMdd" : "";
        return new SimpleDateFormat(pattern, Locale.ENGLISH).format(new Date());
    }

    private static Intent settingsScreen(String activity) {
        return new Intent().setClassName("com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv." + activity);
    }

    // --- Dock ---

    private void buildDock() {
        // Fire OS sends frequent package broadcasts; keep the selection in the dock across rebuilds.
        List<Apps.App> apps = new ArrayList<>();
        List<Object> pkgs = new ArrayList<>();
        for (String pkg : dockApps(this)) {
            Apps.App app = Apps.load(this, pkg);
            if (app != null) { apps.add(app); pkgs.add(pkg); }
        }
        // Same apps as on show: leave the dock be, as fresh views would replay the focus animations.
        // shortcut: an app updating its icon only shows after another dock change, rebuild on PACKAGE_CHANGED if it matters.
        List<Object> shown = new ArrayList<>();
        for (int i = 0; i < dock.getChildCount() - 1; i++) shown.add(dock.getChildAt(i).getTag());
        if (dock.getChildCount() > 0 && shown.equals(pkgs)) return;

        View focused = getCurrentFocus();
        int focusedIndex = focused != null && focused.getParent() == dock ? dock.indexOfChild(focused) : -1;
        moving = null;
        dock.removeAllViews();
        for (Apps.App app : apps) {
            String pkg = app.pkg;
            ImageView icon = new ImageView(this);
            Integer glyph = DOCK_ICONS.get(pkg);
            if (glyph != null) icon.setImageResource(glyph);
            else icon.setImageDrawable(app.icon);
            icon.setPadding(dp(6), dp(6), dp(6), dp(6));
            View item = dockItem(icon, app.label, v -> Apps.start(this, app.launch));
            item.setTag(pkg);
            item.setOnLongClickListener(v -> {
                // Alexa Smart Home shares nothing for a card, so it goes straight to the options.
                if (pkg.equals(SMART_HOME)) dockMenu(v, app);
                else appWindow(v, app);
                return true;
            });
            dock.addView(item);
        }

        ImageView all = new ImageView(this);
        all.setImageResource(R.drawable.ic_apps);
        all.setPadding(dp(6), dp(6), dp(6), dp(6));
        dock.addView(dockItem(all, "All apps", v -> {
            Blur.forAllApps = Blur.snapshot(this);
            startActivity(new Intent(this, AppsActivity.class));
            overridePendingTransition(R.anim.open_enter, R.anim.open_exit);
        }));

        // Automatic focus search doesn't reliably jump between the dock and the top-right buttons.
        dock.getChildAt(dock.getChildCount() / 2).setId(middleDockId);
        linkDockUp();
        if (focusedIndex >= 0) dock.getChildAt(Math.min(focusedIndex, dock.getChildCount() - 1)).requestFocus();
        if (introPending) for (int i = 0; i < dock.getChildCount(); i++) start(dock.getChildAt(i), 0, dp(48));
    }

    /** The dock's packages, left to right: the saved order, else DOCK. */
    static List<String> dockApps(Context ctx) {
        String saved = ctx.getSharedPreferences("launcher", MODE_PRIVATE).getString("dock", null);
        return new ArrayList<>(Arrays.asList(saved != null ? TextUtils.split(saved, ",") : DOCK));
    }

    static void saveDock(Context ctx, List<String> pkgs) {
        ctx.getSharedPreferences("launcher", MODE_PRIVATE).edit().putString("dock", TextUtils.join(",", pkgs)).apply();
    }

    /** Holding OK on a dock app. */
    private void dockMenu(View item, Apps.App app) {
        Map<String, Runnable> m = new LinkedHashMap<>();
        m.put("Move", () -> {
            moving = item;
            item.animate().translationY(-dp(18)).setDuration(150).start();
            Toast.makeText(this, "Left or right to move, OK when done", Toast.LENGTH_SHORT).show();
        });
        m.put("Remove from dock", () -> {
            List<String> pkgs = dockApps(this);
            pkgs.remove(app.pkg);
            saveDock(this, pkgs);
            buildDock();
        });
        m.put("More settings", () -> Apps.start(this, Apps.info(app.pkg)));
        Apps.menu(this, app.label, m);
    }

    static final String SPOTIFY = "com.spotify.tv.android", SMARTTUBE = "org.smarttube.stable",
        SMART_HOME = "com.amazon.smarthomemapviewapp";

    /**
     * Holding OK on a dock app: a little card floating just above the dock, with Open, Settings
     * for the old options menu, and what the app has for you: RxFilms' Continue watching and Next
     * up from the Jellyfin server, otherwise what it played lately (from History).
     */
    private void appWindow(View item, Apps.App app) {
        closePopup();
        String pkg = app.pkg;
        float d = getResources().getDisplayMetrics().density;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setMinimumWidth(dp(240)); // otherwise as wide as its rows, so two covers don't sit in an empty card
        card.setBackground(Menus.rounded(0xD90C0C10, 0, 22 * d, 0));
        card.setElevation(dp(16));
        card.setClipChildren(false);
        card.setClipToPadding(false);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClipChildren(false);
        // Room for the focused pill's grow, which the card's edge would otherwise shave.
        head.setPadding(0, dp(4), dp(4), dp(4));
        TextView name = text(11, 0xCCFFFFFF, 0.25f);
        name.setText(app.label.toUpperCase(Locale.ROOT));
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        View open = iconButton(head, R.drawable.ic_media_play), settings = iconButton(head, R.drawable.ic_settings);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));
        open.setOnClickListener(v -> { closePopup(); Apps.start(this, app.launch); });
        settings.setOnClickListener(v -> { closePopup(); dockMenu(item, app); });

        View first = open;
        if (pkg.startsWith("org.rxfilms.tv")) {
            filmsRows(card, app, item);
        } else if (pkg.equals(SMARTTUBE)) {
            first = claudeFmRow(card, app);
        } else if (pkg.equals(SPOTIFY)) {
            first = spotifyRow(card, app);
        } else {
            List<History.Entry> recent = History.load(this, pkg);
            if (recent.isEmpty()) {
                card.addView(note("Nothing yet: what you play here shows up."));
            } else {
                List<View> cards = new ArrayList<>();
                for (History.Entry e : recent) {
                    View c = mediaCard(e.title, e.sub, false, app, () -> { closePopup(); Apps.start(this, app.launch); });
                    Bitmap cover = e.art();
                    if (cover != null) setCover(c, cover);
                    cards.add(c);
                }
                first = addRow(card, "Recent", cards, false);
            }
        }

        // Floats just above the dock's middle, where the held app sits, pointing down at it,
        // and springs up out of it.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setClipChildren(false);
        // Wrap, not the column's default match_parent: the column would otherwise take the
        // pointer's width and squeeze the card to it.
        box.addView(card, new LinearLayout.LayoutParams(-2, -2));
        View tip = new View(this);
        tip.setBackground(new android.graphics.drawable.ShapeDrawable(pointer()));
        ((android.graphics.drawable.ShapeDrawable) tip.getBackground()).getPaint().setColor(0xD90C0C10);
        box.addView(tip, new LinearLayout.LayoutParams(dp(22), dp(10)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dockLift() + dp(130);
        ((ViewGroup) dock.getParent()).addView(box, lp);
        popup = box;
        popupFrom = item;
        box.setAlpha(0f);
        box.setScaleX(0.85f);
        box.setScaleY(0.85f);
        box.setTranslationY(dp(24));
        box.post(() -> {
            box.setPivotX(box.getWidth() / 2f);
            box.setPivotY(box.getHeight());
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(DOCK_ANIM)
                .setInterpolator(new OvershootInterpolator(1.2f)).start();
        });
        View focusFirst = first;
        card.post(focusFirst::requestFocus);
    }

    /** Spotify's row: Liked Songs and the DJ, each opened in Spotify by its link. */
    private View spotifyRow(LinearLayout card, Apps.App app) {
        String[][] picks = {
            {"Liked Songs", "Your favourite songs", "spotify:collection:tracks", ""},
            {"DJ", "Picked by your AI DJ", "spotify:playlist:37i9dQZF1EYkqdzj48dyYq", "DJ"},
        };
        int[][] colors = {{0xFF4A1DD6, 0xFF8FD3C3}, {0xFF101418, accent}};
        List<View> cards = new ArrayList<>();
        for (int i = 0; i < picks.length; i++) {
            String[] p = picks[i];
            View c = mediaCard(p[0], p[1], false, app, () -> {
                closePopup();
                Apps.start(this, new Intent(Intent.ACTION_VIEW, Uri.parse(p[2])).setPackage(SPOTIFY));
            });
            setCover(c, tile(colors[i][0], colors[i][1], p[3]));
            cards.add(c);
        }
        return addRow(card, "Playlists", cards, false);
    }

    /** A square cover drawn here: a diagonal gradient with a big white mark in the middle. */
    private Bitmap tile(int from, int to, String mark) {
        int size = dp(76) * 2;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, size, size, from, to, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF);
        if (mark.isEmpty()) { // a drawn heart: fonts' own heart symbols come out lopsided
            float w = size * 0.42f, x = (size - w) / 2f, y = size * 0.31f;
            android.graphics.Path h = new android.graphics.Path();
            h.moveTo(x + w / 2, y + w * 0.88f);
            h.cubicTo(x - w * 0.05f, y + w * 0.5f, x, y + w * 0.02f, x + w * 0.27f, y);
            h.cubicTo(x + w * 0.4f, y - w * 0.01f, x + w * 0.48f, y + w * 0.08f, x + w / 2, y + w * 0.16f);
            h.cubicTo(x + w * 0.52f, y + w * 0.08f, x + w * 0.6f, y - w * 0.01f, x + w * 0.73f, y);
            h.cubicTo(x + w, y + w * 0.02f, x + w * 1.05f, y + w * 0.5f, x + w / 2, y + w * 0.88f);
            c.drawPath(h, p);
            return b;
        }
        p.setTextAlign(android.graphics.Paint.Align.CENTER);
        p.setTypeface(digital(700));
        p.setTextSize(size * (mark.length() > 1 ? 0.32f : 0.42f));
        android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(mark, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, p);
        return b;
    }

    /** SmartTube's row: the Claude FM live stream, opened in SmartTube. */
    private View claudeFmRow(LinearLayout card, Apps.App app) {
        String[] id = {ClaudeFm.FALLBACK};
        View c = mediaCard("Claude FM", "Live \u00B7 music for thinking and building", true, app, () -> {
            closePopup();
            Apps.start(this, new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=" + id[0])).setPackage(SMARTTUBE));
        });
        Jellyfin.image(ClaudeFm.thumbnail(id[0]), b -> { if (b != null) setCover(c, b); });
        ClaudeFm.find(current -> {
            if (current.equals(id[0])) return;
            id[0] = current; // the stream restarted under a new id
            Jellyfin.image(ClaudeFm.thumbnail(current), b -> { if (b != null) setCover(c, b); });
        });
        List<View> cards = new ArrayList<>();
        cards.add(c);
        return addRow(card, "Live", cards, true);
    }

    /** RxFilms' rows from Jellyfin, or the one-time Quick Connect sign-in that unlocks them. */
    private void filmsRows(LinearLayout card, Apps.App app, View item) {
        if (!Jellyfin.signedIn(this)) {
            TextView note = note("Connect to RxFilms to see Continue watching and Next up here.");
            card.addView(note);
            LinearLayout row = new LinearLayout(this);
            row.setPadding(0, dp(10), 0, dp(4));
            row.setClipChildren(false);
            Button connect = pillButton(row, "Connect");
            ((LinearLayout.LayoutParams) connect.getLayoutParams()).leftMargin = 0;
            card.addView(row);
            connect.setOnClickListener(v -> {
                connect.setEnabled(false);
                note.setText("Connecting to RxFilms\u2026");
                Jellyfin.signIn(this, new Jellyfin.SignIn() {
                    @Override public void code(String code) {
                        note.setText("In any Jellyfin app you're signed in to, open Settings \u2192 Quick Connect and enter  " + code);
                    }
                    @Override public void done(boolean ok) {
                        if (popup == null || ((ViewGroup) popup).getChildAt(0) != card) return;
                        if (ok) appWindow(item, app); // reopen, now with the rows
                        else {
                            note.setText("Couldn't connect. Is Quick Connect on in the server's settings?");
                            connect.setEnabled(true);
                        }
                    }
                });
            });
            return;
        }
        TextView loading = note("Loading\u2026");
        card.addView(loading);
        Jellyfin.load(this, lists -> {
            if (popup == null || ((ViewGroup) popup).getChildAt(0) != card) return; // closed or replaced meanwhile
            card.removeView(loading);
            String[] titles = {"Continue watching", "Next up"};
            View first = null;
            for (int i = 0; i < 2; i++) {
                List<View> cards = new ArrayList<>();
                for (Jellyfin.Item it : lists.get(i)) {
                    View c = mediaCard(it.title, it.sub, true, app, () -> {
                        closePopup();
                        // RxFilms opens an item's page when started with its Jellyfin id.
                        Apps.start(this, new Intent(app.launch).putExtra("ItemId", it.id));
                    });
                    Jellyfin.image(it.image, b -> { if (b != null) setCover(c, b); });
                    cards.add(c);
                }
                if (!cards.isEmpty()) {
                    View f = addRow(card, titles[i], cards, true);
                    if (first == null) first = f;
                }
            }
            if (first == null) card.addView(note(Jellyfin.signedIn(this) ? "Nothing in progress." : "Signed out: hold OK again to reconnect."));
            else first.requestFocus();
        });
    }

    /** A titled, scrolling row of cards; four fit, the rest scroll in. Returns the first card. */
    private View addRow(LinearLayout card, String title, List<View> cards, boolean wide) {
        TextView label = text(9, accent, 0.25f);
        label.setText(title.toUpperCase(Locale.ROOT));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.setMargins(dp(2), dp(8), 0, 0);
        card.addView(label, llp);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        for (View c : cards) row.addView(c);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        // Clipped to its own box (padding included), so extra cards scroll instead of spilling out.
        scroll.setClipChildren(true);
        scroll.setClipToPadding(false);
        // Padding all round so a focused card's grow and outline stay inside the window.
        scroll.setPadding(dp(6), dp(6), dp(6), dp(6));
        scroll.addView(row);
        int each = (wide ? dp(110) : dp(76)) + dp(8) + dp(4);
        card.addView(scroll, new LinearLayout.LayoutParams(Math.min(cards.size(), 4) * each + dp(12), -2));
        return cards.get(0);
    }

    /** A small round icon button that glows in the accent when focused, like the top-right ones. */
    private View iconButton(LinearLayout parent, int icon) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setPadding(dp(7), dp(7), dp(7), dp(7));
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnFocusChangeListener((v, focused) -> setGlow(b, focused, 1.15f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.leftMargin = dp(10);
        parent.addView(b, lp);
        setGlow(b, false, 1f);
        return b;
    }

    /** The little downward point under the card, aimed at the held dock app. */
    private static android.graphics.drawable.shapes.PathShape pointer() {
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(0, 0);
        p.lineTo(22, 0);
        p.lineTo(11, 10);
        p.close();
        return new android.graphics.drawable.shapes.PathShape(p, 22, 10);
    }

    private Button pillButton(LinearLayout parent, String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        parent.addView(b, new LinearLayout.LayoutParams(-2, dp(28)));
        Menus.pill(b, accent, getResources().getDisplayMetrics().density);
        b.setPadding(dp(12), 0, dp(12), 0);
        ((LinearLayout.LayoutParams) b.getLayoutParams()).setMargins(dp(6), 0, 0, 0);
        return b;
    }

    private TextView note(String text) {
        TextView t = text(11, 0x99FFFFFF, 0.02f);
        t.setText(text);
        t.setMaxWidth(dp(420));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        t.setLayoutParams(lp);
        return t;
    }

    /** Closes the dock app card, handing focus back to the app it came from. */
    private void closePopup() {
        if (popup == null) return;
        View card = popup;
        popup = null;
        View back = popupFrom != null && popupFrom.getParent() == dock ? popupFrom : dock.findViewById(middleDockId);
        card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).translationY(dp(16)).setDuration(200)
            .withEndAction(() -> ((ViewGroup) card.getParent()).removeView(card)).start();
        if (back != null) back.requestFocus();
    }

    /** While the card is up, the arrows move only within it and Back closes it. */
    private boolean popupKey(KeyEvent event) {
        int dir;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BACK:
                if (event.getAction() == KeyEvent.ACTION_UP) closePopup();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: dir = View.FOCUS_LEFT; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dir = View.FOCUS_RIGHT; break;
            case KeyEvent.KEYCODE_DPAD_UP: dir = View.FOCUS_UP; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dir = View.FOCUS_DOWN; break;
            default: return false; // OK and the rest work as usual
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            View next = FocusFinder.getInstance().findNextFocus((ViewGroup) popup, popup.findFocus(), dir);
            if (next != null) next.requestFocus();
        }
        return true;
    }

    /** A cover card: picture (the app's icon until setCover), title and a dim second line. */
    private View mediaCard(String titleText, String subText, boolean wide, Apps.App app, Runnable click) {
        int w = wide ? dp(110) : dp(76), h = wide ? dp(62) : dp(76);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(4), dp(4), dp(4), dp(6));
        card.setFocusable(true);
        card.setClickable(true);
        ImageView art = new ImageView(this);
        art.setClipToOutline(true);
        art.setBackground(Menus.rounded(0xFF1A1A1E, 0, dp(8), 0));
        art.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        art.setImageDrawable(appIcon(app.pkg));
        card.addView(art, new LinearLayout.LayoutParams(w, h));
        TextView title = text(10, 0xB3FFFFFF, 0.01f), sub = text(9, 0x80FFFFFF, 0.01f);
        title.setText(titleText);
        sub.setText(subText);
        for (TextView t : new TextView[] {title, sub}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(t, new LinearLayout.LayoutParams(w, -2));
        }
        ((LinearLayout.LayoutParams) title.getLayoutParams()).topMargin = dp(4);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.rightMargin = dp(4);
        card.setLayoutParams(clp);
        GradientDrawable ring = Menus.rounded(0, accent, dp(8), dp(2));
        card.setOnFocusChangeListener((v, focused) -> {
            art.setForeground(focused ? ring : null); // a thin accent edge on the picture, nothing more
            title.setTextColor(focused ? 0xFFFFFFFF : 0xB3FFFFFF);
            float sc = focused ? 1.06f : 1f;
            v.animate().scaleX(sc).scaleY(sc).setDuration(DOCK_ANIM).setInterpolator(new OvershootInterpolator(1.4f)).start();
        });
        card.setOnClickListener(v -> click.run());
        return card;
    }

    private static void setCover(View card, Bitmap cover) {
        ImageView art = (ImageView) ((ViewGroup) card).getChildAt(0);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImageBitmap(cover);
    }

    private void moveKey(int key) {
        int from = dock.indexOfChild(moving);
        int to = from;
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: to = Math.max(from - 1, 0); break;
            // "All apps" stays last.
            case KeyEvent.KEYCODE_DPAD_RIGHT: to = Math.min(from + 1, dock.getChildCount() - 2); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                endMove();
                return;
        }
        if (to == from) return;
        View moved = moving, other = dock.getChildAt(to);
        int movedLeft = moved.getLeft(), otherLeft = other.getLeft();
        dock.removeView(moved);
        dock.addView(moved, to);
        moved.requestFocus();
        dock.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                dock.getViewTreeObserver().removeOnPreDrawListener(this);
                // The two swapped slots: hold each icon where it was on screen, then let the arc
                // glide everything to the new order from there.
                holdInPlace(moved, movedLeft - moved.getLeft());
                holdInPlace(other, otherLeft - other.getLeft());
                updateDockScale(moved);
                return true;
            }
        });
    }

    /** Offsets an item's icon and name by how far its slot just jumped, so they don't jump with it. */
    private static void holdInPlace(View item, float jump) {
        for (int i = 0; i < 2; i++) {
            View c = ((ViewGroup) item).getChildAt(i);
            c.setTranslationX(c.getTranslationX() + jump);
        }
    }

    private void endMove() {
        List<String> order = new ArrayList<>();
        for (int i = 0; i < dock.getChildCount(); i++) {
            Object pkg = dock.getChildAt(i).getTag();
            if (pkg != null) order.add((String) pkg);
        }
        // Keep the ones that aren't installed (e.g. the other RxFilms build), at the end.
        for (String pkg : dockApps(this)) if (!order.contains(pkg)) order.add(pkg);
        saveDock(this, order);
        // Settle in place rather than rebuilding, which would replay the focus animation.
        moving.animate().translationY(0).setDuration(150).start();
        moving = null;
        for (int i = 0; i < dock.getChildCount(); i++) dock.getChildAt(i).setId(View.NO_ID);
        dock.getChildAt(dock.getChildCount() / 2).setId(middleDockId);
    }

    /**
     * While a dock app is focused it stands out: it grows and the rest of the dock shrinks back.
     * The icons close up, an even gap between them whatever their size, so the small ones don't
     * sit apart in slots sized for full-size icons. The focused one is kept mid-dock.
     */
    private void updateDockScale(View focused) {
        // A freshly rebuilt dock has no positions yet; packing it now would fling the icons apart.
        if (dock.isLayoutRequested()) {
            dock.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    dock.removeOnLayoutChangeListener(this);
                    // Posted: mid-layout the dock still reads as needing layout.
                    dock.post(() -> updateDockScale(dock.getFocusedChild()));
                }
            });
            return;
        }
        int n = dock.getChildCount(), f = dock.indexOfChild(focused);
        float[] scale = new float[n], center = new float[n];
        for (int i = 0; i < n; i++) scale[i] = f < 0 ? 1f : dockScale(Math.abs(i - f));
        float w = dp(68);
        if (f >= 0) {
            for (int i = 1; i < n; i++) {
                // Gaps tighten away from the focused app: roomy beside it, the far ones bunch up.
                int out = Math.max(Math.abs(i - f), Math.abs(i - 1 - f));
                float gap = dp(Math.max(0, 14 - 6 * (out - 1)));
                center[i] = center[i - 1] + w * (scale[i - 1] + scale[i]) / 2 + gap;
            }
            // The focused app always sits mid-dock; the row slides under it as focus moves.
            float dockMid = (slotCenter(0) + slotCenter(n - 1)) / 2;
            float offset = dockMid - center[f];
            for (int i = 0; i < n; i++) center[i] += offset;
        }
        for (int i = 0; i < n; i++) {
            float shift = f < 0 ? 0 : center[i] - slotCenter(i);
            // On a wheel: the further from the focused app, the lower it sits and the more it leans out.
            float r = dp(900); // the wheel's radius
            float dx = f < 0 || !arcDock() ? 0 : Math.max(-r * 0.95f, Math.min(r * 0.95f, center[i] - center[f]));
            float drop = r - (float) Math.sqrt(r * r - dx * dx);
            float lean = (float) Math.toDegrees(Math.asin(dx / r));
            ViewGroup item = (ViewGroup) dock.getChildAt(i);
            // The newly focused icon springs up a touch past its size; the rest glide.
            // Further out also fades back, so the eye settles on the middle.
            float fade = f < 0 ? 0.85f : i == f ? 1f : Math.max(0.4f, 0.9f - 0.15f * Math.abs(i - f));
            item.getChildAt(0).animate().scaleX(scale[i]).scaleY(scale[i]).alpha(fade).translationX(shift)
                .translationY(drop).rotation(lean).setDuration(DOCK_ANIM)
                .setInterpolator(i == f ? new OvershootInterpolator(1.6f) : new DecelerateInterpolator(1.5f)).start();
            item.getChildAt(1).animate().translationX(shift).setDuration(DOCK_ANIM)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start(); // the name follows
        }
    }

    /** Where the item's icon sits with no shift, in the dock's coordinates. */
    private float slotCenter(int i) {
        View item = dock.getChildAt(i);
        return item.getLeft() + item.getWidth() / 2f;
    }

    private static void scaleIcon(View item, float s) {
        ((ViewGroup) item).getChildAt(0).animate().scaleX(s).scaleY(s).setDuration(DOCK_ANIM)
            .setInterpolator(new DecelerateInterpolator(1.5f)).start();
    }

    private View dockItem(ImageView icon, String label, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.forceHasOverlappingRendering(false); // see the dock's: fades mustn't cut the shifted icon
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClipChildren(false);
        // Its side padding would otherwise clip the name, which hangs outside it.
        item.setClipToPadding(false);
        item.setPadding(dp(10), 0, dp(10), 0);
        item.setFocusable(true);
        // The dock's ends are walls: leaving it takes Up, so holding right doesn't run into the top buttons.
        item.setOnKeyListener((v, key, e) -> {
            int i = dock.indexOfChild(v);
            return key == KeyEvent.KEYCODE_DPAD_LEFT && i == 0
                || key == KeyEvent.KEYCODE_DPAD_RIGHT && i == dock.getChildCount() - 1;
        });
        item.setOnClickListener(click);

        TextView name = text(12, 0xFFFFFFFF, 0.02f);
        name.setText(label);
        name.setSingleLine(true);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x8C2A2A2E);
        pill.setCornerRadius(dp(8));
        name.setBackground(pill);
        name.setPadding(dp(10), 0, dp(10), 0);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setVisibility(View.INVISIBLE);

        // The name hangs below the icon into the dock's bottom margin: a negative margin as tall as
        // the name and its gap keeps it out of the layout, so the icons sit exactly where they did.
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(68), dp(68));
        ilp.topMargin = dp(36);
        item.addView(icon, ilp);
        // Grow and shrink from the bottom edge, so every icon keeps the same baseline.
        icon.setPivotX(dp(34));
        icon.setPivotY(dp(68));
        // The holder matches the item's width, so the name never changes the spacing; negative side
        // margins give long names room to spill past the icon.
        FrameLayout nameHolder = new FrameLayout(this);
        nameHolder.setClipChildren(false);
        nameHolder.addView(name, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, dp(24));
        nlp.setMargins(-dp(40), dp(8), -dp(40), -dp(32));
        item.addView(nameHolder, nlp);

        setGlow(icon, false, 1f);
        item.setOnFocusChangeListener((v, focused) -> {
            // Moving hops focus off the item and back at each step; keep the look steady.
            if (moving != null || popup != null) return; // keep the look steady under the card
            if (focused) {
                name.setVisibility(View.VISIBLE);
                name.setAlpha(0f);
                name.setTranslationY(-dp(8));
                name.animate().alpha(1f).translationY(0).setDuration(DOCK_ANIM).setInterpolator(new DecelerateInterpolator()).start();
            } else {
                name.animate().alpha(0f).setDuration(DOCK_ANIM / 2).withEndAction(() -> name.setVisibility(View.INVISIBLE)).start();
            }
            setGlow(icon, focused, DOCK_FOCUS_SCALE);
            // Straight to the size it ends at (focus usually passes to a neighbour), so it never
            // bounces through full size; once focus has settled, a dock left behind grows back.
            if (focused) updateDockScale(v);
            else {
                scaleIcon(v, dockScale(1));
                dock.post(() -> { if (dock.getFocusedChild() == null) updateDockScale(null); });
            }
        });
        return item;
    }

    /** Up from the dock goes to the media controls while they're showing, else to the gear. */
    private void linkDockUp() {
        int up = nowPlaying.getVisibility() == View.VISIBLE ? playPauseId : gearId;
        for (int i = 0; i < dock.getChildCount(); i++) dock.getChildAt(i).setNextFocusUpId(up);
    }

    private void focusDock() {
        if (dock.getChildCount() > 0) dock.getChildAt(dock.getChildCount() / 2).requestFocus();
    }

    // --- Wallpaper ---

    private void loadWallpaper() {
        final String asset = THEME_WALLPAPERS[theme];
        new Thread(() -> {
            Bitmap bmp = decode(asset, 1920);
            runOnUiThread(() -> {
                if (bmp != null) wallpaper.setImageBitmap(bmp);
                else wallpaper.setImageResource(R.drawable.default_wallpaper);
            });
        }).start();
    }

    /** Decodes an image no smaller than {@code minWidth} wide, to keep memory use low on the stick. */
    private Bitmap decode(String asset, int minWidth) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getAssets().open(asset)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= minWidth) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            try (InputStream in = getAssets().open(asset)) { return BitmapFactory.decodeStream(in, null, o); }
        } catch (IOException e) {
            return null;
        }
    }

    // --- Helpers ---

    private TextView text(float sp, int color, float spacing) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLetterSpacing(spacing);
        t.setShadowLayer(dp(6), 0, dp(1), 0x66000000);
        return t;
    }

    private View scrim(GradientDrawable.Orientation o, int color) {
        View v = new View(this);
        v.setBackground(new GradientDrawable(o, new int[] {color, 0x00000000}));
        return v;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams wrap(int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(l, t, r, b);
        return lp;
    }
}
