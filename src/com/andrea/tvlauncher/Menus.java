package com.andrea.tvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;

/** Dresses Fire OS's stock dialogs as the launcher's menus: on a blur, in the theme's accent. */
final class Menus {

    private Menus() {}

    /** Call right after show(). */
    static void style(Activity a, AlertDialog d) {
        Blur.behind(a, d);
        int accent = MainActivity.accent(a);
        float dp = a.getResources().getDisplayMetrics().density;

        ListView list = d.getListView();
        if (list != null) {
            list.setSelector(rounded(accent & 0x40FFFFFF, accent, 10 * dp, (int) (2 * dp)));
            list.setDivider(new ColorDrawable(accent & 0x30FFFFFF));
            list.setDividerHeight(Math.max(1, (int) dp));
            // Rows are made as the list scrolls; tint each one's checkmark as it arrives.
            list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override public void onChildViewAdded(View parent, View row) {
                    if (row instanceof CheckedTextView) ((CheckedTextView) row).setCheckMarkTintList(ColorStateList.valueOf(accent));
                }
                @Override public void onChildViewRemoved(View parent, View row) {}
            });
        }

        for (int which : new int[] {AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE}) {
            Button b = d.getButton(which);
            if (b != null && b.getText().length() > 0) pill(b, accent, dp);
        }
    }

    /** Pill buttons: an accent outline at rest, an accent wash when focused; white text either way. */
    static void pill(Button b, int accent, float dp) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, rounded(accent & 0x80FFFFFF, accent, 40 * dp, (int) (2 * dp)));
        bg.addState(new int[] {}, rounded(0x99101014, accent, 40 * dp, (int) (2 * dp)));
        b.setBackground(bg);
        b.setTextColor(0xFFFFFFFF);
        b.setAllCaps(false);
        b.setLetterSpacing(0.05f);
        b.setStateListAnimator(null); // no stock press shadow
        if (b.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.setMargins((int) (8 * dp), 0, (int) (8 * dp), 0);
            b.setLayoutParams(lp);
        }
        b.setOnFocusChangeListener((v, focused) -> {
            float s = focused ? 1.06f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(MainActivity.DOCK_ANIM).setInterpolator(new OvershootInterpolator(1.6f)).start();
        });
    }

    static GradientDrawable rounded(int fill, int stroke, float radius, int strokeWidth) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        g.setStroke(strokeWidth, stroke);
        return g;
    }
}
