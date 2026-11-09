package com.redline.launcher;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * CarPlay-style apps page that sits to the right of the home screen: pages of 6 x 3 large
 * app icons over a frosted copy of the wallpaper, page dots at the bottom, and sideways
 * swiping between pages. Dragging right on the first page hands the gesture back to the
 * activity so it can slide home back in. Laid out on the same 2000 x 1200 design grid as
 * HomeView and scaled to the real screen.
 */
public class AppsPageView extends View {

    public interface Callback {
        void onLaunch(Slots.AppEntry app);
        void onAppInfo(Slots.AppEntry app);
        /** Finger dragging right past the first page: how far, in screen pixels. */
        void onDragClose(float dxPx);
        void onDragCloseEnd(float dxPx, float velocityPx);
    }

    // ---- layout (design px on the 2000 x 1200 grid)
    static final int COLS = 6, ROWS = 3, PER_PAGE = COLS * ROWS;
    static final float SIDE_PAD = 130, TOP_PAD = 70, GRID_TOP = 262, ROW_H = 258;
    static final float ICON = 160, LABEL_SIZE = 28, LABEL_GAP = 18;
    static final float TITLE_SIZE = 64, COUNT_SIZE = 27;
    static final float DOTS_Y = 1048, DOT_R = 6.5f, DOT_GAP = 28;
    static final float FLING_VELOCITY = 900;   // px/s that counts as a flick to the next page
    static final float PAGE_TURN = 0.3f;       // dragging this share of the screen turns the page
    static final long SNAP_MS = 280;
    // ---- background
    static final int BLUR_DOWNSCALE = 18;      // bigger = blurrier
    static final int TINT = 0x6B12030A;        // dark wash over the blurred wallpaper

    private final Callback cb;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final HashMap<ComponentName, Drawable> icons = new HashMap<ComponentName, Drawable>();
    private List<Slots.AppEntry> apps = new ArrayList<Slots.AppEntry>();
    private Bitmap wallpaper, blurred;
    private float s = 1f, offX, offY;

    private float scrollX;                     // design px; page n starts at n * DW
    private ValueAnimator snap;

