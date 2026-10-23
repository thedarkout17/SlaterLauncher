package com.redline.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Full-screen app drawer shown over the home screen: a heavily frosted copy of the
 * wallpaper, a header with the app count and a close button, and a scrolling grid of
 * app icons sitting on glass plates. Sizes are on the same 2000 x 1200 design grid as
 * HomeView and scale to the real screen.
 */
public class DrawerView extends FrameLayout {

    public interface Callback {
        void onLaunch(Slots.AppEntry app);
        void onAppInfo(Slots.AppEntry app);
        void onClose();
    }

    // ---- look (design px on the 2000 x 1200 grid)
    static final int COLUMNS = 6;
    static final float SIDE_PAD = 130, TOP_PAD = 70, HEADER_H = 190;
    static final float ICON = 160;          // app icons sit straight on the frosted background, no plate
    static final float LABEL_SIZE = 28, LABEL_GAP = 18, ROW_GAP = 46;
    static final float TITLE_SIZE = 64, COUNT_SIZE = 27, CLOSE_SIZE = 88;
    static final int BLUR_DOWNSCALE = 18;      // bigger = blurrier background
    static final int TINT = 0x6B12030A;        // dark wash over the blurred wallpaper
    static final int PLATE_FILL = 0x26FFFFFF, PLATE_EDGE = 0x4DFFFFFF; // used by the close button

    private final Callback cb;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap wallpaper, blurred;
    private float s = 1f, offX, offY;

    private final LinearLayout titles;
    private final TextView title, count;
    private final CloseButton close;
    private final GridView grid;
    private final AppAdapter adapter = new AppAdapter();
    private List<Slots.AppEntry> apps = new ArrayList<Slots.AppEntry>();
    private boolean showing;

