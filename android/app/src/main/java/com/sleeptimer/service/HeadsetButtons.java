package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
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
 * one step instead. A double-press of volume up speaks the current time.
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

    static final float STEP_DB = 0.5f;
    static final long DOUBLE_TAP_MS = 700L;
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
    private long lastUpAt = Long.MIN_VALUE / 2;
    @Nullable private Runnable undoLastUp;
    private static volatile boolean appVisible;

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ButtonLog.VOLUME_CHANGED_ACTION.equals(intent.getAction())) return;
            if (intent.getIntExtra(ButtonLog.EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return;
            onVolumeChanged(intent.getIntExtra(ButtonLog.EXTRA_STREAM_VALUE, -1));
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
        IntentFilter filter = new IntentFilter(ButtonLog.VOLUME_CHANGED_ACTION);
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
        undoLastUp = null;
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
            undoLastUp = null;
            env.log("HEADSET pass-through " + previous + " -> " + value + " (" + passReason + ")");
            return;
        }

        boolean up = value > previous;
        if (up && (value - previous >= 2 || now - lastUpAt <= DOUBLE_TAP_MS)) {
            restore(previous);
            if (undoLastUp != null && value - previous < 2) undoLastUp.run();
            undoLastUp = null;
            lastUpAt = Long.MIN_VALUE / 2;
            env.log("HEADSET double up -> speak time");
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
            undoLastUp = up ? () -> {
                base = previous;
                env.setVolume(previous);
            } : null;
        } else {
            restore(previous);
            float next = Math.max(GainController.MIN_DB,
                    Math.min(GainController.MAX_DB, gain + (up ? STEP_DB : -STEP_DB)));
            env.setGainDb(next);
            env.log("HEADSET " + (up ? "up" : "down") + " -> gain " + format(next));
            undoLastUp = up ? () -> {
                env.setGainDb(gain);
                env.log("HEADSET undo -> gain " + format(gain));
            } : null;
        }
        lastUpAt = up ? now : Long.MIN_VALUE / 2;
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
            ButtonLog.get(context).add(message);
        }

        @Override
        public long now() {
            return SystemClock.uptimeMillis();
        }
    }
}
