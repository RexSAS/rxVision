package com.andrea.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.animation.TimeInterpolator;
import android.widget.FrameLayout;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Fire OS ignores the user's choice of home app and doesn't pass the Home key to
 * accessibility services, so this brings our launcher forward whenever Amazon's
 * home screen appears.
 *
 * The Philips TV remote keeps its own Home button, and Fire OS gives accessibility
 * services no key events at all, so the TV remote's red key stands in for Home: it
 * is spotted in the HDMI-CEC log. Needs, once:
 *   adb shell pm grant com.andrea.tvlauncher android.permission.READ_LOGS
 *
 * It also hides rxhome's scene changes behind a fade to black, or
 * behind the logo when a scene wakes the TV (see onScene), drawn as an accessibility overlay so
 * it covers whatever app the scene opens. rxhome fades the TV's sound itself (Fire OS gives apps
 * no volume over HDMI), following the "reveal" line this logs.
 */
public class HomeService extends AccessibilityService {

    private static final String STOCK_LAUNCHER = "com.amazon.tv.launcher";
    /** CEC <User Control Pressed> (0x44) with the red-key code (0x72), as Fire OS logs it. */
    private static final String TV_REMOTE_RED = "opcode: 0x44,size:2, param[0]=0x72,";

    /** The running service, so the launcher can ask it to put the stick to sleep. */
    private static HomeService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private long lastLaunch;
    /**
     * Five quick Home presses hand Home to Amazon's launcher until ours is opened again from its
     * app row. Amazon's home gives us no sign of further Home presses, so that's the way back.
     */
    static boolean stockMode;
    private int homePresses;
    private long lastHomePress;
    private boolean launchedSinceCount = true;
    private Process logcat;

