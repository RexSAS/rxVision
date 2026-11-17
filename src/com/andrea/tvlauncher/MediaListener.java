package com.andrea.tvlauncher;

import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.service.notification.NotificationListenerService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Android only lists other apps' media sessions (for the now-playing widget) to apps with an
 * enabled notification listener. Enable once:
 *   adb shell cmd notification allow_listener com.andrea.tvlauncher/com.andrea.tvlauncher.MediaListener
 * Running all the time, it also notes what each app plays into History, even while the launcher
 * is in the background.
 */
public class MediaListener extends NotificationListenerService {

    private MediaSessionManager sessions;
    private final Map<MediaSession.Token, MediaController.Callback> watched = new HashMap<>();
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::watch;

    @Override
    public void onListenerConnected() {
        sessions = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName me = new ComponentName(this, MediaListener.class);
        sessions.addOnActiveSessionsChangedListener(changed, me);
        watch(sessions.getActiveSessions(me));
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        watched.clear();
    }

    private void watch(List<MediaController> list) {
        if (list == null) return;
        for (MediaController c : list) {
            if (watched.containsKey(c.getSessionToken())) continue;
            String pkg = c.getPackageName();
            MediaController.Callback cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) { History.record(MediaListener.this, pkg, m); }
                @Override public void onSessionDestroyed() {
                    c.unregisterCallback(this);
                    watched.remove(c.getSessionToken());
                }
            };
            watched.put(c.getSessionToken(), cb);
            c.registerCallback(cb);
            History.record(this, pkg, c.getMetadata());
        }
    }
}
