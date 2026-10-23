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
import android.graphics.RadialGradient;
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
    }

    static final float DW = 2000f, DH = 1200f;

    // ---- layout (design pixels) ------------------------------------------------
    static final RectF SIDEBAR = new RectF(-60, 230, 215, 890);
    static final RectF APPS = new RectF(-60, 900, 215, 1105);
    static final RectF CLOCK = new RectF(290, 188, 780, 435);
    static final RectF MUSIC = new RectF(290, 455, 780, 1105);
    static final RectF ART = new RectF(428, 512, 643, 722);
    static final float SIDE_X = 117f;
    static final float[] SIDE_Y = {330f, 487f, 643f, 797f};
    static final String[] SIDE_SLOTS = {Slots.SIDE_SETTINGS, Slots.SIDE_CAMERA, Slots.SIDE_BT, Slots.SIDE_NAV};
    static final float PROG_Y = 917f, PROG_X0 = 335f, PROG_X1 = 740f;
    static final float CTRL_Y = 1000f;
    static final float[] CTRL_X = {388f, 537f, 685f};
    static final float[] TILE_X = {913f, 1115f, 1322f, 1541f, 1775f};
    static final float TILE_Y = 1017f, TILE_SIZE = 168f;
    static final String[] TILE_SLOTS = {Slots.TILE_LINK, Slots.TILE_VIDEO, Slots.TILE_EQ, Slots.TILE_MUSIC, Slots.TILE_YT};
    static final int[][] TILE_COLORS = {
            {0xFF46E6B6, 0xFF1FAA86},
            {0xFF4DDCEB, 0xFF2A9FDA},
            {0xFFB772F4, 0xFF7A4BE4},
            {0xFFF46AA6, 0xFFCF3569},
            {0xFFF25050, 0xFFC4282B},
    };
    static final int TEAL = 0xFF45D6B4;

    private final Host host;
    private final MediaHelper media;

    private Bitmap wallpaper, wallScaled, blur;
    private float scale = 1f, offX, offY;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());

    // touch state
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

    public HomeView(Context c, Host host, MediaHelper media) {
        super(c);
        this.host = host;
        this.media = media;
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
    }

    private static Bitmap boxBlur(Bitmap src, int r) {
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

        glass(c, SIDEBAR, 34, 0);
        glass(c, APPS, 34, 0x66E0242C);
        glass(c, CLOCK, 26, 0);
        glass(c, MUSIC, 26, 0);

        drawSidebar(c);
        drawAppsButton(c);
        drawClock(c);
        drawMusic(c);
        for (int i = 0; i < TILE_X.length; i++) drawTile(c, i);

        c.restore();
    }

    /** Frosted-glass panel: blurred wallpaper, a light tint, a top sheen and a thin bright edge. */
    private void glass(Canvas c, RectF r, float radius, int tint) {
        path.reset();
        path.addRoundRect(r, radius, radius, Path.Direction.CW);
        // soft shadow
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0x33000000);
        tmp.set(r.left + 4, r.top + 8, r.right + 4, r.bottom + 10);
        c.drawRoundRect(tmp, radius, radius, p);

        c.save();
        c.clipPath(path);
        if (blur != null) {
            tmp.set(-offX / scale, -offY / scale, (getWidth() - offX) / scale, (getHeight() - offY) / scale);
            p.setColor(Color.WHITE);
            c.drawBitmap(blur, null, tmp, p);
        }
        p.setColor(0x2EFFE6EA);
        c.drawRect(r, p);
        if (tint != 0) { p.setColor(tint); c.drawRect(r, p); }
        p.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(220, r.height()),
                0x26FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(r, p);
        p.setShader(null);
        c.restore();

        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.2f);
        p.setColor(0x59FFFFFF);
        c.drawPath(path, p);
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
        c.save();
        float s = press("apps");
        c.scale(s, s, cx, cy);
        white(5.5f);
        c.drawCircle(cx, cy, 42, p);
        white(0);
        for (int dx = -1; dx <= 1; dx += 2)
            for (int dy = -1; dy <= 1; dy += 2)
                c.drawCircle(cx + dx * 15, cy + dy * 15, 11, p);
        c.restore();
        p.clearShadowLayer();
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
        c.drawRoundRect(tmp, 10, 10, p);
        if (art != null) {
            BitmapShader sh = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float k = Math.max(ART.width() / art.getWidth(), ART.height() / art.getHeight());
            m.setScale(k, k);
            m.postTranslate(ART.centerX() - art.getWidth() * k / 2f, ART.centerY() - art.getHeight() * k / 2f);
            sh.setLocalMatrix(m);
            p.setShader(sh);
            p.setColor(Color.WHITE);
            c.drawRoundRect(ART, 10, 10, p);
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
        c.drawText(title, MUSIC.centerX(), 845, text);
        String artist = media.artist();
        if (!TextUtils.isEmpty(artist)) {
            text.setTextSize(22);
            text.setColor(0xB3FFFFFF);
            artist = TextUtils.ellipsize(artist, new android.text.TextPaint(text), MUSIC.width() - 80, TextUtils.TruncateAt.END).toString();
            c.drawText(artist, MUSIC.centerX(), 878, text);
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
        p.setShader(new LinearGradient(ART.left, ART.top, ART.right, ART.bottom,
                new int[]{0xFF9FF0F0, 0xFFE6E0FA, 0xFFF4C8EE}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(ART, 10, 10, p);
        p.setShader(null);
        // splash of dots, like the mockup's artwork
        float cx = ART.centerX() + 6, cy = ART.centerY() - 4;
        int[] cols = {0xFF7A3CF0, 0xFFB050F0, 0xFF3C50E0, 0xFFD040C8, 0xFF5AA0F0};
        java.util.Random r = new java.util.Random(7);
        c.save();
        c.clipRect(ART);
        for (int i = 0; i < 60; i++) {
            double a = r.nextDouble() * Math.PI * 2;
            double d = Math.sqrt(r.nextDouble()) * 62;
            float rad = 4 + r.nextFloat() * 10;
            p.setColor(cols[r.nextInt(cols.length)]);
            p.setAlpha(200 + r.nextInt(55));
            c.drawCircle(cx + (float) (Math.cos(a) * d), cy + (float) (Math.sin(a) * d), rad, p);
        }
        c.restore();
        p.setAlpha(255);
    }

    private void drawTile(Canvas c, int i) {
        float cx = TILE_X[i], cy = TILE_Y, half = TILE_SIZE / 2f;
        c.save();
        float s = press(TILE_SLOTS[i]);
        c.scale(s, s, cx, cy);
        RectF r = new RectF(cx - half, cy - half, cx + half, cy + half);
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0x55000000);
        tmp.set(r.left + 2, r.top + 10, r.right + 2, r.bottom + 12);
        c.drawRoundRect(tmp, 36, 36, p);
        p.setShader(new LinearGradient(0, r.top, 0, r.bottom, TILE_COLORS[i][0], TILE_COLORS[i][1], Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 36, 36, p);
        p.setShader(new RadialGradient(cx - 30, r.top + 20, TILE_SIZE * 0.8f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, 36, 36, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(0x40FFFFFF);
        c.drawRoundRect(r, 36, 36, p);

        switch (i) {
            case 0: { // car link: phone
                white(7f);
                tmp.set(cx - 30, cy - 50, cx + 30, cy + 50);
                c.drawRoundRect(tmp, 11, 11, p);
                white(0);
                p.setColor(0xF2FFFFFF);
                tmp.set(cx - 18, cy - 36, cx + 18, cy + 14);
                c.drawRoundRect(tmp, 4, 4, p);
                for (int k = -1; k <= 1; k++) {
                    float dx = cx + k * 14, dy = cy + 31;
                    path.reset();
                    path.moveTo(dx, dy - 6); path.lineTo(dx + 6, dy); path.lineTo(dx, dy + 6); path.lineTo(dx - 6, dy); path.close();
                    c.drawPath(path, p);
                }
                break;
            }
            case 1: { // front video: camera
                white(0);
                tmp.set(cx - 50, cy - 30, cx + 50, cy + 40);
                c.drawRoundRect(tmp, 12, 12, p);
                tmp.set(cx - 20, cy - 44, cx + 20, cy - 24);
                c.drawRoundRect(tmp, 6, 6, p);
                p.clearShadowLayer();
                p.setColor(TILE_COLORS[1][1]);
                c.drawCircle(cx, cy + 5, 22, p);
                p.setColor(Color.WHITE);
                c.drawCircle(cx, cy + 5, 12, p);
                p.setColor(TILE_COLORS[1][1]);
                c.drawCircle(cx, cy + 5, 6, p);
                break;
            }
            case 2: { // equalizer
                float[] knob = {12, -18, 18, -6, 10};
                for (int k = 0; k < 5; k++) {
                    float x = cx - 48 + k * 24;
                    white(5f);
                    c.drawLine(x, cy - 46, x, cy + 46, p);
                    white(0);
                    tmp.set(x - 11, cy + knob[k] - 9, x + 11, cy + knob[k] + 9);
                    c.drawRoundRect(tmp, 8, 8, p);
                }
                break;
            }
            case 3: { // music note
                white(0);
                c.save();
                c.rotate(-18, cx - 22, cy + 30);
                tmp.set(cx - 44, cy + 14, cx - 2, cy + 46);
                c.drawOval(tmp, p);
                c.restore();
                tmp.set(cx - 9, cy - 50, cx + 2, cy + 30);
                c.drawRect(tmp, p);
                path.reset();
                path.moveTo(cx - 4, cy - 52);
                path.cubicTo(cx + 18, cy - 46, cx + 40, cy - 36, cx + 42, cy - 10);
                path.cubicTo(cx + 34, cy - 24, cx + 18, cy - 28, cx - 4, cy - 26);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case 4: { // play
                white(0);
                path.reset();
                path.moveTo(cx - 28, cy - 38);
                path.lineTo(cx + 42, cy);
                path.lineTo(cx - 28, cy + 38);
                path.close();
                c.drawPath(path, p);
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
        else if (id.equals("apps")) host.onAppsTap();
        else if (id.equals("clock")) host.onClockTap();
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
