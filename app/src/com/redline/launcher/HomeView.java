package com.redline.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Calendar;

/**
 * Draws the whole home screen. Everything is laid out on a 2000 x 1200 design
 * canvas (the head unit's native resolution) and scaled to fit the real screen,
 * so it matches the mockup pixel for pixel on a 2000x1200 display.
 */
public class HomeView extends View {

    public interface Host {
        void onSlotTap(String slot);
        void onSlotLongPress(String slot);
        void onAppsTap();
        void onClockTap();
        void onBackgroundLongPress();
        void onMusicInfoTap();
        void onSpeedTap();
        void onCarLongPress();
    }

    static final float DW = 2000f, DH = 1200f;

    // ---- layout (design pixels) ------------------------------------------------
    static final RectF SIDEBAR = new RectF(-60, 198, 215, 905);
    static final RectF APPS = new RectF(-60, 880, 215, 1105);
    // Beveled outlines (design px): top/bottom edges slant all the way to the screen edge, every bend rounded by BEVEL_RADIUS.
    // The left edge sits off-screen so only the right side shows.
    static final float[] SIDEBAR_SHAPE = {-60, 198, 215, 290, 215, 850, -60, 905};
    static final float[] APPS_SHAPE = {-60, 935, 215, 880, 215, 1105, -60, 1105};
    static final float BEVEL_RADIUS = 16f;
    static final RectF CLOCK = new RectF(290, 188, 780, 435);
    static final RectF MUSIC = new RectF(290, 455, 780, 1105);
    static final RectF ART = new RectF(375, 490, 695, 810);   // album cover, 320 px square
    static final float TITLE_Y = 857f, ARTIST_Y = 886f;
    static final float SIDE_X = 117f;
    static final float[] SIDE_Y = {330f, 487f, 643f, 797f};
    static final String[] SIDE_SLOTS = {Slots.SIDE_SETTINGS, Slots.SIDE_CAMERA, Slots.SIDE_BT, Slots.SIDE_NAV};
    static final float PROG_Y = 917f, PROG_X0 = 335f, PROG_X1 = 740f;
    static final float CTRL_Y = 1000f;
    static final float[] CTRL_X = {388f, 537f, 685f};
    static final float[] TILE_X = {913f, 1115f, 1322f, 1541f, 1775f};
    static final float TILE_Y = 1017f, TILE_SIZE = 168f;
    static final String[] TILE_SLOTS = {Slots.TILE_LINK, Slots.TILE_VIDEO, Slots.TILE_EQ, Slots.TILE_MUSIC, Slots.TILE_YT};
    // Tile gradients from the icon mockups: {start, end}. Diagonal top-left -> bottom-right,
    // except the music tile, which runs left -> right.
    static final int[][] TILE_COLORS = {
            {0xFFF861B9, 0xFFF88B44},   // Car Link: pink -> orange
            {0xFF00A874, 0xFF1B6FC0},   // Front Video: green -> blue
            {0xFF5E00A2, 0xFFEE14D6},   // Equalizer: deep purple -> magenta
            {0xFF009B98, 0xFF00A350},   // Music: teal -> green
            {0xFFF51F44, 0xFFF8763C},   // YouTube: red -> orange
    };
    static final boolean[] TILE_HORIZONTAL = {false, false, false, true, false};
    static final int TEAL = 0xFF45D6B4;

    // ---- speed, drawn in the middle of the wallpaper's moon
    static final float MOON_CX = 980f, MOON_CY = 282f, MOON_R = 168f;
    // where the car sits in the wallpaper (long-press here for Apply Wrap)
    static final RectF CAR = new RectF(905, 420, 1880, 820);
    static final int SPEED_GLOW = 0xFFFF1E46;   // red outer glow around the speed
    static final float SPEED_GLOW_RADIUS = 30f;
    static final int SPEED_GLOW_PASSES = 3;      // more passes = brighter glow
    static final float SPEED_SIZE = 150f, SPEED_Y = 318f, MPH_SIZE = 34f, MPH_Y = 370f;