    public DrawerView(Context c, Callback cb) {
        super(c);
        this.cb = cb;
        setWillNotDraw(false);
        setClickable(true); // swallow touches so the home screen underneath doesn't get them

        titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(c);
        title.setText("Apps");
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setShadowLayer(12, 0, 3, 0x66000000);
        count = new TextView(c);
        count.setTextColor(0xB3FFFFFF);
        count.setLetterSpacing(0.08f);
        count.setShadowLayer(8, 0, 2, 0x66000000);
        titles.addView(title);
        titles.addView(count);
        addView(titles);

        close = new CloseButton(c);
        close.setContentDescription("Close apps");
        close.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { DrawerView.this.cb.onClose(); }
        });
        addView(close);

        grid = new GridView(c);
        grid.setNumColumns(COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setCacheColorHint(Color.TRANSPARENT);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalFadingEdgeEnabled(true);
        grid.setClipToPadding(false);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int i, long id) {
                DrawerView.this.cb.onLaunch(apps.get(i));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View v, int i, long id) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                DrawerView.this.cb.onAppInfo(apps.get(i));
                return true;
            }
        });
        addView(grid);
    }

    // ------------------------------------------------------------------ public

    public void setWallpaper(Bitmap b) {
        wallpaper = b;
        rebuildBlur();
        invalidate();
    }

    public boolean isShowing() { return showing; }

    public void show(List<Slots.AppEntry> list) {
        apps = list;
        adapter.icons.clear();
        adapter.notifyDataSetChanged();
        count.setText(list.size() == 1 ? "1 APP" : list.size() + " APPS");
        grid.setSelection(0);
        showing = true;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(220).setListener(null).start();
        grid.setTranslationY(70 * s);
        grid.setAlpha(0f);
        grid.animate().translationY(0).alpha(1f).setDuration(340).setStartDelay(40)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        titles.setTranslationY(-20 * s);
        titles.animate().translationY(0).setDuration(300).setInterpolator(new DecelerateInterpolator(2f)).start();
    }

    public void hide(boolean animated) {
        if (!showing) return;
        showing = false;
        if (!animated) {
            animate().cancel();
            setVisibility(GONE);
            return;
        }
        animate().alpha(0f).setDuration(180).withEndAction(new Runnable() {
            @Override public void run() { if (!showing) setVisibility(GONE); }
        }).start();
        grid.animate().translationY(40 * s).setStartDelay(0).setDuration(180).start();
    }

    // ------------------------------------------------------------------ layout

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        s = Math.min(w / HomeView.DW, h / HomeView.DH);
        offX = (w - HomeView.DW * s) / 2f;
        offY = (h - HomeView.DH * s) / 2f;
        applyMetrics(w, h);
        rebuildBlur();
    }

    private int px(float design) { return Math.round(design * s); }

    private void applyMetrics(int w, int h) {
        int side = Math.round(offX) + px(SIDE_PAD);
        int top = Math.round(offY) + px(TOP_PAD);

        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, TITLE_SIZE * s);
        count.setTextSize(TypedValue.COMPLEX_UNIT_PX, COUNT_SIZE * s);
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = side;
        tl.topMargin = top;
        titles.setLayoutParams(tl);

        LayoutParams cl = new LayoutParams(px(CLOSE_SIZE), px(CLOSE_SIZE), Gravity.TOP | Gravity.END);
        cl.rightMargin = side;
        cl.topMargin = top + px(14);
        close.setLayoutParams(cl);

        LayoutParams gl = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gl.topMargin = Math.round(offY) + px(HEADER_H + TOP_PAD - 20);
        grid.setLayoutParams(gl);
        grid.setPadding(side, px(26), side, px(90));
        grid.setHorizontalSpacing(px(24));
        grid.setVerticalSpacing(px(ROW_GAP));
        grid.setFadingEdgeLength(px(90));
        adapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ background

    private void rebuildBlur() {
        int w = getWidth(), h = getHeight();
        if (wallpaper == null || w == 0 || h == 0) return;
        // centre-crop the wallpaper to the screen's shape, shrink it a lot, then blur: cheap and very soft
        float target = w / (float) h, src = wallpaper.getWidth() / (float) wallpaper.getHeight();
        int cw = wallpaper.getWidth(), ch = wallpaper.getHeight(), cx = 0, cy = 0;
        if (src > target) { cw = Math.round(ch * target); cx = (wallpaper.getWidth() - cw) / 2; }
        else { ch = Math.round(cw / target); cy = (wallpaper.getHeight() - ch) / 2; }
        Bitmap crop = Bitmap.createBitmap(wallpaper, cx, cy, cw, ch);
        Bitmap small = Bitmap.createScaledBitmap(crop, Math.max(2, w / BLUR_DOWNSCALE), Math.max(2, h / BLUR_DOWNSCALE), true);
        if (crop != wallpaper) crop.recycle();
        blurred = HomeView.boxBlur(HomeView.boxBlur(HomeView.boxBlur(small, 2), 2), 2);
    }

    private final RectF full = new RectF();

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        full.set(0, 0, w, h);
        p.setShader(null);
        p.setColor(Color.WHITE);
        if (blurred != null) c.drawBitmap(blurred, null, full, p);
        else c.drawColor(0xFF2A0508);
        c.drawColor(TINT);
        // darker band behind the header, softer toward the bottom
        p.setShader(new LinearGradient(0, 0, 0, offY + 320 * s, 0x59000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, offY + 320 * s, p);
        p.setShader(new LinearGradient(0, h - 260 * s, 0, h, 0x00000000, 0x4D000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h - 260 * s, w, h, p);
        p.setShader(null);
        // hairline under the header
        p.setColor(0x26FFFFFF);
        float y = offY + (HEADER_H + TOP_PAD - 26) * s;
        float side = offX + SIDE_PAD * s;
        c.drawRect(side, y, w - side, y + Math.max(1f, 1.5f * s), p);
    }

    // ------------------------------------------------------------------ grid cells

    private class AppAdapter extends BaseAdapter {
        final HashMap<ComponentName, Drawable> icons = new HashMap<ComponentName, Drawable>();

        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            AppCell cell = v instanceof AppCell ? (AppCell) v : new AppCell(getContext());
            Slots.AppEntry a = apps.get(i);
            Drawable d = icons.get(a.cn);
            if (d == null) {
                PackageManager pm = getContext().getPackageManager();
                d = a.ri.loadIcon(pm);
                icons.put(a.cn, d);
            }
            cell.bind(a.label, d);
            return cell;
        }
    }

    /** One app: its own icon, large, with its name underneath. */
    private class AppCell extends View {
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private String label = "";
        private Drawable icon;

        AppCell(Context c) {
            super(c);
            tp.setColor(Color.WHITE);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setShadowLayer(8, 0, 2, 0x99000000);
        }

        void bind(String label, Drawable icon) {
            this.label = label;
            this.icon = icon;
            setContentDescription(label);
            invalidate();
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int h = Math.round((ICON + LABEL_GAP + LABEL_SIZE * 1.5f) * s);
            setMeasuredDimension(w, h);
        }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            animate().scaleX(pressed ? 0.92f : 1f).scaleY(pressed ? 0.92f : 1f).setDuration(110).start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), size = ICON * s;
            setPivotX(w / 2f);
            setPivotY(size / 2f);

            if (icon != null) {
                int is = Math.round(size);
                int l = Math.round((w - is) / 2f);
                icon.setBounds(l, 0, l + is, is);
                icon.draw(c);
            }

            tp.setTextSize(LABEL_SIZE * s);
            String text = TextUtils.ellipsize(label, tp, w - 16 * s, TextUtils.TruncateAt.END).toString();
            c.drawText(text, w / 2f, size + LABEL_GAP * s + LABEL_SIZE * s * 0.95f, tp);
        }
    }

    /** Round glass button with an X. */
    private class CloseButton extends View {
        private final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseButton(Context c) { super(c); setClickable(true); }

        @Override public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            animate().scaleX(pressed ? 0.9f : 1f).scaleY(pressed ? 0.9f : 1f).setDuration(100).start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), cx = w / 2f, cy = getHeight() / 2f, r = w / 2f - 2 * s;
            bp.setStyle(Paint.Style.FILL);
            bp.setColor(PLATE_FILL);
            c.drawCircle(cx, cy, r, bp);
            bp.setStyle(Paint.Style.STROKE);
            bp.setStrokeWidth(Math.max(1f, 2f * s));
            bp.setColor(PLATE_EDGE);
            c.drawCircle(cx, cy, r, bp);
            bp.setColor(Color.WHITE);
            bp.setStrokeWidth(5f * s);
            bp.setStrokeCap(Paint.Cap.ROUND);
            float k = r * 0.36f;
            c.drawLine(cx - k, cy - k, cx + k, cy + k, bp);
            c.drawLine(cx + k, cy - k, cx - k, cy + k, bp);
        }
    }
}
