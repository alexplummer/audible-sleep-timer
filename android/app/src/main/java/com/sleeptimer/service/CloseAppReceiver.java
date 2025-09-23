package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class CloseAppReceiver extends BroadcastReceiver {
    private static final String TAG = "CloseAppReceiver";
    public static final String ACTION_CLOSE_APP = "com.sleeptimer.CLOSE_APP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_CLOSE_APP.equals(intent.getAction())) {
            Log.d(TAG, "Close app action received");
            
            try {
                // Stop the foreground service
                Intent serviceIntent = new Intent(context, ForegroundService.class);
                context.stopService(serviceIntent);
                
                // Notify React Native to close the app
                try {
                    Class<?> clazz = Class.forName("com.sleeptimer.MediaButtonEventModule");
                    clazz.getMethod("sendCloseAppEvent").invoke(null);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to notify React Native about close action", e);
                }
                
                // Force close the app by finishing the main activity
                Intent closeIntent = new Intent("com.sleeptimer.CLOSE_MAIN_ACTIVITY");
                closeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                context.sendBroadcast(closeIntent);
                
                Log.d(TAG, "Close app actions completed");
            } catch (Exception e) {
                Log.e(TAG, "Error closing app", e);
            }
        }
    }
}