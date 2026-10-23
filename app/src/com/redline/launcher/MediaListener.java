package com.redline.launcher;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the launcher can be granted "notification access", which is
 * what Android requires before an app may read the now-playing song, artwork
 * and position from other media apps.
 */
public class MediaListener extends NotificationListenerService {
}