    // ---- glass panel look (0 = invisible, 255 = solid). Lower = more see-through.
    // ---- Apple-style glass (same numbers as preview/index.html)
    static final int GLASS_BLUR_DOWNSCALE = 5;     // light, smooth blur: clear, not foggy
    static final float GLASS_SATURATION = 1.1f;    // close to neutral...
    static final float GLASS_BRIGHTNESS = 0.9f;    // ...and slightly dimmed so white text stays crisp
    static final int GLASS_TINT = 0x12FFFFFF;      // faint luminous lift, like real glass
    static final float GLASS_RADIUS = 40f;         // larger, softer corners
    static final float LENS_BAND = 22f, LENS_ZOOM = 1.08f; // edge refraction: the rim magnifies what's behind...
    static final int LENS_STEPS = 3;               // ...easing to none toward the middle
    static final float RIM_WIDTH = 1.8f, RIM_GLOW = 9f;   // crisp specular rim + soft inner glow
    static final int SHADOW = 0x61000000;          // soft floating shadow
    static final float SHADOW_BLUR = 22f, SHADOW_Y = 14f;
    // rim brightness around the panel, clockwise from 3 o'clock: bright at bottom-right and top-left
    static final float[] RIM_POS = {0f, .125f, .25f, .5f, .625f, .75f, 1f};
    static final float[] RIM_ALPHA = {.30f, .85f, .18f, .22f, 1f, .22f, .30f};
    static final android.graphics.ColorMatrixColorFilter GLASS_FILTER = glassFilter();

    private static android.graphics.ColorMatrixColorFilter glassFilter() {
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
        cm.setSaturation(GLASS_SATURATION);
        float b = GLASS_BRIGHTNESS;
        cm.postConcat(new android.graphics.ColorMatrix(new float[]{
                b, 0, 0, 0, 0,   0, b, 0, 0, 0,   0, 0, b, 0, 0,   0, 0, 0, 1, 0}));
        return new android.graphics.ColorMatrixColorFilter(cm);
    }
    static final int APPS_TINT = 0x2EE0242C;   // red wash on the four-dot button

    private final Host host;
    private final MediaHelper media;
    private final SpeedHelper speed;

    private Bitmap wallpaper, wallScaled, blur, blurLight;
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix lensMatrix = new Matrix();
    private final Path lensPath = new Path();
    private float scale = 1f, offX, offY;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    // touch state
    // four-dot button spin animation
    static final long APPS_SPIN_MS = 520;      // length of the spin
    static final long APPS_OPEN_DELAY_MS = 380; // when the app grid opens, during the spin
    private long appsSpinStart = -1;

