package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Turns Bluetooth headset volume presses into the app's fine gain steps.
 *
 * <p>Headsets using AVRCP absolute volume send no key events; they change the media volume
 * directly. While Audible plays over Bluetooth, a media-volume change that is not preceded by a
 * phone volume key is treated as a headset press: the system volume is restored and the gain moves
 * one step instead. A double-press of volume down speaks the current time.
 */
public final class HeadsetButtons {

    /** What the controller needs from the device; replaced in tests. */
    interface Env {
        int getVolume();
        int getMaxVolume();
        void setVolume(int volume);
        boolean isBluetoothOutput();
        boolean isAudiblePlaying();
        /** Phone volume keys can be observed: screen off (best effort) or this app visible. */
        boolean isRemapContext();
        float getGainDb();
        void setGainDb(float db);
        void speakTime();
        void log(String message);
        long now();
    }

    static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    static final String EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    static final String EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    static final float STEP_DB = 2f;
    static final long DOUBLE_TAP_MS = 900L;
    /** A repeat in the same direction this soon is the same press (echo or multi-step change). */
    static final long DUPLICATE_MS = 300L;
    static final long PHONE_KEY_WINDOW_MS = 1500L;
    private static final String KEY_ENABLED = "headset_steps_enabled";
    private static final String TAG = "HeadsetButtons";
    private static HeadsetButtons instance;

