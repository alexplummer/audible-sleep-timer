package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class PresetTimerReceiver extends BroadcastReceiver {
    private static final String TAG = "PresetTimerReceiver";
    public static final String ACTION_SET_TIMER = "com.sleeptimer.SET_TIMER";
    public static final String EXTRA_MINUTES = "minutes";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "PresetTimerReceiver onReceive called with action: " + intent.getAction());
        
        if (ACTION_SET_TIMER.equals(intent.getAction())) {
            int minutes = intent.getIntExtra(EXTRA_MINUTES, 15);
            Log.d(TAG, "Setting timer to " + minutes + " minutes from notification");
            
            try {
                // Update the timer configuration
                Class<?> clazz = Class.forName("com.sleeptimer.TimerConfigModule");
                java.lang.reflect.Method method = clazz.getMethod("setTimerDurationFromNotification", int.class);
                method.invoke(null, minutes);
                
                // Also notify React Native about the change
                try {
                    Class<?> eventClazz = Class.forName("com.sleeptimer.MediaButtonEventModule");
                    eventClazz.getMethod("sendTimerPresetSelectedEvent", int.class).invoke(null, minutes);
                } catch (Exception e) {
                    Log.w(TAG, "Could not send preset selected event to React Native", e);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to set timer duration", e);
            }
        }
    }
}