    private String pressed;          // id of the element under the finger
    private float downX, downY;
    private boolean longFired, seeking;
    private final int slop;
    private final Runnable longPress = new Runnable() {
        @Override public void run() {
            longFired = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            String id = pressed;
            pressed = null;
            invalidate();
            if (id == null || id.equals("bg")) host.onBackgroundLongPress();
            else if (id.equals("car")) host.onCarLongPress();
            else if (id.startsWith("side_") || id.startsWith("tile_")) host.onSlotLongPress(id);
            else host.onBackgroundLongPress();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            invalidate();
            handler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    public HomeView(Context c, Host host, MediaHelper media, SpeedHelper speed) {
        super(c);
        this.host = host;
        this.media = media;
        this.speed = speed;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        text.setColor(Color.WHITE);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setWallpaper(Bitmap b) {
        wallpaper = b;
        rebuildBackground();
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(ticker);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(ticker);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        scale = Math.min(w / DW, h / DH);
        offX = (w - DW * scale) / 2f;
        offY = (h - DH * scale) / 2f;
        rebuildBackground();
    }

    /** Centre-crop the wallpaper to the screen and make a blurred copy for the glass panels. */
    private void rebuildBackground() {
        int w = getWidth(), h = getHeight();
        if (wallpaper == null || w == 0 || h == 0) return;
        float s = Math.max(w / (float) wallpaper.getWidth(), h / (float) wallpaper.getHeight());
        int sw = Math.round(wallpaper.getWidth() * s), sh = Math.round(wallpaper.getHeight() * s);
        Bitmap scaled = Bitmap.createScaledBitmap(wallpaper, sw, sh, true);
        int x = (sw - w) / 2, y = (sh - h) / 2;
        wallScaled = Bitmap.createBitmap(scaled, Math.max(0, x), Math.max(0, y), Math.min(w, sw), Math.min(h, sh));
        if (scaled != wallScaled && scaled != wallpaper) scaled.recycle();
        Bitmap small = Bitmap.createScaledBitmap(wallScaled, Math.max(1, w / 12), Math.max(1, h / 12), true);
        blur = boxBlur(boxBlur(small, 3), 3);
        Bitmap light = Bitmap.createScaledBitmap(wallScaled, Math.max(1, w / GLASS_BLUR_DOWNSCALE),
                Math.max(1, h / GLASS_BLUR_DOWNSCALE), true);
        blurLight = boxBlur(boxBlur(light, 3), 3);
    }

    static Bitmap boxBlur(Bitmap src, int r) {
        int w = src.getWidth(), h = src.getHeight();
        int[] in = new int[w * h], out = new int[w * h];
        src.getPixels(in, 0, w, 0, 0, w, h);
        // horizontal then vertical pass
        for (int pass = 0; pass < 2; pass++) {
            int[] a = pass == 0 ? in : out, b = pass == 0 ? out : in;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rs = 0, gs = 0, bs = 0, n = 0;
                    for (int k = -r; k <= r; k++) {
                        int xx = pass == 0 ? Math.min(w - 1, Math.max(0, x + k)) : x;
                        int yy = pass == 0 ? y : Math.min(h - 1, Math.max(0, y + k));
                        int c = a[yy * w + xx];
                        rs += (c >> 16) & 0xff; gs += (c >> 8) & 0xff; bs += c & 0xff; n++;
                    }
                    b[y * w + x] = 0xff000000 | ((rs / n) << 16) | ((gs / n) << 8) | (bs / n);
                }
            }
        }
        Bitmap res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        res.setPixels(in, 0, w, 0, 0, w, h);
        return res;
    }

    // ============================================================== drawing

    @Override protected void onDraw(Canvas c) {
        if (wallScaled != null) c.drawBitmap(wallScaled, 0, 0, p);
        else c.drawColor(0xFF2A0508);

        c.save();
        c.translate(offX, offY);
        c.scale(scale, scale);

        drawSpeed(c);

        glass(c, roundedPolygon(SIDEBAR_SHAPE, BEVEL_RADIUS), SIDEBAR, 0);
        glass(c, roundedPolygon(APPS_SHAPE, BEVEL_RADIUS), APPS, APPS_TINT);
        glass(c, roundRect(CLOCK, GLASS_RADIUS), CLOCK, 0);
        glass(c, roundRect(MUSIC, GLASS_RADIUS), MUSIC, 0);

        drawSidebar(c);
        drawAppsButton(c);
        drawClock(c);
        drawMusic(c);
        for (int i = 0; i < TILE_X.length; i++) drawTile(c, i);

        c.restore();
    }

    private static Path roundRect(RectF r, float radius) {
        Path out = new Path();
        out.addRoundRect(r, radius, radius, Path.Direction.CW);
        return out;
    }

    /** Polygon (x,y pairs) whose corners are smoothed with curves of the given radius. */
    static Path roundedPolygon(float[] pts, float radius) {
        Path out = new Path();
        int n = pts.length / 2;
        for (int i = 0; i < n; i++) {
            float px = pts[((i - 1 + n) % n) * 2], py = pts[((i - 1 + n) % n) * 2 + 1];
            float cx = pts[i * 2], cy = pts[i * 2 + 1];
            float nx = pts[((i + 1) % n) * 2], ny = pts[((i + 1) % n) * 2 + 1];
            float d1 = (float) Math.hypot(cx - px, cy - py), d2 = (float) Math.hypot(nx - cx, ny - cy);
            float r = Math.min(radius, Math.min(d1, d2) / 2f);
            float ax = cx + (px - cx) * r / d1, ay = cy + (py - cy) * r / d1;
            float bx = cx + (nx - cx) * r / d2, by = cy + (ny - cy) * r / d2;
            if (i == 0) out.moveTo(ax, ay); else out.lineTo(ax, ay);
            out.quadTo(cx, cy, bx, by);
        }
        out.close();
        return out;
    }

    private static Shader rimShader(RectF r, float alphaScale) {
        int[] cols = new int[RIM_ALPHA.length];
        for (int i = 0; i < cols.length; i++) cols[i] = (Math.round(255 * Math.min(1f, RIM_ALPHA[i] * alphaScale)) << 24) | 0xFFFFFF;
        return new android.graphics.SweepGradient(r.centerX(), r.centerY(), cols, RIM_POS);
    }

    /** Apple-style glass: clear light blur, refraction along the edges, specular rim, soft floating shadow. */
    private void glass(Canvas c, Path shape, RectF r, int tint) {
        float cx = r.centerX(), cy = r.centerY(), w = r.width(), h = r.height();
        Bitmap bg = blurLight != null ? blurLight : blur;

        // shadow (the glass body drawn over it is opaque, so it only shows outside)
        if (shadowPaint.getMaskFilter() == null)
            shadowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(SHADOW_BLUR, android.graphics.BlurMaskFilter.Blur.NORMAL));
        shadowPaint.setColor(SHADOW);
        c.save();
        c.translate(0, SHADOW_Y);
        c.drawPath(shape, shadowPaint);
        c.restore();

        c.save();
        c.clipPath(shape);
        if (bg != null) {
            tmp.set(-offX / scale, -offY / scale, (getWidth() - offX) / scale, (getHeight() - offY) / scale);
            p.setShader(null);
            p.setColor(Color.WHITE);
            p.setColorFilter(GLASS_FILTER);
            // edge refraction: nested copies of the shape, each a little smaller and less magnified,
            // so the bend is strongest at the rim and fades to none in the middle
            for (int k = 0; k <= LENS_STEPS; k++) {
                float inset = LENS_BAND * k / LENS_STEPS;
                float zoom = 1f + (LENS_ZOOM - 1f) * (1f - k / (float) LENS_STEPS);
                c.save();
                if (k > 0) {
                    lensMatrix.setScale((w - 2 * inset) / w, (h - 2 * inset) / h, cx, cy);
                    shape.transform(lensMatrix, lensPath);
                    c.clipPath(lensPath);
                }
                c.scale(zoom, zoom, cx, cy);
                c.drawBitmap(bg, null, tmp, p);
                c.restore();
            }
            p.setColorFilter(null);
        }
        p.setColor(GLASS_TINT);
        c.drawRect(r, p);
        if (tint != 0) { p.setColor(tint); c.drawRect(r, p); }
        // faint light from above
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(160, h * .5f), 0x1AFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        // soft inner glow along the rim
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(RIM_GLOW * 2);
        p.setShader(rimShader(r, .22f));
        c.drawPath(shape, p);
        p.setShader(null);
        c.restore();

        // crisp specular rim
        p.setStrokeWidth(RIM_WIDTH);
        p.setShader(rimShader(r, 1f));
        c.drawPath(shape, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
    }

    private float press(String id) { return id.equals(pressed) ? 0.92f : 1f; }

    private void white(float stroke) {
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        if (stroke > 0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(stroke); }
        else p.setStyle(Paint.Style.FILL);
        p.setShadowLayer(6, 0, 2, 0x55000000);
    }

    private void drawSidebar(Canvas c) {
        for (int i = 0; i < 4; i++) {
            float x = SIDE_X, y = SIDE_Y[i];
            c.save();
            float s = press(SIDE_SLOTS[i]);
            c.scale(s, s, x, y);
            switch (i) {
                case 0: // settings target
                    white(6.5f);
                    c.drawCircle(x, y, 24, p);
                    white(0);
                    c.drawCircle(x, y, 10, p);
                    break;
                case 1: // camera
                    white(6f);
                    tmp.set(x - 27, y - 18, x + 27, y + 22);
                    c.drawRoundRect(tmp, 7, 7, p);
                    c.drawCircle(x, y + 2, 10, p);
                    white(0);
                    tmp.set(x - 12, y - 27, x + 12, y - 16);
                    c.drawRoundRect(tmp, 3, 3, p);
                    break;
                case 2: // bluetooth
                    white(6f);
                    path.reset();
                    path.moveTo(x - 17, y - 14);
                    path.lineTo(x + 15, y + 15);
                    path.lineTo(x, y + 30);
                    path.lineTo(x, y - 30);
                    path.lineTo(x + 15, y - 15);
                    path.lineTo(x - 17, y + 14);
                    c.drawPath(path, p);
                    break;
                case 3: // location pin
                    white(6f);
                    path.reset();
                    path.moveTo(x, y + 30);
                    path.cubicTo(x - 10, y + 14, x - 26, y + 2, x - 26, y - 14);
                    path.cubicTo(x - 26, y - 30, x - 14, y - 40, x, y - 40);
                    path.cubicTo(x + 14, y - 40, x + 26, y - 30, x + 26, y - 14);
                    path.cubicTo(x + 26, y + 2, x + 10, y + 14, x, y + 30);
                    path.close();
                    c.drawPath(path, p);
                    c.drawCircle(x, y - 14, 8, p);
                    break;
            }
            c.restore();
        }
        p.clearShadowLayer();
    }

    private void drawAppsButton(Canvas c) {
        float cx = 115, cy = 997;
        // t runs 0 -> 1 over the spin; -1 when idle
        float t = -1f;
        if (appsSpinStart >= 0) {
            t = (android.os.SystemClock.uptimeMillis() - appsSpinStart) / (float) APPS_SPIN_MS;
            if (t >= 1f) { t = -1f; appsSpinStart = -1; } else postInvalidateOnAnimation();
        }
        float ease = t < 0 ? 0f : 1f - (float) Math.pow(1f - t, 3);   // ease-out
        float bump = t < 0 ? 0f : (float) Math.sin(Math.PI * t);      // 0 -> 1 -> 0

        c.save();
        float s = press("apps") * (1f + 0.08f * bump);
        c.scale(s, s, cx, cy);
        c.rotate(360f * ease, cx, cy);
        white(5.5f);
        c.drawCircle(cx, cy, 42, p);
        white(0);
        float spread = 15 + 6 * bump;
        float dotR = 11 - 2 * bump;
        for (int dx = -1; dx <= 1; dx += 2)
            for (int dy = -1; dy <= 1; dy += 2)
                c.drawCircle(cx + dx * spread, cy + dy * spread, dotR, p);
        c.restore();
        p.clearShadowLayer();
    }

    private void drawSpeed(Canvas c) {
        int mph = speed.mph();
        String num = mph < 0 ? "--" : String.valueOf(mph);
        float s = press("speed");
        c.save();
        c.scale(s, s, MOON_CX, MOON_CY);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.setLetterSpacing(-0.02f);
        text.setTextSize(SPEED_SIZE);
        // glow: several passes so it reads as a strong neon halo
        text.setShadowLayer(SPEED_GLOW_RADIUS, 0, 0, SPEED_GLOW);
        for (int i = 0; i < SPEED_GLOW_PASSES; i++) c.drawText(num, MOON_CX, SPEED_Y, text);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setLetterSpacing(0.3f);
        text.setTextSize(MPH_SIZE);
        text.setColor(0xEEFFFFFF);
        text.setShadowLayer(SPEED_GLOW_RADIUS * 0.6f, 0, 0, SPEED_GLOW);
        for (int i = 0; i < 2; i++) c.drawText("MPH", MOON_CX + MPH_SIZE * 0.15f, MPH_Y, text); // nudge right to balance the trailing letter-space
        text.clearShadowLayer();
        text.setLetterSpacing(0f);
        c.restore();
    }

    private void drawClock(Canvas c) {
        Calendar now = Calendar.getInstance();
        boolean h24 = DateFormat.is24HourFormat(getContext());
        String time = DateFormat.format(h24 ? "H:mm" : "h:mm", now).toString();
        String date = DateFormat.format("MMMM d", now).toString();
        float cx = CLOCK.centerX();

        text.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.02f);
        text.setTextSize(124);
        text.setShadowLayer(10, 0, 4, 0x66000000);
        text.setColor(Color.WHITE);
        c.drawText(time, cx, 337, text);

        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextSize(31);
        text.setLetterSpacing(0.12f);
        text.setColor(0xEEFFFFFF);
        c.drawText(date, cx, 395, text);
        text.clearShadowLayer();
        text.setLetterSpacing(0f);
    }

