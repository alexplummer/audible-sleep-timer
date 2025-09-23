package com.sleeptimer;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.sleeptimer.service.PauseAudibleReceiver;

public class MediaControlModule extends ReactContextBaseJavaModule {
    private static final String TAG = "MediaControlModule";

    public MediaControlModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return "MediaControl";
    }

    @ReactMethod
    public void pauseMediaPlayback() {
        Context context = getReactApplicationContext();
        
        // Create an explicit intent
        try {
            Intent explicitIntent = new Intent(context, PauseAudibleReceiver.class);
            explicitIntent.setAction("com.sleeptimer.PAUSE_AUDIBLE");
            explicitIntent.setPackage(context.getPackageName());
            explicitIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(explicitIntent);
        } catch (Exception e) {
            // Ignore exception
        }
        
        // Also send implicit broadcast as backup
        try {
            Intent implicitIntent = new Intent("com.sleeptimer.PAUSE_AUDIBLE");
            implicitIntent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(implicitIntent);
        } catch (Exception e) {
            // Ignore exception
        }

        // Backup media button broadcast removed to prevent infinite loop
        // since MediaButtonReceiver is now working properly
        Log.e(TAG, "!!!!!!!Skipping backup media button broadcast to prevent loop");
    }
}