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
}
