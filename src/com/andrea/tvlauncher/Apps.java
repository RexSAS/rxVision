package com.andrea.tvlauncher;

import android.content.Context;
import android.content.Intent;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Looks up launchable apps and starts them. */
final class Apps {

    static final class App {
        final String pkg;
        final String label;
        final Drawable icon;
        final Intent launch;

        App(String pkg, String label, Drawable icon, Intent launch) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.launch = launch;
        }
    }

    private Apps() {}

    /** Returns the app, or null if it isn't installed or can't be launched. */
    static App load(Context ctx, String pkg) {
        if (APPSTORE.equals(pkg)) return appstore(ctx);
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) return null;
        try {
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return new App(pkg, label.toString(), pm.getApplicationIcon(pkg), launch);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static final String APPSTORE = "com.amazon.venezia";

    /** Fire OS entries that are stubs, internal screens, or replaced by our own entries. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
        "com.amazon.tv.launcher",              // Amazon home (we redirect away from it)
        APPSTORE,                              // its launcher entry is "Your Apps"; see appstore()
        "com.amazon.firetv.troubleshooting",
        "com.amazon.tv.earlyaccess",
        "com.amazon.ssm",                      // System Status Monitor
        "com.amazon.whasettings",              // AmazonSettings
        "com.amazon.tv.ftvambient",            // Ambient Experience
        "com.amazon.ftv.screensaver",
        "com.amazon.ftv.profilepicker",
        "com.amazon.hedwig",                   // News: placeholder that opens its Appstore page
        "com.amazon.minitv.android.app"));     // miniTV: same

    /** The Amazon Appstore's storefront; its own launcher entry only opens the "Your Apps" grid. */
    static App appstore(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent launch = new Intent("com.amazon.venezia.ade.FEATURED_LAUNCH").setPackage(APPSTORE);
        if (pm.resolveActivity(launch, 0) == null) return null;
        try {
            return new App(APPSTORE, "Appstore", pm.getApplicationIcon(APPSTORE), launch);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The Appstore followed by every launchable app except hidden ones and this launcher, sorted by name. */
    static List<App> all(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, Boolean> pkgs = new LinkedHashMap<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                pkgs.put(ri.activityInfo.packageName, true);
            }
        }
        pkgs.remove(ctx.getPackageName());

        List<App> apps = new ArrayList<>();
        for (String pkg : pkgs.keySet()) {
            if (HIDDEN.contains(pkg)) continue;
            App app = load(ctx, pkg);
            if (app != null) apps.add(app);
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

        App store = appstore(ctx);
        if (store != null) apps.add(0, store);
        return apps;
    }

    static void start(Context ctx, Intent intent) {
        try {
            ctx.startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ActivityOptions.makeCustomAnimation(ctx, R.anim.open_enter, R.anim.open_exit).toBundle());
        } catch (RuntimeException e) {
            Toast.makeText(ctx, "Can't open this app", Toast.LENGTH_SHORT).show();
        }
    }

    /** The app's page in Fire OS Settings. */
    static Intent info(String pkg) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
    }

    /** A list of actions, as the long-press menu on an app. */
    static void menu(Activity a, String title, Map<String, Runnable> items) {
        String[] names = items.keySet().toArray(new String[0]);
        Runnable[] actions = items.values().toArray(new Runnable[0]);
        Menus.style(a, new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(names, (d, which) -> actions[which].run())
            .show());
    }
}
