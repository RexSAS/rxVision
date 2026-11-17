package com.andrea.tvlauncher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Grid of every installed app, opened from the dock's "All apps" button. */
public class AppsActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final List<Apps.App> apps = Apps.all(this);
        final float density = getResources().getDisplayMetrics().density;
        final int pad = (int) (48 * density);

        GridView grid = new GridView(this);
        // On Home's blur when there is one, darkened so the names read; else the old near-black.
        Bitmap blur = Blur.forAllApps;
        Blur.forAllApps = null;
        if (blur != null) {
            BitmapDrawable bg = new BitmapDrawable(getResources(), blur);
            bg.setColorFilter(0x66000000, PorterDuff.Mode.SRC_ATOP);
            grid.setBackground(bg);
        } else {
            grid.setBackgroundColor(0xF20E0E12);
        }
        grid.setNumColumns(6);
        grid.setVerticalSpacing((int) (12 * density));
        grid.setHorizontalSpacing((int) (12 * density));
        grid.setPadding(pad, pad, pad, pad);
        grid.setClipToPadding(false);
        GradientDrawable selector = new GradientDrawable();
        int accent = MainActivity.accent(this);
        selector.setColor(accent & 0x40FFFFFF);
        selector.setStroke((int) (2 * density), accent);
        selector.setCornerRadius(16 * density);
        grid.setSelector(selector);
        grid.setDrawSelectorOnTop(false);

        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View convert, ViewGroup parent) {
                LinearLayout cell = (LinearLayout) convert;
                if (cell == null) {
                    cell = new LinearLayout(AppsActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    int p = (int) (12 * density);
                    cell.setPadding(p, p, p, p);
                    cell.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
                    ImageView icon = new ImageView(AppsActivity.this);
                    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    cell.addView(icon, new LinearLayout.LayoutParams((int) (64 * density), (int) (64 * density)));
                    TextView label = new TextView(AppsActivity.this);
                    label.setTextColor(0xFFFFFFFF);
                    label.setTextSize(13);
                    label.setSingleLine(true);
                    label.setEllipsize(TextUtils.TruncateAt.END);
                    label.setGravity(Gravity.CENTER);
                    label.setPadding(0, (int) (8 * density), 0, 0);
                    cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
                }
                Apps.App app = apps.get(i);
                ((ImageView) cell.getChildAt(0)).setImageDrawable(app.icon);
                ((TextView) cell.getChildAt(1)).setText(app.label);
                return cell;
            }
        });
        grid.setOnItemClickListener((parent, view, i, id) -> {
            Apps.start(this, apps.get(i).launch);
            finish();
        });

        grid.setOnItemLongClickListener((parent, view, i, id) -> {
            appMenu(apps.get(i));
            return true;
        });

        setContentView(grid);
    }

    /** Holding OK on an app. */
    private void appMenu(Apps.App app) {
        List<String> dock = MainActivity.dockApps(this);
        boolean docked = dock.contains(app.pkg);
        Map<String, Runnable> m = new LinkedHashMap<>();
        m.put(docked ? "Remove from dock" : "Add to dock", () -> {
            if (docked) dock.remove(app.pkg);
            else dock.add(app.pkg);
            MainActivity.saveDock(this, dock);
            Toast.makeText(this, docked ? "Removed from dock" : "Added to dock", Toast.LENGTH_SHORT).show();
        });
        m.put("More settings", () -> Apps.start(this, Apps.info(app.pkg)));
        Apps.menu(this, app.label, m);
    }
}
