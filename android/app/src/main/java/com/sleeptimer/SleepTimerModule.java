package com.sleeptimer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.sleeptimer.service.AppShutdown;
import com.sleeptimer.service.AudibleController;
import com.sleeptimer.service.AudibleListenerService;
import com.sleeptimer.service.GainController;
import com.sleeptimer.service.SleepTimer;

import java.util.Locale;

/** JS bridge for the native sleep timer, Audible state and gain control. */
public class SleepTimerModule extends ReactContextBaseJavaModule implements SleepTimer.Listener {

    public static final String NAME = "SleepTimer";
    public static final String EVENT_STATE_CHANGED = "SleepTimerStateChanged";
    private static final String TAG = "SleepTimerModule";

    private final Handler main = new Handler(Looper.getMainLooper());

    public SleepTimerModule(ReactApplicationContext context) {
        super(context);
        main.post(() -> SleepTimer.get(context).addListener(this));
    }

    @NonNull
    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void invalidate() {
        Context context = getReactApplicationContext();
        main.post(() -> SleepTimer.get(context).removeListener(this));
        super.invalidate();
    }

    @Override
    public void onSleepTimerChanged() {
        ReactApplicationContext context = getReactApplicationContext();
        if (!context.hasActiveReactInstance()) return;
        try {
            context.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(EVENT_STATE_CHANGED, buildState());
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not emit state", e);
        }
    }

    @ReactMethod
    public void getState(Promise promise) {
        run(promise, () -> GainController.get(ctx()).refresh());
    }

    @ReactMethod
    public void setDuration(double minutes, Promise promise) {
        run(promise, () -> SleepTimer.get(ctx()).setDuration((int) Math.round(minutes)));
    }

    @ReactMethod
    public void start(Promise promise) {
        run(promise, () -> SleepTimer.get(ctx()).start());
    }

    @ReactMethod
    public void stop(Promise promise) {
        run(promise, () -> SleepTimer.get(ctx()).stop());
    }

    @ReactMethod
    public void resume(Promise promise) {
        run(promise, () -> SleepTimer.get(ctx()).resume());
    }

    @ReactMethod
    public void setGain(double db, Promise promise) {
        run(promise, () -> GainController.get(ctx()).setGainDb((float) db));
    }

    @ReactMethod
    public void setGainLegacyMode(boolean enabled, Promise promise) {
        run(promise, () -> GainController.get(ctx()).setLegacyMode(enabled));
    }

    @ReactMethod
    public void openNotificationAccessSettings(Promise promise) {
        main.post(() -> {
            try {
                Intent intent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                    new ComponentName(ctx(), AudibleListenerService.class).flattenToString());
                } else {
                    intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                }
                startSettings(intent);
                promise.resolve(null);
            } catch (RuntimeException e) {
                try {
                    startSettings(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                    promise.resolve(null);
                } catch (RuntimeException fallback) {
                    promise.reject("E_SETTINGS", fallback);
                }
            }
        });
    }

    @ReactMethod
    public void close(Promise promise) {
        promise.resolve(null);
        AppShutdown.closeApp(ctx(), null);
    }

    private void startSettings(Intent intent) {
        Activity activity = getCurrentActivity();
        if (activity != null) {
            activity.startActivity(intent);
        } else {
            ctx().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    private Context ctx() {
        return getReactApplicationContext().getApplicationContext();
    }

    /** Runs an action on the main thread, then resolves with the resulting state. */
    private void run(Promise promise, Runnable action) {
        main.post(() -> {
            try {
                action.run();
                promise.resolve(buildState());
            } catch (RuntimeException e) {
                promise.reject("E_SLEEP_TIMER", e);
            }
        });
    }

    private WritableMap buildState() {
        Context context = ctx();
        SleepTimer timer = SleepTimer.get(context);
        AudibleController audible = AudibleController.get(context);
        GainController gain = GainController.get(context);

        WritableMap map = Arguments.createMap();
        map.putString("state", timer.getState().name().toLowerCase(Locale.ROOT));
        map.putDouble("remainingMs", timer.getRemainingMs());
        map.putInt("durationMinutes", timer.getDurationMinutes());
        map.putInt("minMinutes", SleepTimer.MIN_MINUTES);
        map.putInt("maxMinutes", SleepTimer.MAX_MINUTES);
        map.putBoolean("audiblePlaying", audible.isAudiblePlaying());
        map.putBoolean("audibleInstalled", audible.isAudibleInstalled());
        map.putBoolean("notificationAccess", AudibleController.hasNotificationAccess(context));
        map.putDouble("gainDb", gain.getGainDb());
        map.putDouble("gainMinDb", GainController.MIN_DB);
        map.putDouble("gainMaxDb", GainController.MAX_DB);
        map.putBoolean("gainSupported", GainController.isSupported());
        map.putBoolean("gainApplied", gain.isApplied());
        map.putBoolean("gainOverridden", gain.isOverridden());
        map.putBoolean("gainLegacyMode", gain.isLegacyMode());
        map.putBoolean("gainDetectionGranted", gain.hasDetectionPermission());
        map.putInt("gainSessionCount", gain.getSessionCount());
        map.putString("gainError", gain.getError());
        return map;
    }
}
