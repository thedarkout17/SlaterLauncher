package com.redline.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
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

public class MainActivity extends Activity implements HomeView.Host, MediaHelper.Listener {

    private static final int REQ_WALLPAPER = 41;
    private HomeView home;
    private MediaHelper media;

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
        home = new HomeView(this, this, media);
        setContentView(home);
        home.setWallpaper(loadWallpaper());

        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (!sp.getBoolean("tipShown", false)) {
            sp.edit().putBoolean("tipShown", true).apply();
            Toast.makeText(this, "Tip: long-press any icon to choose its app, or long-press the background for options",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        media.start();
        home.invalidate();
    }

    @Override protected void onPause() {
        media.stop();
        super.onPause();
    }

    @Override public void onBackPressed() {
        // This is the home screen; there is nothing to go back to.
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
                "Android settings", "Choose a different home app"};
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
