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
    
    public ForegroundService() {
        Log.d(TAG, "ForegroundService constructor called");
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "ForegroundService onCreate called");
        super.onCreate();
        createNotificationChannel();
        MediaButtonReceiver.register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "ForegroundService onStartCommand called");
        
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Sleep Timer")
                    .setContentText("Ready to handle media button presses")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setAutoCancel(false);

            android.content.Intent notificationIntent = new android.content.Intent(this, com.sleeptimer.MainActivity.class);
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, notificationIntent, android.app.PendingIntent.FLAG_IMMUTABLE
            );
            builder.setContentIntent(pendingIntent);
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            Log.e(TAG, "Error creating notification", e);
        }
        
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "ForegroundService onDestroy called");
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
}
