package com.sleeptimer.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class ForegroundService extends Service {
    private static final String CHANNEL_ID = "SleepTimerForegroundService";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "ForegroundService";
    private static ForegroundService instance;
    
    public ForegroundService() {
        Log.d(TAG, "ForegroundService constructor called");
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "ForegroundService onCreate called");
        super.onCreate();
        instance = this;
        createNotificationChannel();
        MediaButtonReceiver.register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ForegroundService onStartCommand called");
        
        // Create initial notification
        updateNotificationInternal("Ready to handle media button presses", 0);
        
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "ForegroundService onDestroy called");
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Sleep Timer Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Keeps sleep timer active for media button handling");
                channel.setShowBadge(false);
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error creating notification channel", e);
            }
        }
    }
    
    public static void updateNotification(String status, long remainingTimeMs) {
        if (instance != null) {
            instance.updateNotificationInternal(status, remainingTimeMs);
        }
    }
    
    private void updateNotificationInternal(String status, long remainingTimeMs) {
        try {
            // Create close app intent
            android.content.Intent closeIntent = new android.content.Intent(this, CloseAppReceiver.class);
            closeIntent.setAction(CloseAppReceiver.ACTION_CLOSE_APP);
            android.app.PendingIntent closePendingIntent = android.app.PendingIntent.getBroadcast(
                this, 1, closeIntent, android.app.PendingIntent.FLAG_IMMUTABLE
            );

            String contentText;
            if (remainingTimeMs > 0) {
                String timeRemaining = formatTime(remainingTimeMs);
                contentText = status + " - " + timeRemaining + " remaining";
            } else {
                contentText = status;
            }

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Sleep Timer")
                    .setContentText(contentText)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setAutoCancel(false)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close App", closePendingIntent);

            // Add preset timer buttons
            int[] presetMinutes = {15, 45};
            for (int i = 0; i < presetMinutes.length; i++) {
                int minutes = presetMinutes[i];
                android.content.Intent presetIntent = new android.content.Intent(this, PresetTimerReceiver.class);
                presetIntent.setAction(PresetTimerReceiver.ACTION_SET_TIMER);
                presetIntent.putExtra(PresetTimerReceiver.EXTRA_MINUTES, minutes);
                
                android.app.PendingIntent presetPendingIntent = android.app.PendingIntent.getBroadcast(
                    this, 100 + i, presetIntent, android.app.PendingIntent.FLAG_IMMUTABLE
                );
                
                builder.addAction(android.R.drawable.ic_menu_recent_history, minutes + "m", presetPendingIntent);
            }

            android.content.Intent notificationIntent = new android.content.Intent(this, com.sleeptimer.MainActivity.class);
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, notificationIntent, android.app.PendingIntent.FLAG_IMMUTABLE
            );
            builder.setContentIntent(pendingIntent);
            
            // Set custom big text style to show "Reset Timer:" header
            NotificationCompat.BigTextStyle bigTextStyle = new NotificationCompat.BigTextStyle()
                .setBigContentTitle("Sleep Timer")
                .bigText(contentText + "\n\nReset Timer: Use buttons below to change duration");
            builder.setStyle(bigTextStyle);
            
            android.app.Notification notification = builder.build();
            
            // Use startForeground for the first notification, notify for updates
            if (remainingTimeMs == 0 && "Ready to handle media button presses".equals(status)) {
                startForeground(NOTIFICATION_ID, notification);
            } else {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.notify(NOTIFICATION_ID, notification);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification", e);
        }
    }
    
    private String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes % 60, seconds % 60);
        } else {
            return String.format("%d:%02d", minutes, seconds % 60);
        }
    }
}
