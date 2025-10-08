package com.sleeptimer;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter;
import com.sleeptimer.service.MediaButtonReceiver;

public class MediaButtonEventModule extends ReactContextBaseJavaModule {
    public static final String REACT_CLASS = "MediaButtonEvent";
    private static ReactApplicationContext reactContext;

    public MediaButtonEventModule(ReactApplicationContext context) {
        super(context);
        reactContext = context;
    }

    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Required for RN built in Event Emitter Calls
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Required for RN built in Event Emitter Calls
    }

    @ReactMethod
    public void startAudible(Promise promise) {
        try {
            android.util.Log.d("MediaButtonEventModule", "startAudible called from React Native");
            MediaButtonReceiver.triggerStartAudible(reactContext);
            promise.resolve("Audible started successfully");
        } catch (Exception e) {
            android.util.Log.e("MediaButtonEventModule", "Error starting Audible", e);
            promise.reject("START_AUDIBLE_ERROR", "Failed to start Audible: " + e.getMessage());
        }
    }

    @ReactMethod
    public void closeApp(Promise promise) {
        try {
            android.util.Log.d("MediaButtonEventModule", "closeApp called from React Native");
            
            // Stop the foreground service
            android.content.Intent serviceIntent = new android.content.Intent(reactContext, com.sleeptimer.service.ForegroundService.class);
            reactContext.stopService(serviceIntent);
            
            // Emit close event to JavaScript so it can finish properly
            sendCloseAppEvent();
            
            // Close the main activity after a short delay to allow JS cleanup
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (getCurrentActivity() != null) {
                        getCurrentActivity().finishAffinity();
                    } else {
                        // Alternative method using system exit
                        System.exit(0);
                    }
                } catch (Exception e) {
                    android.util.Log.e("MediaButtonEventModule", "Error finishing activity", e);
                    System.exit(0);
                }
            }, 500);
            
            promise.resolve("App closing");
        } catch (Exception e) {
            android.util.Log.e("MediaButtonEventModule", "Error closing app", e);
            promise.reject("CLOSE_APP_ERROR", "Failed to close app: " + e.getMessage());
        }
    }

    public static void sendPlayButtonEvent() {
        android.util.Log.d("MediaButtonEventModule", "sendPlayButtonEvent called. reactContext is " + (reactContext == null ? "null" : "not null"));
        if (reactContext != null) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("MediaButtonPlayPressed", null);
            android.util.Log.d("MediaButtonEventModule", "MediaButtonPlayPressed event emitted to JS");
        } else {
            android.util.Log.e("MediaButtonEventModule", "Cannot emit event: reactContext is null");
        }
    }

    public static void sendCloseAppEvent() {
        android.util.Log.d("MediaButtonEventModule", "sendCloseAppEvent called. reactContext is " + (reactContext == null ? "null" : "not null"));
        if (reactContext != null) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("AppCloseRequested", null);
            android.util.Log.d("MediaButtonEventModule", "AppCloseRequested event emitted to JS");
        } else {
            android.util.Log.e("MediaButtonEventModule", "Cannot emit event: reactContext is null");
        }
    }

    public static void sendTimerCompletedEvent() {
        android.util.Log.d("MediaButtonEventModule", "sendTimerCompletedEvent called. reactContext is " + (reactContext == null ? "null" : "not null"));
        if (reactContext != null) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("TimerCompleted", null);
            android.util.Log.d("MediaButtonEventModule", "TimerCompleted event emitted to JS");
        } else {
            android.util.Log.e("MediaButtonEventModule", "Cannot emit event: reactContext is null");
        }
    }

    public static void sendTimerPausedEvent() {
        android.util.Log.d("MediaButtonEventModule", "sendTimerPausedEvent called. reactContext is " + (reactContext == null ? "null" : "not null"));
        if (reactContext != null) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("TimerPaused", null);
            android.util.Log.d("MediaButtonEventModule", "TimerPaused event emitted to JS");
        } else {
            android.util.Log.e("MediaButtonEventModule", "Cannot emit event: reactContext is null");
        }
    }

    public static void sendTimerResumedEvent() {
        android.util.Log.d("MediaButtonEventModule", "sendTimerResumedEvent called. reactContext is " + (reactContext == null ? "null" : "not null"));
        if (reactContext != null) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("TimerResumed", null);
            android.util.Log.d("MediaButtonEventModule", "TimerResumed event emitted to JS");
        } else {
            android.util.Log.e("MediaButtonEventModule", "Cannot emit event: reactContext is null");
        }
    }

    public static void sendTimerUpdatedEvent(long newDurationSeconds) {
        android.util.Log.d("MediaButtonEventModule", "sendTimerUpdatedEvent called with duration: " + newDurationSeconds);
        if (reactContext != null) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("TimerUpdated", newDurationSeconds);
            android.util.Log.d("MediaButtonEventModule", "TimerUpdated event emitted to JS");
        } else {
            android.util.Log.e("MediaButtonEventModule", "Cannot emit event: reactContext is null");
        }
    }
}
