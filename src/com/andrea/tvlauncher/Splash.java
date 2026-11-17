package com.andrea.tvlauncher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;

/**
 * The RxHome logo, drawn live: a ring in the accent draws itself, a roof rises inside it, the
 * name spells in under it, then the whole thing lifts away into Home.
 */
final class Splash extends View {

    private static final String NAME = "RxHome";
    private static final int ACCENT_CHARS = 2; // "Rx" in the accent, "Home" in white

    private final int accent;
    private final float dp;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), word = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path roof = new Path(), roofPart = new Path();
    private final RectF oval = new RectF();
    private PathMeasure roofMeasure;
    private float t; // 0..1 through the drawing

    Splash(Context c, int accent, Typeface face) {
        super(c);
        this.accent = accent;
        dp = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setStrokeJoin(Paint.Join.ROUND);
        ring.setStrokeWidth(4 * dp);
        ring.setColor(accent);
        ring.setShadowLayer(14 * dp, 0, 0, accent);
        word.setTypeface(face);
        word.setTextSize(30 * dp);
        word.setLetterSpacing(0.18f);
        word.setShadowLayer(10 * dp, 0, 0, 0x66000000);
    }

    private boolean played;

    /** After `delay`, runs the drawing, then fades and lifts the logo away and calls done once it's gone. */
    void play(long delay, Runnable done) {
        if (played) return;
        played = true;
        ValueAnimator draw = ValueAnimator.ofFloat(0f, 1f).setDuration(1700);
        draw.setStartDelay(delay);
        draw.setInterpolator(new LinearInterpolator()); // each part eases itself below
        draw.addUpdateListener(a -> {
            t = (float) a.getAnimatedValue();
            invalidate();
        });
        draw.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                animate().alpha(0f).scaleX(1.08f).scaleY(1.08f).setStartDelay(250).setDuration(650)
                    .withEndAction(() -> {
                        ((ViewGroup) getParent()).removeView(Splash.this);
                        done.run();
                    }).start();
            }
        });
        draw.start();
    }

    /** How far a part that runs from `start` for `length` (fractions of t) has got, eased out. */
    private float part(float start, float length) {
        float x = Math.max(0f, Math.min(1f, (t - start) / length));
        return 1f - (1f - x) * (1f - x) * (1f - x);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(0xFF07070A);
        float cx = getWidth() / 2f, cy = getHeight() / 2f - 30 * dp, r = 62 * dp;

        // The ring sweeps round from the top.
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(oval, -90, 360 * part(0f, 0.45f), false, ring);

        // A roof inside it, drawn from left eave over the peak to the right.
        if (roofMeasure == null || roof.isEmpty()) {
            roof.reset();
            roof.moveTo(cx - r * 0.48f, cy + r * 0.22f);
            roof.lineTo(cx, cy - r * 0.30f);
            roof.lineTo(cx + r * 0.48f, cy + r * 0.22f);
            roofMeasure = new PathMeasure(roof, false);
        }
        roofPart.reset();
        roofMeasure.getSegment(0, roofMeasure.getLength() * part(0.25f, 0.35f), roofPart, true);
        c.drawPath(roofPart, ring);

        // The name, letter by letter, each rising into place.
        float total = word.measureText(NAME), x = cx - total / 2f, base = cy + r + 64 * dp;
        for (int i = 0; i < NAME.length(); i++) {
            String ch = NAME.substring(i, i + 1);
            float p = part(0.45f + i * 0.06f, 0.3f);
            word.setColor(i < ACCENT_CHARS ? accent : 0xFFFFFFFF);
            word.setAlpha(Math.round(255 * p));
            c.drawText(ch, x, base + (1f - p) * 14 * dp, word);
            x += word.measureText(ch);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        roof.reset(); // re-centre the roof for the new size
    }
}
