package com.redline.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity implements HomeView.Host, MediaHelper.Listener, SpeedHelper.Listener {

    private static final int REQ_WALLPAPER = 41;
    private static final int REQ_LOCATION = 42;
    private static final int REQ_HOME = 43;
    private boolean homePromptShown; // once per app start
    private HomeView home;
    private MediaHelper media;
    private SpeedHelper speed;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        media = new MediaHelper(this, this);
        speed = new SpeedHelper(this, this);
        home = new HomeView(this, this, media, speed);
        setContentView(home);
        home.setWallpaper(loadWallpaper());

        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (!speed.hasPermission() && !sp.getBoolean("askedLocation", false)) {
            sp.edit().putBoolean("askedLocation", true).apply();
            requestLocation();
        }
        if (!sp.getBoolean("tipShown", false)) {
            sp.edit().putBoolean("tipShown", true).apply();
            Toast.makeText(this, "Tip: long-press any icon to choose its app, or long-press the background for options",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        media.start();
        speed.start();
        home.invalidate();
        maybeAskToBeHome();
    }

    // ------------------------------------------------------------------ default home screen

    /** True when the Home button opens this launcher. */
    private boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }

    private void maybeAskToBeHome() {
        if (homePromptShown || isDefaultHome()) return;
        if (getSharedPreferences("app", MODE_PRIVATE).getBoolean("neverAskHome", false)) return;
        homePromptShown = true;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Make this your home screen?")
                .setMessage("The Home button still opens your old launcher. Tap Set as home, then choose "
                        + "Redline Launcher and tap Always if asked.")
                .setPositiveButton("Set as home", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { requestDefaultHome(); }
                })
                .setNegativeButton("Not now", null)
                .setNeutralButton("Don't ask again", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("neverAskHome", true).apply();
                    }
                })
                .show();
    }

    /** Ask Android to make this the home app, using the best method the unit supports. */
    private void requestDefaultHome() {
        // Android 10+: the system "Set as default home app?" dialog (RoleManager, called by name
        // so the app still builds against the Android 9 SDK).
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Object rm = getSystemService("role");
                Intent r = (Intent) rm.getClass().getMethod("createRequestRoleIntent", String.class)
                        .invoke(rm, "android.app.role.HOME");
                startActivityForResult(r, REQ_HOME);
                return;
            } catch (Exception ignored) { }
        }
        // Older Android or a unit without that dialog: open the Home app settings screen.
        if (Slots.start(this, new Intent(Settings.ACTION_HOME_SETTINGS))) return;
        // Last resort: the system app chooser, by briefly asking for HOME.
        Intent chooser = new Intent(Intent.ACTION_MAIN);
        chooser.addCategory(Intent.CATEGORY_HOME);
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!Slots.start(this, Intent.createChooser(chooser, "Choose your home screen"))) {
            Toast.makeText(this, "Open Settings > Apps > Default apps > Home app and pick Redline Launcher",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onPause() {
        media.stop();
        speed.stop();
        super.onPause();
    }

    @Override public void onBackPressed() {
        // This is the home screen; there is nothing to go back to.
    }

    @Override public void onSpeedChanged() {
        if (home != null) home.postInvalidate();
    }

    @Override public void onSpeedTap() {
        if (!speed.hasPermission()) {
            requestLocation();
        } else if (!speed.gpsEnabled()) {
            Toast.makeText(this, "Turn on location so the speed can be shown", Toast.LENGTH_LONG).show();
            Slots.start(this, new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else if (speed.mph() < 0) {
            Toast.makeText(this, "Waiting for a GPS signal", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestLocation() {
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_LOCATION && speed.hasPermission()) speed.start();
    }

    @Override public void onMediaChanged() {
        if (home != null) home.postInvalidate();
    }

    // ------------------------------------------------------------------ HomeView.Host

    @Override public void onSlotTap(String slot) { Slots.launch(this, slot); }

    @Override public void onSlotLongPress(String slot) { Slots.pick(this, slot); }

    @Override public void onAppsTap() {
        startActivity(new Intent(this, AppDrawerActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override public void onClockTap() {
        Intent i = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!Slots.start(this, i)) {
            Slots.start(this, new Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @Override public void onMusicInfoTap() {
        if (!media.hasAccess()) {
            askForMusicAccess();
            return;
        }
        if (!media.openPlayer()) Slots.launch(this, Slots.TILE_MUSIC);
    }

    private void askForMusicAccess() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Show what's playing")
                .setMessage("To show the song name, album art and progress, turn on notification access for "
                        + "Redline Launcher on the next screen. The play/skip buttons already work without it.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { media.openAccessSettings(); }
                })
                .setNegativeButton("Not now", null)
                .show();
    }

    @Override public void onBackgroundLongPress() {
        String[] items = {"Change wallpaper", "Use the default wallpaper", "Show song info (music access)",
                "Android settings", "Choose a different home app", "Make this my home screen"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Redline Launcher")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: pickWallpaper(); break;
                            case 1:
                                new File(getFilesDir(), "wallpaper.jpg").delete();
                                home.setWallpaper(loadWallpaper());
                                break;
                            case 2: media.openAccessSettings(); break;
                            case 3:
                                Slots.start(MainActivity.this, new Intent(Settings.ACTION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                                break;
                            case 4:
                                Intent hi = new Intent(Settings.ACTION_HOME_SETTINGS);
                                if (!Slots.start(MainActivity.this, hi))
                                    Slots.start(MainActivity.this, new Intent(Settings.ACTION_SETTINGS));
                                break;
                            case 5:
                                getSharedPreferences("app", MODE_PRIVATE).edit().remove("neverAskHome").apply();
                                if (isDefaultHome()) Toast.makeText(MainActivity.this,
                                        "Redline Launcher is already your home screen", Toast.LENGTH_SHORT).show();
                                else requestDefaultHome();
                                break;
                        }
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ wallpaper

    private void pickWallpaper() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Choose wallpaper"), REQ_WALLPAPER);
        } catch (Exception e) {
            Toast.makeText(this, "No gallery or file app found to pick an image", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_HOME) {
            if (isDefaultHome()) {
                Toast.makeText(this, "Done. The Home button now opens Redline Launcher", Toast.LENGTH_LONG).show();
            } else {
                // The role dialog was declined or isn't honoured on this unit; try the settings screen.
                Slots.start(this, new Intent(Settings.ACTION_HOME_SETTINGS));
            }
            return;
        }
        if (req != REQ_WALLPAPER || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            Bitmap b = decodeSampled(uri, 2600);
            if (b == null) throw new Exception("unreadable");
            FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "wallpaper.jpg"));
            b.compress(Bitmap.CompressFormat.JPEG, 94, out);
            out.close();
            home.setWallpaper(b);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open that image", Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap decodeSampled(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        in = getContentResolver().openInputStream(uri);
        Bitmap b = BitmapFactory.decodeStream(in, null, o2);
        in.close();
        return b;
    }

    private Bitmap loadWallpaper() {
        File f = new File(getFilesDir(), "wallpaper.jpg");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        if (f.exists()) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (b != null) return b;
        }
        return BitmapFactory.decodeResource(getResources(), R.drawable.default_wallpaper, o);
    }
}