    // touch
    private final int slop;
    private VelocityTracker vt;
    private float downX, downY, startScroll;
    private boolean dragging, closing, longFired;
    private int pressed = -1;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            if (pressed < 0) return;
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Slots.AppEntry a = apps.get(pressed);
            pressed = -1;
            invalidate();
            cb.onAppInfo(a);
        }
    };

    public AppsPageView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        tp.setColor(Color.WHITE);
        tp.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------ public

    /** Load the app list and go back to the first page. Called each time the page starts to open. */
    public void prepare(List<Slots.AppEntry> list) {
        apps = list;
        icons.clear();
        if (snap != null) snap.cancel();
        scrollX = 0;
        pressed = -1;
        invalidate();
    }

    public void setWallpaper(Bitmap b) {
        wallpaper = b;
        rebuildBlur();
        invalidate();
    }

    private int pages() { return Math.max(1, (apps.size() + PER_PAGE - 1) / PER_PAGE); }
    private float maxScroll() { return (pages() - 1) * HomeView.DW; }

    // ------------------------------------------------------------------ layout / background

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        s = Math.min(w / HomeView.DW, h / HomeView.DH);
        offX = (w - HomeView.DW * s) / 2f;
        offY = (h - HomeView.DH * s) / 2f;
        rebuildBlur();
    }

    private void rebuildBlur() {
        int w = getWidth(), h = getHeight();
        if (wallpaper == null || w == 0 || h == 0) return;
        float target = w / (float) h, src = wallpaper.getWidth() / (float) wallpaper.getHeight();
        int cw = wallpaper.getWidth(), ch = wallpaper.getHeight(), cx = 0, cy = 0;
        if (src > target) { cw = Math.round(ch * target); cx = (wallpaper.getWidth() - cw) / 2; }
        else { ch = Math.round(cw / target); cy = (wallpaper.getHeight() - ch) / 2; }
        Bitmap crop = Bitmap.createBitmap(wallpaper, cx, cy, cw, ch);
        Bitmap small = Bitmap.createScaledBitmap(crop, Math.max(2, w / BLUR_DOWNSCALE), Math.max(2, h / BLUR_DOWNSCALE), true);
        if (crop != wallpaper) crop.recycle();
        blurred = HomeView.boxBlur(HomeView.boxBlur(HomeView.boxBlur(small, 2), 2), 2);
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        p.setShader(null);
        p.setColor(Color.WHITE);
        if (blurred != null) c.drawBitmap(blurred, null, new android.graphics.RectF(0, 0, w, h), p);
        else c.drawColor(0xFF2A0508);
        c.drawColor(TINT);
        p.setShader(new LinearGradient(0, 0, 0, offY + 320 * s, 0x59000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, offY + 320 * s, p);
        p.setShader(new LinearGradient(0, h - 260 * s, 0, h, 0x00000000, 0x4D000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h - 260 * s, w, h, p);
        p.setShader(null);

        c.save();
        c.translate(offX, offY);
        c.scale(s, s);

        // header
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tp.setTextSize(TITLE_SIZE);
        tp.setColor(Color.WHITE);
        tp.setShadowLayer(12, 0, 3, 0x66000000);
        c.drawText("Apps", SIDE_PAD, TOP_PAD + TITLE_SIZE * 0.95f, tp);
        tp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tp.setTextSize(COUNT_SIZE);
        tp.setLetterSpacing(0.08f);
        tp.setColor(0xB3FFFFFF);
        c.drawText(apps.size() == 1 ? "1 APP" : apps.size() + " APPS", SIDE_PAD, TOP_PAD + TITLE_SIZE * 1.15f + COUNT_SIZE, tp);
        tp.setLetterSpacing(0f);
        p.setColor(0x26FFFFFF);
        c.drawRect(SIDE_PAD, GRID_TOP - 40, HomeView.DW - SIDE_PAD, GRID_TOP - 38.5f, p);

        // icons, page by page, shifted by the current scroll
        float colW = (HomeView.DW - 2 * SIDE_PAD) / COLS;
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(LABEL_SIZE);
        tp.setColor(Color.WHITE);
        tp.setShadowLayer(8, 0, 2, 0x99000000);
        PackageManagerHolder pm = new PackageManagerHolder();
        for (int i = 0; i < apps.size(); i++) {
            int page = i / PER_PAGE, idx = i % PER_PAGE;
            float cx = page * HomeView.DW - scrollX + SIDE_PAD + colW * (idx % COLS + 0.5f);
            if (cx < -colW || cx > HomeView.DW + colW) continue;
            float top = GRID_TOP + (idx / COLS) * ROW_H;
            Slots.AppEntry a = apps.get(i);
            c.save();
            if (i == pressed) c.scale(0.92f, 0.92f, cx, top + ICON / 2f);
            Drawable d = icons.get(a.cn);
            if (d == null) { d = a.ri.loadIcon(pm.get()); icons.put(a.cn, d); }
            int l = Math.round(cx - ICON / 2f), t = Math.round(top);
            d.setBounds(l, t, l + Math.round(ICON), t + Math.round(ICON));
            d.draw(c);
            c.restore();
            String label = TextUtils.ellipsize(a.label, tp, colW - 16, TextUtils.TruncateAt.END).toString();
            c.drawText(label, cx, top + ICON + LABEL_GAP + LABEL_SIZE * 0.95f, tp);
        }
        tp.clearShadowLayer();

        // page dots
        int n = pages();
        if (n > 1) {
            float at = scrollX / HomeView.DW;
            float x0 = HomeView.DW / 2f - (n - 1) * DOT_GAP / 2f;
            for (int k = 0; k < n; k++) {
                float near = Math.max(0f, 1f - Math.abs(at - k));           // 1 on the current page
                p.setColor(Color.WHITE);
                p.setAlpha(Math.round(255 * (0.35f + 0.65f * near)));
                c.drawCircle(x0 + k * DOT_GAP, DOTS_Y, DOT_R, p);
            }
            p.setAlpha(255);
        }
        c.restore();
    }

    /** Lazily fetches the package manager once per frame. */
    private class PackageManagerHolder {
        android.content.pm.PackageManager pm;
        android.content.pm.PackageManager get() {
            if (pm == null) pm = getContext().getPackageManager();
            return pm;
        }
    }

    // ------------------------------------------------------------------ touch

    private float dx(float px) { return (px) / s; }

    private int hit(float x, float y) {
        float dxp = (x - offX) / s, dyp = (y - offY) / s;
        float colW = (HomeView.DW - 2 * SIDE_PAD) / COLS;
        int page = Math.round(scrollX / HomeView.DW);
        int col = (int) Math.floor((dxp - SIDE_PAD) / colW);
        int row = (int) Math.floor((dyp - GRID_TOP) / ROW_H);
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;
        float inRow = dyp - GRID_TOP - row * ROW_H;
        if (inRow > ICON + LABEL_GAP + LABEL_SIZE * 1.5f) return -1;
        int i = page * PER_PAGE + row * COLS + col;
        return i < apps.size() ? i : -1;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (snap != null) snap.cancel();
                if (vt != null) vt.recycle();
                vt = VelocityTracker.obtain();
                vt.addMovement(e);
                downX = e.getX(); downY = e.getY();
                startScroll = scrollX;
                dragging = closing = longFired = false;
                pressed = hit(e.getX(), e.getY());
                if (pressed >= 0) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (vt != null) vt.addMovement(e);
                float mx = e.getX() - downX, my = e.getY() - downY;
                if (!dragging && Math.abs(mx) > slop && Math.abs(mx) > Math.abs(my)) {
                    dragging = true;
                    handler.removeCallbacks(longPress);
                    pressed = -1;
                    downX = e.getX();          // start the drag from here so nothing jumps
                    mx = 0;
                }
                if (!dragging) {
                    if (Math.abs(my) > slop) { handler.removeCallbacks(longPress); pressed = -1; invalidate(); }
                    return true;
                }
                float target = startScroll - dx(mx);
                if (target < 0) {                       // past the first page: slide home back in
                    scrollX = 0;
                    closing = true;
                    cb.onDragClose(-target * s);
                } else {
                    if (closing) { closing = false; cb.onDragClose(0); }
                    float max = maxScroll();
                    scrollX = target > max ? max + (target - max) * 0.3f : target;   // soft edge after the last page
                }
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPress);
                float vx = 0;
                if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                if (dragging) {
                    if (closing) {
                        cb.onDragCloseEnd(Math.max(0, (e.getX() - downX) - startScroll * s), vx);
                        closing = false;
                    } else {
                        snapTo(pageFor(vx));
                    }
                } else if (pressed >= 0 && !longFired) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    final Slots.AppEntry a = apps.get(pressed);
                    pressed = -1;
                    invalidate();
                    cb.onLaunch(a);
                }
                dragging = false;
                pressed = -1;
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (vt != null) { vt.recycle(); vt = null; }
                if (closing) cb.onDragCloseEnd(0, 0);
                else if (dragging) snapTo(Math.round(scrollX / HomeView.DW));
                dragging = closing = false;
                pressed = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private int pageFor(float vx) {
        int from = Math.round(startScroll / HomeView.DW);
        float moved = scrollX / HomeView.DW - from;   // + = toward the next page
        int page = from;
        if (moved > PAGE_TURN || (vx < -FLING_VELOCITY && moved > 0.02f)) page = from + 1;
        else if (moved < -PAGE_TURN || (vx > FLING_VELOCITY && moved < -0.02f)) page = from - 1;
        return Math.max(0, Math.min(pages() - 1, page));
    }

    private void snapTo(int page) {
        if (snap != null) snap.cancel();
        snap = ValueAnimator.ofFloat(scrollX, page * HomeView.DW);
        snap.setDuration(SNAP_MS);
        snap.setInterpolator(new DecelerateInterpolator(1.8f));
        snap.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                scrollX = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        snap.start();
    }
}
