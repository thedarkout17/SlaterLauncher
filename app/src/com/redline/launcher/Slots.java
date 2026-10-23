package com.redline.launcher;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Every tappable shortcut on the home screen is a "slot" that opens an app the user can change. */
public class Slots {

    public static final String SIDE_SETTINGS = "side_settings";
    public static final String SIDE_CAMERA = "side_camera";
    public static final String SIDE_BT = "side_bt";
    public static final String SIDE_NAV = "side_nav";
    public static final String TILE_LINK = "tile_0";
    public static final String TILE_VIDEO = "tile_1";
    public static final String TILE_EQ = "tile_2";
    public static final String TILE_MUSIC = "tile_3";
    public static final String TILE_YT = "tile_4";

    public static String title(String slot) {
        if (SIDE_SETTINGS.equals(slot)) return "Settings button";
        if (SIDE_CAMERA.equals(slot)) return "Camera button";
        if (SIDE_BT.equals(slot)) return "Bluetooth button";
        if (SIDE_NAV.equals(slot)) return "Navigation button";
        if (TILE_LINK.equals(slot)) return "Car Link tile";
        if (TILE_VIDEO.equals(slot)) return "Front Video tile";
        if (TILE_EQ.equals(slot)) return "Equalizer tile";
        if (TILE_MUSIC.equals(slot)) return "Music tile";
        if (TILE_YT.equals(slot)) return "YouTube tile";
        return "Shortcut";
    }

    /** Label keywords used to find a sensible app automatically, best match first. */
    private static String[] keywords(String slot) {
        if (SIDE_SETTINGS.equals(slot)) return new String[]{"settings", "car settings", "setting"};
        if (SIDE_CAMERA.equals(slot)) return new String[]{"front video", "camera", "avin", "dvr", "front camera", "reverse"};
        if (SIDE_BT.equals(slot)) return new String[]{"bluetooth", "bt phone", "phone", "bt music", "bt"};
        if (SIDE_NAV.equals(slot)) return new String[]{"maps", "navigation", "waze", "navi", "gps"};
        if (TILE_LINK.equals(slot)) return new String[]{"car link 2.0", "car link", "carlink", "zlink", "autolink", "android auto", "carplay", "tlink", "easyconnection"};
        if (TILE_VIDEO.equals(slot)) return new String[]{"front video", "front camera", "camera", "avin", "dvr", "video"};
        if (TILE_EQ.equals(slot)) return new String[]{"equalizer", "eq", "sound", "audio", "dsp"};
        if (TILE_MUSIC.equals(slot)) return new String[]{"music player", "music", "local music", "player", "spotify"};
        if (TILE_YT.equals(slot)) return new String[]{"youtube", "youtube music", "video"};
        return new String[0];
    }

    private static String preferredPackage(String slot) {
        if (TILE_YT.equals(slot)) return "com.google.android.youtube";
        if (SIDE_NAV.equals(slot)) return "com.google.android.apps.maps";
        return null;
    }

    /** Intent used when no installed app matches. */
    private static Intent fallback(String slot) {
        if (SIDE_SETTINGS.equals(slot)) return new Intent(Settings.ACTION_SETTINGS);
        if (SIDE_BT.equals(slot)) return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        if (SIDE_NAV.equals(slot)) return new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
        if (TILE_MUSIC.equals(slot)) return Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
        if (TILE_EQ.equals(slot)) return new Intent("android.media.action.DISPLAY_AUDIO_EFFECT_CONTROL_PANEL");
        if (TILE_YT.equals(slot)) return new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"));
        return null;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("slots", Context.MODE_PRIVATE);
    }

    public static class AppEntry {
        public final ComponentName cn;
        public final String label;
        public final ResolveInfo ri;
        AppEntry(ResolveInfo ri, PackageManager pm) {
            this.ri = ri;
            this.cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            CharSequence l = ri.loadLabel(pm);
            this.label = l == null ? ri.activityInfo.packageName : l.toString();
        }
    }

    public static List<AppEntry> launchableApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        List<AppEntry> out = new ArrayList<AppEntry>();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo.packageName.equals(c.getPackageName())) continue;
            out.add(new AppEntry(ri, pm));
        }
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    /** Find the app a slot should open: user choice first, then a label match. */
    public static ComponentName resolve(Context c, String slot) {
        String saved = prefs(c).getString(slot, null);
        if (saved != null) {
            ComponentName cn = ComponentName.unflattenFromString(saved);
            if (cn != null && exists(c, cn)) return cn;
        }
        List<AppEntry> apps = launchableApps(c);
        String pkg = preferredPackage(slot);
        if (pkg != null) {
            for (AppEntry a : apps) if (a.cn.getPackageName().equals(pkg)) return a.cn;
        }
        for (String k : keywords(slot)) {
            for (AppEntry a : apps) if (a.label.toLowerCase(Locale.ROOT).equals(k)) return a.cn;
        }
        for (String k : keywords(slot)) {
            if (k.length() < 3) continue; // avoid matching "eq"/"bt" inside unrelated words
            for (AppEntry a : apps) if (a.label.toLowerCase(Locale.ROOT).contains(k)) return a.cn;
        }
        return null;
    }

    private static boolean exists(Context c, ComponentName cn) {
        try {
            c.getPackageManager().getActivityInfo(cn, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static void launch(final Context c, final String slot) {
        ComponentName cn = resolve(c, slot);
        if (cn != null) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (start(c, i)) return;
        }
        Intent fb = fallback(slot);
        if (fb != null) {
            fb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (start(c, fb)) return;
        }
        Toast.makeText(c, "Choose an app for the " + title(slot).toLowerCase(Locale.ROOT), Toast.LENGTH_SHORT).show();
        pick(c, slot);
    }

    public static boolean start(Context c, Intent i) {
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Show a list of installed apps so the user can choose what a slot opens. */
    public static void pick(final Context c, final String slot) {
        final List<AppEntry> apps = launchableApps(c);
        final PackageManager pm = c.getPackageManager();
        final int pad = dp(c, 12);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int p) { return apps.get(p); }
            @Override public long getItemId(int p) { return p; }
            @Override public View getView(int p, View v, ViewGroup parent) {
                LinearLayout row;
                if (v instanceof LinearLayout) {
                    row = (LinearLayout) v;
                } else {
                    row = new LinearLayout(c);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(pad * 2, pad, pad * 2, pad);
                    ImageView iv = new ImageView(c);
                    row.addView(iv, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
                    TextView tv = new TextView(c);
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                    tv.setTextColor(Color.WHITE);
                    tv.setPadding(pad * 2, 0, 0, 0);
                    row.addView(tv);
                }
                AppEntry a = apps.get(p);
                ((ImageView) row.getChildAt(0)).setImageDrawable(a.ri.loadIcon(pm));
                ((TextView) row.getChildAt(1)).setText(a.label);
                return row;
            }
        };
        new AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Choose app for the " + title(slot))
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        prefs(c).edit().putString(slot, apps.get(which).cn.flattenToString()).apply();
                        Toast.makeText(c, title(slot) + " now opens " + apps.get(which).label, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Automatic", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        prefs(c).edit().remove(slot).apply();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