    /** Scene broadcast from rxhome, sent over adb (see onScene). */
    private static final String SCENE = "com.andrea.rxhome.SCENE";
    /** After rxhome's "ready", the screen counts as settled once no new window has shown for this long. */
    private static final long SETTLE_MS = 2000;
    /** Into the new scene: slower than going black, where rxhome waits on us before changing the picture. */
    private static final long REVEAL_MS = 5000, LOGO_REVEAL_MS = 3000;
    /** Longest the cover stays without a "ready" (an older rxhome, or one that lost the connection). */
    private static final long MAX_HOLD_MS = 25000;
    /**
     * Eased, and even to the eye: the eye sees light on a ~2.2 power curve, so a plain alpha fade
     * looks like it jumps out of (or into) black. These spread the change evenly in perceived brightness.
     */
    private static final TimeInterpolator TO_BLACK = t -> 1 - (float) Math.pow(1 - ease(t), 2.2),
        FROM_BLACK = t -> (float) Math.pow(ease(t), 2.2);
    /**
     * The cover: black, holding the logo (a Splash) when a scene wakes the TV. Its window stays up, see-through,
     * until the screen goes off: adding or removing a window over the picture flashes the TV.
     */
    private FrameLayout cover;
    private Splash logo;
    private long notBefore, revealFadeMs;
    /** rxhome has run the scene's last step: reveal once the screen settles. */
    private boolean ready;
    /** rxhome's name for this "ready", logged with the reveal so it can bring the sound up in step. */
    private String readyId;
    private final Runnable reveal = this::reveal, lift = this::lift;
    private final BroadcastReceiver sceneReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) onScreenOn();
            else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) onScreenOff();
            else onScene(i);
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        new Thread(this::watchTvRemote, "tv-remote-watch").start();
        IntentFilter f = new IntentFilter(SCENE);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(sceneReceiver, f);

    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        unregisterReceiver(sceneReceiver);
        removeCover();
        if (logcat != null) logcat.destroy();
        super.onDestroy();
    }


    private void watchTvRemote() {
        try {
            // -T 1 skips the backlog so old presses don't fire on start.
            logcat = Runtime.getRuntime().exec(new String[] {"logcat", "-T", "1", "-s", "hdmi_cec:I"});
            BufferedReader in = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
            for (String line; (line = in.readLine()) != null; ) {
                if (line.contains(TV_REMOTE_RED)) main.post(this::goHome);
            }
        } catch (Exception e) {
            Log.w("HomeService", "TV remote watch stopped", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (revealFadeMs > 0) settleLater();
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        // Only Amazon's home screen; other screens in that package (e.g. profiles) stay usable.
        if (pkg != null && STOCK_LAUNCHER.contentEquals(pkg)
                && cls != null && cls.toString().contains("HomeActivity")) {
            // Amazon's home can report one press twice; only count it if we covered it since.
            long now = SystemClock.elapsedRealtime();
            if (now - lastHomePress > 2500) homePresses = 0;
            if (launchedSinceCount) {
                launchedSinceCount = false;
                lastHomePress = now;
                if (++homePresses == 5) stockMode = true;
            }
            goHome();
        }
    }

    @Override
    public void onInterrupt() {}

    /**
     * Puts the stick to sleep, as its power menu would; apps can't, but the lock-screen action
     * open to accessibility services does. False if the service isn't running.
     */
    static boolean sleep() {
        return instance != null && instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    /**
     * rxhome sends start / swap / off right before a scene first changes this TV's picture, then
     * waits fade_ms: fade fully to black over that, hold until rxhome says "ready" (the scene's last
     * step has run) and the screen has settled, then slowly fade the new screen in. If the TV is off
     * there's nothing to hide yet: it wakes to the logo, held the same way. tv_off: fade to black
     * over fade_ms before the TV goes off.
     */
    private void onScene(Intent i) {
        String event = String.valueOf(i.getStringExtra("event"));
        long fadeMs = Math.max(0, i.getIntExtra("fade_ms", 1500)), now = SystemClock.elapsedRealtime();
        Log.i("HomeService", "scene " + event);
        if (event.equals("ready")) {
            if (revealFadeMs == 0) return; // nothing held (the scene only switched the TV off)
            ready = true;
            readyId = i.getStringExtra("id");
            settleLater();
            return;
        }
        main.removeCallbacks(reveal);
        main.removeCallbacks(lift);
        if (event.equals("tv_off")) {
            revealFadeMs = 0;
            cover(false);
            fadeBlack(fadeMs);
            // The TV is off by now: clear the cover, unseen.
            main.postDelayed(lift, fadeMs + 5000);
            return;
        }
        boolean waking = !getSystemService(PowerManager.class).isInteractive();
        cover(waking);
        if (!waking) fadeBlack(fadeMs);
        revealFadeMs = waking ? LOGO_REVEAL_MS : REVEAL_MS;
        ready = false;
        readyId = null;
        // Never before fully black; waking, not before the logo has drawn either (onScreenOn).
        notBefore = now + fadeMs;
        main.postDelayed(reveal, MAX_HOLD_MS);
    }

    /** The stick woke: draw the logo on the cover, once the TV shows a picture. */
    private void onScreenOn() {
        if (logo == null) return;
        long delay = MainActivity.WAKE_DELAY + MainActivity.SPLASH_DELAY;
        logo.play(delay, null);
        notBefore = Math.max(notBefore, SystemClock.elapsedRealtime() + delay + Splash.DRAW_MS + 300);
    }

    /** Nothing to see now: the cover's window can go. Not while a scene holds it (one starting from off). */
    private void onScreenOff() {
        if (revealFadeMs == 0) removeCover();
    }

    /** After "ready", and on each new window after it: reveal once nothing has changed for SETTLE_MS. */
    private void settleLater() {
        if (!ready) return;
        main.removeCallbacks(reveal);
        main.postDelayed(reveal, SETTLE_MS);
    }

    /** Puts up the cover: with the logo (shown at once, the screen is off) or plain (faded in by fadeBlack). */
    private void cover(boolean withLogo) {
        if (cover == null) {
            cover = new FrameLayout(this);
            cover.setBackgroundColor(0xFF000000);
            cover.setAlpha(0f);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Never takes the remote's keys or touches; layout covers the bars too.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            getSystemService(WindowManager.class).addView(cover, lp);
        }
        if (withLogo && logo == null) {
            logo = new Splash(this, MainActivity.accent(this), MainActivity.font(this, "orbitron.ttf", 600));
            cover.addView(logo);
            cover.animate().cancel();
            cover.setAlpha(1f);
        } else if (!withLogo) {
            dropLogo();
        }
    }

    private void fadeBlack(long ms) {
        cover.animate().cancel();
        cover.animate().alpha(1f).setDuration(ms).setInterpolator(TO_BLACK).withEndAction(null);
    }

    private void reveal() {
        long wait = notBefore - SystemClock.elapsedRealtime();
        if (wait > 0) {
            main.postDelayed(reveal, wait);
            return;
        }
        long ms = revealFadeMs;
        revealFadeMs = 0;
        ready = false;
        Log.i("HomeService", "reveal " + readyId + " " + ms);
        if (cover != null) cover.animate().alpha(0f).setDuration(ms).setInterpolator(FROM_BLACK).withEndAction(this::dropLogo);
    }

    /** After tv_off: the TV is off, so at once. */
    private void lift() {
        if (cover != null) {
            cover.animate().cancel();
            cover.setAlpha(0f);
        }
    }

    private void dropLogo() {
        if (logo == null) return;
        cover.removeView(logo);
        logo = null;
    }

    private void removeCover() {
        if (cover == null) return;
        cover.animate().cancel();
        try {
            getSystemService(WindowManager.class).removeView(cover);
        } catch (IllegalArgumentException e) {
            // Its window is already gone.
        }
        cover = null;
        logo = null;
    }

    /** Smoothstep: starts and ends gently. */
    private static float ease(float t) {
        return t * t * (3 - 2 * t);
    }

    private void goHome() {
        if (stockMode) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 500) return;
        lastLaunch = now;
        launchedSinceCount = true;
        startActivity(new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }
}
