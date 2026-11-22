package com.geely.drivemem.services;

import android.service.notification.NotificationListenerService;

/** Only a key: Android lets an app read other apps' media sessions
 * (MediaSessionManager.getActiveSessions) when one of its components is an
 * enabled notification listener. This is that component; it reads no
 * notifications. ModeHelper enables it (MediaAccessReceiver) when the
 * driver turns on Bluetooth music. See util/CarMediaSessions. */
public class MediaAccessListener extends NotificationListenerService { }
