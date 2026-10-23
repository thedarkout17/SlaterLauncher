package com.redline.launcher;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

/** Reads the car's speed from GPS and reports it in miles per hour. */
public class SpeedHelper {

    public interface Listener { void onSpeedChanged(); }

    static final float MPS_TO_MPH = 2.23694f;
    /** Show "--" if GPS has not reported a speed for this long. */
    static final long STALE_MS = 5000;

    private final Context ctx;
    private final Listener listener;
    private final LocationManager lm;
    private float mps = -1f;
    private long fixAtMs = 0;
    private boolean running;

    private final LocationListener gps = new LocationListener() {
        @Override public void onLocationChanged(Location loc) {
            if (loc != null && loc.hasSpeed()) {
                mps = loc.getSpeed();
                fixAtMs = SystemClock.elapsedRealtime();
                if (listener != null) listener.onSpeedChanged();
            }
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { mps = -1f; }
    };

    public SpeedHelper(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
    }

    public boolean hasPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean gpsEnabled() {
        try {
            return lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    public void start() {
        if (running || lm == null || !hasPermission()) return;
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, gps, Looper.getMainLooper());
            running = true;
        } catch (SecurityException e) {
            running = false;
        } catch (IllegalArgumentException e) {
            running = false; // unit has no GPS provider
        }
    }

    public void stop() {
        if (!running) return;
        try { lm.removeUpdates(gps); } catch (Exception ignored) { }
        running = false;
    }

    /** Current speed rounded to whole mph, or -1 when unknown. */
    public int mph() {
        if (mps < 0 || SystemClock.elapsedRealtime() - fixAtMs > STALE_MS) return -1;
        return Math.round(mps * MPS_TO_MPH);
    }
}