    private final Env env;
    @Nullable private final Context appContext;
    @Nullable private final SharedPreferences prefs;
    private boolean enabled;
    private boolean observing;
    private int base = -1;
    private long lastPhoneKeyAt = Long.MIN_VALUE / 2;
    private long lastDownAt = Long.MIN_VALUE / 2;
    private long lastPressAt = Long.MIN_VALUE / 2;
    private boolean lastPressUp;
    @Nullable private Runnable undoLastDown;
    private static volatile boolean appVisible;

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!VOLUME_CHANGED_ACTION.equals(intent.getAction())) return;
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return;
            onVolumeChanged(intent.getIntExtra(EXTRA_STREAM_VALUE, -1));
        }
    };

    public static synchronized HeadsetButtons get(Context context) {
        if (instance == null) instance = new HeadsetButtons(context.getApplicationContext());
        return instance;
    }

    private HeadsetButtons(Context context) {
        appContext = context;
        prefs = context.getSharedPreferences(SleepTimer.PREFS, Context.MODE_PRIVATE);
        enabled = prefs.getBoolean(KEY_ENABLED, true);
        env = new DeviceEnv(context);
    }

    @VisibleForTesting
    HeadsetButtons(Env env) {
        this.env = env;
        appContext = null;
        prefs = null;
        enabled = true;
    }

    public static void setAppVisible(boolean visible) {
        appVisible = visible;
    }

    /** The accessibility key filter lets phone volume keys be recognised with the screen off. */
    public static boolean isKeyFilterEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName self = new ComponentName(context, ButtonLoggerService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String part : splitter) {
            if (self.equals(ComponentName.unflattenFromString(part))) return true;
        }
        return false;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (prefs != null) prefs.edit().putBoolean(KEY_ENABLED, value).apply();
        resetBase();
    }

    /** Called on the main thread while the app is open. */
    public void startObserving() {
        if (observing || appContext == null || !GainController.isSupported()) return;
        observing = true;
        resetBase();
        IntentFilter filter = new IntentFilter(VOLUME_CHANGED_ACTION);
        // Protected system broadcast: only the system can send it.
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            appContext.registerReceiver(volumeReceiver, filter);
        }
    }

    public void stopObserving() {
        if (!observing || appContext == null) return;
        observing = false;
        try {
            appContext.unregisterReceiver(volumeReceiver);
        } catch (IllegalArgumentException ignored) {
            // Not registered.
        }
        TimeSpeaker.get(appContext).release();
    }

    /** Phone volume keys (seen by the activity or the accessibility key filter) are left alone. */
    public void onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            lastPhoneKeyAt = env.now();
        }
    }

    @VisibleForTesting
    void resetBase() {
        base = env.getVolume();
        undoLastDown = null;
    }

    @VisibleForTesting
    void onVolumeChanged(int value) {
        if (value < 0) return;
        if (base < 0) base = value;
        if (value == base) return;
        int previous = base;
        long now = env.now();

        String passReason = enabled ? null : "remap off";
        if (passReason == null && now - lastPhoneKeyAt <= PHONE_KEY_WINDOW_MS) passReason = "phone key";
        if (passReason == null && !env.isBluetoothOutput()) passReason = "not Bluetooth";
        if (passReason == null && !env.isAudiblePlaying()) passReason = "Audible not playing";
        if (passReason == null && !env.isRemapContext()) passReason = "screen on, app in background";
        if (passReason != null) {
            base = value;
            undoLastDown = null;
            env.log("HEADSET pass-through " + previous + " -> " + value + " (" + passReason + ")");
            return;
        }

        boolean up = value > previous;
        if (up == lastPressUp && now - lastPressAt < DUPLICATE_MS) {
            restore(previous);
            env.log("HEADSET duplicate " + previous + " -> " + value + " ignored");
            return;
        }
        lastPressAt = now;
        lastPressUp = up;
        if (!up && now - lastDownAt <= DOUBLE_TAP_MS) {
            restore(previous);
            if (undoLastDown != null) undoLastDown.run();
            undoLastDown = null;
            lastDownAt = Long.MIN_VALUE / 2;
            env.log("HEADSET double down -> speak time");
            env.speakTime();
            return;
        }

        float gain = env.getGainDb();
        int max = env.getMaxVolume();
        boolean passToSystem = up
                ? gain >= GainController.MAX_DB || previous == 0
                : gain <= GainController.MIN_DB || previous >= max;
        if (passToSystem) {
            base = value;
            env.log("HEADSET " + (up ? "up" : "down") + " -> system volume " + value + "/" + max);
            undoLastDown = up ? null : () -> {
                base = previous;
                env.setVolume(previous);
            };
        } else {
            restore(previous);
            float next = Math.max(GainController.MIN_DB,
                    Math.min(GainController.MAX_DB, gain + (up ? STEP_DB : -STEP_DB)));
            env.setGainDb(next);
            env.log("HEADSET " + (up ? "up" : "down") + " -> gain " + format(next));
            undoLastDown = up ? null : () -> {
                env.setGainDb(gain);
                env.log("HEADSET undo -> gain " + format(gain));
            };
        }
        lastDownAt = up ? Long.MIN_VALUE / 2 : now;
    }

    private void restore(int volume) {
        base = volume;
        env.setVolume(volume);
    }

    private static String format(float db) {
        return db >= 0 ? "0 dB" : String.format(java.util.Locale.US, "%.1f dB", db);
    }

    private static final class DeviceEnv implements Env {
        private final Context context;

        DeviceEnv(Context context) {
            this.context = context;
        }

        @Nullable
        private AudioManager audio() {
            return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        }

        @Override
        public int getVolume() {
            AudioManager am = audio();
            return am != null ? am.getStreamVolume(AudioManager.STREAM_MUSIC) : -1;
        }

        @Override
        public int getMaxVolume() {
            AudioManager am = audio();
            return am != null ? am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 0;
        }

        @Override
        public void setVolume(int volume) {
            AudioManager am = audio();
            if (am == null) return;
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            } catch (SecurityException e) {
                Log.w(TAG, "Could not restore volume", e);
            }
        }

        @Override
        public boolean isBluetoothOutput() {
            AudioManager am = audio();
            if (am == null) return false;
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = d.getType();
                if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean isAudiblePlaying() {
            return AudibleController.get(context).isAudiblePlaying();
        }

        @Override
        public boolean isRemapContext() {
            if (appVisible) return true;
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && !pm.isInteractive();
        }

        @Override
        public float getGainDb() {
            return GainController.get(context).getGainDb();
        }

        @Override
        public void setGainDb(float db) {
            GainController.get(context).setGainDb(db);
            SleepTimer.get(context).notifyChanged();
        }

        @Override
        public void speakTime() {
            TimeSpeaker.get(context).speakTime();
        }

        @Override
        public void log(String message) {
            Log.i(TAG, message);
        }

        @Override
        public long now() {
            return SystemClock.uptimeMillis();
        }
    }
}