    private void drawMusic(Canvas c) {
        // album artwork
        Bitmap art = media.art();
        float s = press("art");
        c.save();
        c.scale(s, s, ART.centerX(), ART.centerY());
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55000000);
        tmp.set(ART.left + 3, ART.top + 8, ART.right + 3, ART.bottom + 10);
        c.drawRoundRect(tmp, 14, 14, p);
        if (art != null) {
            BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float k = Math.max(ART.width() / art.getWidth(), ART.height() / art.getHeight());
            m.setScale(k, k);
            m.postTranslate(ART.centerX() - art.getWidth() * k / 2f, ART.centerY() - art.getHeight() * k / 2f);
            sh.setLocalMatrix(m);
            p.setShader(sh);
            p.setColor(Color.WHITE);
            c.drawRoundRect(ART, 14, 14, p);
        } else {
            drawPlaceholderArt(c);
        }
        p.setShader(null);
        c.restore();

        // title
        String title = media.title();
        if (!media.hasAccess()) title = "Tap to show music";
        else if (TextUtils.isEmpty(title)) title = "Nothing playing";
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(32);
        text.setLetterSpacing(0.04f);
        text.setColor(Color.WHITE);
        text.setShadowLayer(6, 0, 2, 0x66000000);
        title = TextUtils.ellipsize(title, new android.text.TextPaint(text), MUSIC.width() - 80, TextUtils.TruncateAt.END).toString();
        c.drawText(title, MUSIC.centerX(), TITLE_Y, text);
        String artist = media.artist();
        if (!TextUtils.isEmpty(artist)) {
            text.setTextSize(22);
            text.setColor(0xB3FFFFFF);
            artist = TextUtils.ellipsize(artist, new android.text.TextPaint(text), MUSIC.width() - 80, TextUtils.TruncateAt.END).toString();
            c.drawText(artist, MUSIC.centerX(), ARTIST_Y, text);
        }
        text.clearShadowLayer();
        text.setLetterSpacing(0f);

