package com.sleeptimer.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.sleeptimer.MainActivity;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the app alive while it is open so the timer can run with the screen off, and shows the
 * timer notification. It is never restarted by the system (START_NOT_STICKY) and is only started
 * from the visible app, so once closed it stays closed.
 */
public class ForegroundService extends Service implements SleepTimer.Listener {

    public static final String CHANNEL_ID = "sleep_timer";
    private static final String LEGACY_CHANNEL_ID = "SleepTimerForegroundService";
    public static final int NOTIFICATION_ID = 1;
    private static final int[] PRESET_MINUTES = {15, 45};
    private static final String TAG = "ForegroundService";

    @Nullable private static volatile ForegroundService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    public static void start(Context context) {
        if (AppShutdown.isClosing()) return;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ForegroundService.class));
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not start service", e);
        }
    }

    static void stop(Context context) {
        ForegroundService service = instance;
        if (service != null) {
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE);
            service.stopSelf();
        }
        context.stopService(new Intent(context, ForegroundService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        enterForeground();
        if (AppShutdown.isClosing()) return;
        SleepTimer.get(this).addListener(this);
        AudibleController.get(this).startObserving();
        GainController.get(this).activate();
        AudibleListenerService.rebind(this);
        HeadsetButtons.get(this).startObserving();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() call must be followed by startForeground().
        enterForeground();
        if (AppShutdown.isClosing()) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        SleepTimer timer = SleepTimer.get(this);
        timer.removeListener(this);
        // Without the service nothing may keep running in the background.
        timer.shutdown();
        AudibleController.get(this).cancelPendingPause();
        AudibleController.get(this).stopObserving();
        GainController.get(this).release();
        AudibleListenerService.unbind(this);
        HeadsetButtons.get(this).stopObserving();
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSleepTimerChanged() {
        if (instance != this || AppShutdown.isClosing()) return;
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS denied; the service keeps running without a visible notification.
            Log.w(TAG, "Notification permission missing", e);
        }
    }

    private void enterForeground() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                : 0;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not enter foreground", e);
            stopSelf();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Sleep timer", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows the sleep timer and its controls");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
    }

    private Notification buildNotification() {
        SleepTimer timer = SleepTimer.get(this);
        AudibleController audible = AudibleController.get(this);
        long remainingMs = timer.getRemainingMs();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(activityIntent());

        String text;
        switch (timer.getState()) {
            case RUNNING: {
                long endWallClock = System.currentTimeMillis() + remainingMs;
                String at = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault())
                        .format(new Date(endWallClock));
                builder.setContentTitle("Sleep timer running")
                        .setWhen(endWallClock)
                        .setShowWhen(true)
                        .setUsesChronometer(true)
                        .setChronometerCountDown(true);
                text = "Audible pauses at " + at;
                break;
            }
            case PAUSED:
                builder.setContentTitle("Sleep timer paused").setShowWhen(false);
                text = formatDuration(remainingMs) + " left - continues when Audible plays";
                break;
            default:
                builder.setContentTitle("Sleep timer ready (" + timer.getDurationMinutes() + " min)")
                        .setShowWhen(false);
                text = audible.isObserving()
                        ? "Starts when Audible plays"
                        : "Open the app to allow notification access";
                break;
        }
        builder.setContentText(text);

        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closeIntent());
        for (int i = 0; i < PRESET_MINUTES.length; i++) {
            builder.addAction(android.R.drawable.ic_menu_recent_history,
                    PRESET_MINUTES[i] + " min", presetIntent(i, PRESET_MINUTES[i]));
        }
        return builder.build();
    }

    private PendingIntent activityIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent closeIntent() {
        Intent intent = new Intent(this, CloseAppReceiver.class)
                .setAction(CloseAppReceiver.ACTION_CLOSE_APP);
        return PendingIntent.getBroadcast(this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent presetIntent(int index, int minutes) {
        Intent intent = new Intent(this, PresetTimerReceiver.class)
                .setAction(PresetTimerReceiver.ACTION_SET_TIMER)
                .putExtra(PresetTimerReceiver.EXTRA_MINUTES, minutes);
        return PendingIntent.getBroadcast(this, 100 + index, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String formatDuration(long ms) {
        long totalSeconds = (ms + 999) / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%d:%02d", m, s);
    }
}
