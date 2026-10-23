package com.redline.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Full-screen grid of every installed app, opened from the four-dot button. */
public class AppDrawerActivity extends Activity {

    private List<Slots.AppEntry> apps;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0.55f);
        apps = Slots.launchableApps(this);
        final PackageManager pm = getPackageManager();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xB3140306);
        root.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });

        final int iconPx = Slots.dp(this, 64);
        final int pad = Slots.dp(this, 10);

        GridView grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Slots.dp(this, 120));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(Slots.dp(this, 18));
        grid.setPadding(Slots.dp(this, 48), Slots.dp(this, 56), Slots.dp(this, 48), Slots.dp(this, 56));
        grid.setClipToPadding(false);
        grid.setSelector(new GradientDrawable());
        grid.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int i) { return apps.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View v, ViewGroup parent) {
                LinearLayout cell;
                if (v instanceof LinearLayout) {
                    cell = (LinearLayout) v;
                } else {
                    cell = new LinearLayout(AppDrawerActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER_HORIZONTAL);
                    cell.setPadding(pad, pad, pad, pad);
                    ImageView iv = new ImageView(AppDrawerActivity.this);
                    cell.addView(iv, new LinearLayout.LayoutParams(iconPx, iconPx));
                    TextView tv = new TextView(AppDrawerActivity.this);
                    tv.setTextColor(Color.WHITE);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                    tv.setGravity(Gravity.CENTER_HORIZONTAL);
                    tv.setMaxLines(2);
                    tv.setEllipsize(TextUtils.TruncateAt.END);
                    tv.setShadowLayer(6, 0, 2, 0xAA000000);
                    tv.setPadding(0, pad, 0, 0);
                    cell.addView(tv);
                }
                Slots.AppEntry a = apps.get(i);
                ((ImageView) cell.getChildAt(0)).setImageDrawable(a.ri.loadIcon(pm));
                ((TextView) cell.getChildAt(1)).setText(a.label);
                return cell;
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int i, long id) {
                Intent li = new Intent(Intent.ACTION_MAIN);
                li.addCategory(Intent.CATEGORY_LAUNCHER);
                li.setComponent(apps.get(i).cn);
                li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Slots.start(AppDrawerActivity.this, li);
                finish();
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View view, int i, long id) {
                Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + apps.get(i).cn.getPackageName()));
                Slots.start(AppDrawerActivity.this, info);
                return true;
            }
        });
        root.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}
