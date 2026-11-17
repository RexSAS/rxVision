package com.andrea.tvlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.ImageView;

/**
 * Menus float on a blurred copy of the screen instead of Fire OS's grey panel and dimming.
 * Fire OS 8 is Android 11, which has no window blur, so the screen is snapshotted small,
 * blurred here and stretched back up behind the dialog.
 */
final class Blur {

    /** Shrink factor of the snapshot: both cheaper to blur and softer once stretched back. */
    private static final int SCALE = 6;

    private Blur() {}

    static void behind(Activity a, Dialog d) {
        d.getWindow().setBackgroundDrawable(new ColorDrawable(0));
        // Without this the now see-through dialog settles on an opaque surface: black over the blur.
        d.getWindow().setFormat(PixelFormat.TRANSLUCENT);
        // Fire OS paints its grey full-screen backdrop and the panel on the dialog's containers, over the blur.
        clearBackgrounds(d.getWindow().getDecorView());
        d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        ImageView shade = decor.findViewWithTag(Blur.class);
        if (shade == null) {
            Bitmap small = snapshot(a);
            if (small == null) return;
            shade = new ImageView(a);
            shade.setTag(Blur.class);
            shade.setScaleType(ImageView.ScaleType.FIT_XY);
            shade.setImageBitmap(small);
            // A light veil keeps the menu's white text readable on bright wallpapers.
            shade.setColorFilter(0x40000000);
            shade.setAlpha(0f);
            decor.addView(shade, new ViewGroup.LayoutParams(-1, -1));
            shade.animate().alpha(1f).setDuration(250).start();
        }
        // Menus opened from menus share one blur; it goes when the last one closes.
        ImageView s = shade;
        s.setTag(R.id.blur_count, count(s) + 1);
        d.setOnDismissListener(x -> {
            int left = count(s) - 1;
            s.setTag(R.id.blur_count, left);
            // Posted: a menu that opens another closes first, so give the next one a moment to claim it.
            s.post(() -> {
                if (count(s) == 0) s.animate().alpha(0f).setDuration(200).withEndAction(() -> decor.removeView(s)).start();
            });
        });
    }

    /** Strips the backgrounds of the dialog's containers; buttons and the list's highlights keep theirs. */
    private static void clearBackgrounds(View v) {
        if (!(v instanceof ViewGroup) || v instanceof AbsListView) return;
        v.setBackground(null);
        for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) clearBackgrounds(((ViewGroup) v).getChildAt(i));
    }

    /** A small blurred copy of the activity's screen, to stretch back up; null before it's laid out. */
    static Bitmap snapshot(Activity a) {
        View content = a.findViewById(android.R.id.content);
        if (content.getWidth() == 0) return null;
        Bitmap small = Bitmap.createBitmap(content.getWidth() / SCALE, content.getHeight() / SCALE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        c.scale(1f / SCALE, 1f / SCALE);
        content.draw(c);
        blur(small, 2);
        return small;
    }

    /** Home's blurred screen, handed to the All apps page as it opens over it. */
    static Bitmap forAllApps;

    private static int count(View v) {
        Object n = v.getTag(R.id.blur_count);
        return n == null ? 0 : (Integer) n;
    }

    /** Three box-blur passes each way, which together look close to a Gaussian blur. */
    static void blur(Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            box(px, tmp, w, h, r, 1, w);  // rows
            box(tmp, px, h, w, r, w, 1);  // columns
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    /** Averages each pixel with r neighbours either side along lines of n pixels, `step` apart. */
    private static void box(int[] in, int[] out, int n, int lines, int r, int step, int lineStep) {
        for (int line = 0; line < lines; line++) {
            int base = line * lineStep;
            for (int i = 0; i < n; i++) {
                int a = 0, red = 0, g = 0, bl = 0, count = 0;
                for (int k = Math.max(0, i - r); k <= Math.min(n - 1, i + r); k++) {
                    int p = in[base + k * step];
                    a += p >>> 24; red += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; bl += p & 0xFF;
                    count++;
                }
                out[base + i * step] = (a / count) << 24 | (red / count) << 16 | (g / count) << 8 | bl / count;
            }
        }
    }
}
