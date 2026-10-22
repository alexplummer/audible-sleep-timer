package com.sleeptimer.service;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Exists only so the user can grant notification access, which Android requires before an app
 * may observe and control another app's media session (Audible). No notification data is read.
 *
 * The system keeps approved listeners bound, which would restart the app after it is closed, so
 * the listener unbinds itself whenever the app isn't running.
 */
public class AudibleListenerService extends NotificationListenerService {

    private static final String TAG = "AudibleListener";

    @Nullable private static AudibleListenerService connected;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = this;
        if (AppShutdown.isClosing() || !ForegroundService.isRunning()) {
            unbindSelf();
            return;
        }
        AudibleController.get(this).refresh();
    }

    @Override
    public void onListenerDisconnected() {
        if (connected == this) connected = null;
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Not used.
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Not used.
    }

    private void unbindSelf() {
        try {
            requestUnbind();
        } catch (RuntimeException e) {
            Log.w(TAG, "requestUnbind failed", e);
        }
    }

    /** Asks the system to unbind the listener so it can't restart the closed app. */
    static void unbind(Context context) {
        AudibleListenerService service = connected;
        if (service != null) {
            service.unbindSelf();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                requestUnbind(new ComponentName(context, AudibleListenerService.class));
            } catch (RuntimeException e) {
                Log.w(TAG, "requestUnbind(component) failed", e);
            }
        }
    }

    /** Re-binds after a previous close, once the app is running again. */
    static void rebind(Context context) {
        if (connected != null || !AudibleController.hasNotificationAccess(context)) return;
        try {
            requestRebind(new ComponentName(context, AudibleListenerService.class));
        } catch (RuntimeException e) {
            Log.w(TAG, "requestRebind failed", e);
        }
    }
}
