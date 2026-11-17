package com.andrea.tvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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

    @Override
    protected void onServiceConnected() {
        instance = this;
        new Thread(this::watchTvRemote, "tv-remote-watch").start();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
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
