package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Diagnostic log of hardware button activity: key events (from the accessibility key filter and
 * the open activity), media-volume changes, audio output devices and Audible play/pause changes.
 * Used to find out what a Bluetooth headset actually sends to the phone.
 */
public final class ButtonLog {

    public interface Listener {
        void onButtonLogEntry(String entry);
    }

    static final String VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION";
    static final String EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    static final String EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE";
    static final String EXTRA_PREV_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE";
    private static final int MAX_ENTRIES = 300;
    private static final String TAG = "ButtonLog";
    private static ButtonLog instance;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> entries = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private boolean observing;

    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!VOLUME_CHANGED_ACTION.equals(intent.getAction())) return;
            int stream = intent.getIntExtra(EXTRA_STREAM_TYPE, -1);
            if (stream != AudioManager.STREAM_MUSIC) return;
            int value = intent.getIntExtra(EXTRA_STREAM_VALUE, -1);
            int prev = intent.getIntExtra(EXTRA_PREV_STREAM_VALUE, -1);
            if (value == prev) return;
            AudioManager am = audioManager();
            int max = am != null ? am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : -1;
            add("VOLUME media " + prev + " -> " + value + "/" + max
                    + (value > prev ? " (up)" : " (down)") + " via " + currentOutput());
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            logDevices("connected", added);
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            logDevices("disconnected", removed);
        }
    };

    public static synchronized ButtonLog get(Context context) {
        if (instance == null) instance = new ButtonLog(context.getApplicationContext());
        return instance;
    }

    private ButtonLog(Context context) {
        appContext = context;
    }

    public synchronized List<String> getEntries() {
        return new ArrayList<>(entries);
    }

    public void clear() {
        synchronized (this) {
            entries.clear();
        }
        add("Log cleared");
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void add(String message) {
        String entry;
        synchronized (this) {
            entry = clock.format(new Date()) + "  " + message;
            entries.addLast(entry);
            while (entries.size() > MAX_ENTRIES) entries.removeFirst();
        }
        Log.i(TAG, message);
        for (Listener listener : listeners) {
            try {
                listener.onButtonLogEntry(entry);
            } catch (RuntimeException e) {
                Log.w(TAG, "Listener failed", e);
            }
        }
    }

    public void logKey(String source, KeyEvent event) {
        String action;
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN: action = "down"; break;
            case KeyEvent.ACTION_UP: action = "up"; break;
            default: action = "action" + event.getAction(); break;
        }
        StringBuilder sb = new StringBuilder("KEY [").append(source).append("] ")
                .append(KeyEvent.keyCodeToString(event.getKeyCode()))
                .append(' ').append(action);
        if (event.getRepeatCount() > 0) sb.append(" repeat=").append(event.getRepeatCount());
        if (event.isLongPress()) sb.append(" longpress");
        sb.append(" scan=").append(event.getScanCode());
        InputDevice device = event.getDevice();
        if (device != null) {
            sb.append(" device=\"").append(device.getName()).append("\" id=").append(device.getId());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                sb.append(device.isExternal() ? " external" : " built-in");
            }
            if (device.isVirtual()) sb.append(" virtual");
        } else {
            sb.append(" deviceId=").append(event.getDeviceId()).append(" (no device)");
        }
        sb.append(" src=0x").append(Integer.toHexString(event.getSource()));
        add(sb.toString());
    }

    /** Registers volume and device observers while the app is open. */
    public void startObserving() {
        main.post(() -> {
            if (observing) return;
            observing = true;
            // Protected system broadcast: only the system can send it.
            IntentFilter filter = new IntentFilter(VOLUME_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                appContext.registerReceiver(volumeReceiver, filter);
            }
            AudioManager am = audioManager();
            if (am != null) am.registerAudioDeviceCallback(deviceCallback, main);
            add("Logging started. Key filter (accessibility): "
                    + (isKeyFilterEnabled(appContext) ? "ON" : "OFF - only keys while app is open")
                    + ". Output: " + currentOutput());
        });
    }

    public void stopObserving() {
        main.post(() -> {
            if (!observing) return;
            observing = false;
            try {
                appContext.unregisterReceiver(volumeReceiver);
            } catch (IllegalArgumentException ignored) {
                // Not registered.
            }
            AudioManager am = audioManager();
            if (am != null) am.unregisterAudioDeviceCallback(deviceCallback);
        });
    }

    public static boolean isKeyFilterEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName self = new ComponentName(context, ButtonLoggerService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        for (String part : splitter) {
            ComponentName name = ComponentName.unflattenFromString(part);
            if (self.equals(name)) return true;
        }
        return false;
    }

    private void logDevices(String verb, AudioDeviceInfo[] devices) {
        for (AudioDeviceInfo d : devices) {
            if (!d.isSink()) continue;
            add("DEVICE " + verb + ": " + describe(d));
        }
    }

    private String currentOutput() {
        AudioManager am = audioManager();
        if (am == null) return "unknown";
        AudioDeviceInfo best = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = d.getType();
            if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) {
                return describe(d);
            }
            if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    || type == AudioDeviceInfo.TYPE_USB_HEADSET) {
                best = d;
            }
        }
        return best != null ? describe(best) : "phone speaker";
    }

    private static String describe(AudioDeviceInfo d) {
        return typeName(d.getType()) + " \"" + d.getProductName() + "\"";
    }

    private static String typeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "BT-A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BT-SCO";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired-headphones";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "wired-headset";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "usb-headset";
            default: return "type" + type;
        }
    }

    @Nullable
    private AudioManager audioManager() {
        return (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
    }
}
