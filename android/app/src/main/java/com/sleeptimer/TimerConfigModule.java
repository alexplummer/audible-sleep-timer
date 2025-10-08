package com.sleeptimer;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;

public class TimerConfigModule extends ReactContextBaseJavaModule {
    private static int timerDurationSeconds = 15 * 60; // Default 15 minutes in seconds
    
    public TimerConfigModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return "TimerConfig";
    }

    @ReactMethod
    public void setTimerDuration(int minutes, Promise promise) {
        try {
            timerDurationSeconds = minutes * 60;
            
            // If timer is currently running, update it with the new duration
            updateRunningTimer();
            
            promise.resolve("Timer duration set to " + minutes + " minutes");
        } catch (Exception e) {
            promise.reject("ERROR", "Failed to set timer duration", e);
        }
    }
    
    private void updateRunningTimer() {
        try {
            // Use reflection to call MediaButtonReceiver's updateRunningTimer method
            Class<?> clazz = Class.forName("com.sleeptimer.service.MediaButtonReceiver");
            clazz.getMethod("updateRunningTimerDuration", ReactApplicationContext.class)
                 .invoke(null, getReactApplicationContext());
        } catch (Exception e) {
            // Log but don't fail - this just means timer update isn't available
            android.util.Log.d("TimerConfigModule", "Could not update running timer: " + e.getMessage());
        }
    }

    @ReactMethod
    public void getTimerDuration(Promise promise) {
        try {
            int minutes = timerDurationSeconds / 60;
            promise.resolve(minutes);
        } catch (Exception e) {
            promise.reject("ERROR", "Failed to get timer duration", e);
        }
    }
    
    // Static method for MediaButtonReceiver to access timer duration
    public static int getTimerDurationSeconds() {
        return timerDurationSeconds;
    }
    
    // Static method for notification preset buttons
    public static void setTimerDurationFromNotification(int minutes) {
        try {
            timerDurationSeconds = minutes * 60;
            android.util.Log.d("TimerConfigModule", "Timer duration set to " + minutes + " minutes from notification");
            
            // Update running timer if active
            updateRunningTimerStatic();
        } catch (Exception e) {
            android.util.Log.e("TimerConfigModule", "Failed to set timer duration from notification", e);
        }
    }
    
    private static void updateRunningTimerStatic() {
        try {
            // Use reflection to call MediaButtonReceiver's updateRunningTimer method
            Class<?> clazz = Class.forName("com.sleeptimer.service.MediaButtonReceiver");
            // We need to pass a context, but we don't have access to it here
            // The MediaButtonReceiver should handle null context gracefully
            clazz.getMethod("updateRunningTimerDuration", android.content.Context.class)
                 .invoke(null, (android.content.Context) null);
        } catch (Exception e) {
            android.util.Log.d("TimerConfigModule", "Could not update running timer from static method: " + e.getMessage());
        }
    }
}