        // progress bar
        long dur = media.duration();
        float frac = dur > 0 ? Math.min(1f, media.position() / (float) dur) : 0f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x4DFFFFFF);
        tmp.set(PROG_X0, PROG_Y - 3.5f, PROG_X1, PROG_Y + 3.5f);
        c.drawRoundRect(tmp, 4, 4, p);
        float kx = PROG_X0 + (PROG_X1 - PROG_X0) * frac;
        p.setColor(TEAL);
        tmp.set(PROG_X0, PROG_Y - 3.5f, Math.max(PROG_X0 + 1, PROG_X1), PROG_Y + 3.5f);
        c.drawRoundRect(tmp, 4, 4, p); // full teal track like the design...
        p.setColor(0x80FFFFFF);
        tmp.set(kx, PROG_Y - 3.5f, PROG_X1, PROG_Y + 3.5f);
        if (dur > 0) c.drawRoundRect(tmp, 4, 4, p); // ...then dim the unplayed part once we know the length
        p.setColor(TEAL);
        p.setShadowLayer(6, 0, 2, 0x55000000);
        c.drawCircle(kx, PROG_Y, seeking ? 16 : 12.5f, p);
        p.clearShadowLayer();

        // transport controls
        white(0);
        float y = CTRL_Y;
        c.save(); float s0 = press("prev"); c.scale(s0, s0, CTRL_X[0], y);
        float x = CTRL_X[0];
        c.drawRect(x - 19, y - 21, x - 12, y + 21, p);
        path.reset(); path.moveTo(x + 19, y - 21); path.lineTo(x - 11, y); path.lineTo(x + 19, y + 21); path.close();
        c.drawPath(path, p);
        c.restore();

        c.save(); float s1 = press("play"); c.scale(s1, s1, CTRL_X[1], y);
        x = CTRL_X[1];
        if (media.isPlaying()) {
            tmp.set(x - 17, y - 24, x - 5, y + 24); c.drawRoundRect(tmp, 2, 2, p);
            tmp.set(x + 5, y - 24, x + 17, y + 24); c.drawRoundRect(tmp, 2, 2, p);
        } else {
            path.reset(); path.moveTo(x - 15, y - 25); path.lineTo(x + 22, y); path.lineTo(x - 15, y + 25); path.close();
            c.drawPath(path, p);
        }
        c.restore();

        c.save(); float s2 = press("next"); c.scale(s2, s2, CTRL_X[2], y);
        x = CTRL_X[2];
        path.reset(); path.moveTo(x - 19, y - 21); path.lineTo(x + 11, y); path.lineTo(x - 19, y + 21); path.close();
        c.drawPath(path, p);
        c.drawRect(x + 12, y - 21, x + 19, y + 21, p);
        c.restore();
        p.clearShadowLayer();
    }

    private void drawPlaceholderArt(Canvas c) {
        p.setColor(Color.WHITE); // opaque, so the gradient isn't faded by the shadow colour
        p.setShader(new LinearGradient(ART.left, ART.top, ART.right, ART.bottom,
                new int[]{0xFF9FF0F0, 0xFFE6E0FA, 0xFFF4C8EE}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(ART, 14, 14, p);
        p.setShader(null);
        // splash of dots, like the mockup's artwork
        float k = ART.width() / 215f; // dot pattern scales with the cover
        float cx = ART.centerX() + 6 * k, cy = ART.centerY() - 4 * k;
        int[] cols = {0xFF7A3CF0, 0xFFB050F0, 0xFF3C50E0, 0xFFD040C8, 0xFF5AA0F0};
        java.util.Random r = new java.util.Random(7);
        c.save();
        c.clipRect(ART);
        for (int i = 0; i < 60; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = Math.sqrt(r.nextDouble()) * 62 * k;
            float rad = (4 + r.nextFloat() * 10) * k;
            p.setColor(cols[r.nextInt(cols.length)]);
            p.setAlpha(200 + r.nextInt(55));
            c.drawCircle(cx + (float) (Math.cos(a) * d), cy + (float) (Math.sin(a) * d), rad, p);
        }
        c.restore();
        p.setAlpha(255);
    }

    /** Eighth note centred on (cx, cy): rotated oval head, stem and curved flag merged into one path. */
    private static Path musicNote(float cx, float cy) {
        Path head = new Path();
        head.addOval(new RectF(cx - 37, cy + 13, cx + 9, cy + 47), Path.Direction.CW);
        Matrix m = new Matrix();
        m.setRotate(-22, cx - 14, cy + 30);
        head.transform(m);

        Path stem = new Path();
        stem.addRect(cx, cy - 52, cx + 9, cy + 28, Path.Direction.CW);

        Path flag = new Path();
        flag.moveTo(cx + 9, cy - 52);
        flag.cubicTo(cx + 12, cy - 34, cx + 40, cy - 30, cx + 36, cy - 2);
        flag.cubicTo(cx + 34, cy - 18, cx + 22, cy - 22, cx + 9, cy - 26);
        flag.close();

        Path note = new Path(head);
        note.op(stem, Path.Op.UNION);
        note.op(flag, Path.Op.UNION);
        return note;
    }

    /** White, no shadow: the new icon set is flat. */
    private void glyph(float stroke) {
        white(stroke);
        p.clearShadowLayer();
    }

    private void drawTile(Canvas c, int i) {
        float cx = TILE_X[i], cy = TILE_Y, half = TILE_SIZE / 2f;
        c.save();
        float s = press(TILE_SLOTS[i]);
        c.scale(s, s, cx, cy);
        RectF r = new RectF(cx - half, cy - half, cx + half, cy + half);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0x55000000);                       // drop shadow so the tile lifts off the wallpaper
        tmp.set(r.left + 2, r.top + 10, r.right + 2, r.bottom + 12);
        c.drawRoundRect(tmp, 36, 36, p);
        p.setColor(Color.WHITE);                      // opaque: the gradient's own colours decide the look
        Shader tile = TILE_HORIZONTAL[i]
                ? new LinearGradient(r.left, cy, r.right, cy, TILE_COLORS[i][0], TILE_COLORS[i][1], Shader.TileMode.CLAMP)
                : new LinearGradient(r.left, r.top, r.right, r.bottom, TILE_COLORS[i][0], TILE_COLORS[i][1], Shader.TileMode.CLAMP);
        p.setShader(tile);
        c.drawRoundRect(r, 36, 36, p);
        p.setShader(null);

        switch (i) {
            case 0: { // Car Link: phone outline
                glyph(6f);
                tmp.set(cx - 34, cy - 57, cx + 34, cy + 55);
                c.drawRoundRect(tmp, 11, 11, p);
                break;
            }
            case 1: { // Front Video: camera; lens and flash are cut-outs showing the tile colour
                glyph(0);
                path.reset();
                path.addRoundRect(new RectF(cx - 59, cy - 32, cx + 58, cy + 46), 11, 11, Path.Direction.CW);
                Path hump = new Path();
                hump.addRoundRect(new RectF(cx - 29, cy - 45, cx + 30, cy - 20), 9, 9, Path.Direction.CW);
                path.op(hump, Path.Op.UNION);
                c.drawPath(path, p);
                p.setColor(Color.WHITE);
                p.setShader(tile);
                c.drawCircle(cx, cy + 7, 28, p);                  // lens
                c.drawCircle(cx + 41, cy - 16, 5.5f, p);          // flash
                p.setShader(null);
                p.setColor(Color.WHITE);
                c.drawCircle(cx + 11, cy - 4, 5.5f, p);           // lens highlight
                break;
            }
            case 2: { // Equalizer: four sliders
                float[] xs = {-47.5f, -16f, 16f, 47.5f};
                float[] knob = {-7f, 9f, -17f, 20f};
                for (int k = 0; k < 4; k++) {
                    float x = cx + xs[k];
                    glyph(7f);
                    c.drawLine(x, cy - 46, x, cy + 48, p);
                    glyph(0);
                    c.drawCircle(x, cy + knob[k], 10.5f, p);
                }
                break;
            }
            case 3: { // Music: eighth note
                glyph(0);
                c.save();
                c.scale(1.15f, 1.15f, cx, cy);                    // the mockup's note is a bit larger
                c.drawPath(musicNote(cx, cy), p);
                c.restore();
                break;
            }
            case 4: { // YouTube: rounded play triangle
                glyph(0);
                p.setPathEffect(new android.graphics.CornerPathEffect(9));
                path.reset();
                path.moveTo(cx - 24, cy - 36);
                path.lineTo(cx + 38, cy);
                path.lineTo(cx - 24, cy + 36);
                path.close();
                c.drawPath(path, p);
                p.setPathEffect(null);
                break;
            }
        }
        p.clearShadowLayer();
        c.restore();
    }

    // ============================================================== touch

    private String hit(float x, float y) {
        for (int i = 0; i < 4; i++)
            if (dist(x, y, SIDE_X, SIDE_Y[i]) < 72) return SIDE_SLOTS[i];
        if (x >= 0 && x <= APPS.right && y >= APPS.top && y <= APPS.bottom) return "apps";
        for (int i = 0; i < TILE_X.length; i++)
            if (Math.abs(x - TILE_X[i]) < TILE_SIZE / 2 + 8 && Math.abs(y - TILE_Y) < TILE_SIZE / 2 + 8) return TILE_SLOTS[i];
        if (MUSIC.contains(x, y)) {
            if (y > PROG_Y - 30 && y < PROG_Y + 30 && x > PROG_X0 - 25 && x < PROG_X1 + 25) return "seek";
            for (int i = 0; i < 3; i++)
                if (dist(x, y, CTRL_X[i], CTRL_Y) < 60) return i == 0 ? "prev" : i == 1 ? "play" : "next";
            if (y < PROG_Y - 30) return "art";
            return "music";
        }
        if (CLOCK.contains(x, y)) return "clock";
        if (dist(x, y, MOON_CX, MOON_CY) < MOON_R) return "speed";
        if (CAR.contains(x, y)) return "car";
        return "bg";
    }

    private static float dist(float x, float y, float a, float b) {
        float dx = x - a, dy = y - b;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private PointF toDesign(MotionEvent e) {
        return new PointF((e.getX() - offX) / scale, (e.getY() - offY) / scale);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        PointF d = toDesign(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                longFired = false;
                pressed = hit(d.x, d.y);
                if ("seek".equals(pressed) && media.duration() > 0) {
                    seeking = true;
                } else if (!"seek".equals(pressed) && !"music".equals(pressed)) {
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (seeking) { invalidate(); return true; }
                if (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop) {
                    handler.removeCallbacks(longPress);
                    if (pressed != null && !pressed.equals(hit(d.x, d.y))) { pressed = null; invalidate(); }
                }
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                if (seeking) {
                    seeking = false;
                    float f = Math.max(0, Math.min(1, (d.x - PROG_X0) / (PROG_X1 - PROG_X0)));
                    media.seekTo((long) (f * media.duration()));
                } else if (!longFired && pressed != null) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    click(pressed);
                }
                pressed = null;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                seeking = false;
                pressed = null;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void click(String id) {
        if (id.startsWith("side_") || id.startsWith("tile_")) host.onSlotTap(id);
        else if (id.equals("apps")) {
            if (appsSpinStart >= 0) return; // already spinning
            appsSpinStart = android.os.SystemClock.uptimeMillis();
            invalidate();
            handler.postDelayed(new Runnable() { @Override public void run() { host.onAppsTap(); } }, APPS_OPEN_DELAY_MS);
        }
        else if (id.equals("clock")) host.onClockTap();
        else if (id.equals("speed")) host.onSpeedTap();
        else if (id.equals("prev")) { media.previous(); }
        else if (id.equals("next")) { media.next(); }
        else if (id.equals("play")) { media.playPause(); }
        else if (id.equals("art")) host.onMusicInfoTap();
        handler.postDelayed(new Runnable() { @Override public void run() { invalidate(); } }, 400);
    }

    /** Used by the preview tool to draw a frame without a window. */
    public void layoutFor(int w, int h) {
        layout(0, 0, w, h);
        onSizeChanged(w, h, 0, 0);
    }

    @SuppressWarnings("unused")
    private static Rect r(RectF f) { return new Rect((int) f.left, (int) f.top, (int) f.right, (int) f.bottom); }
}
