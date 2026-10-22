package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing itself. Android lets an app see other apps' media sessions (what Spotify is playing, see NowPlaying)
 * only while it has an enabled notification listener. Notifications are never read. Enabled over adb:
 *   cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.NowPlayingListener
 */
public class NowPlayingListener extends NotificationListenerService {
}
