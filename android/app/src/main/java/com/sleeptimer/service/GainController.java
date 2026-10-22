package com.sleeptimer.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.RequiresApi;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Attenuates Audible's audio sessions; the device-dependent global mix is opt-in only. */
public final class GainController {

    public static final float MIN_DB = -40f;
    public static final float MAX_DB = 0f;
    private static final float STEP_DB = 0.5f;
    private static final String KEY_GAIN_DB = "gain_db";
    private static final String KEY_LEGACY = "gain_legacy";
    private static final String TAG = "GainController";
    private static GainController instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable private final AudibleAudioSessions discovery;
    private final Map<Integer, DynamicsProcessing> effects = new HashMap<>();
    private Set<Integer> sessions = new HashSet<>();

    private float gainDb;
    private boolean active;
    private boolean legacy;
    private boolean applied;
    private boolean overridden;
    @Nullable private String error;
    @Nullable private String detectionError;

    public static synchronized GainController get(Context context) {
        if (instance == null) instance = new GainController(context.getApplicationContext());
        return instance;
    }

    private GainController(Context context) {
        appContext = context;
        prefs = context.getSharedPreferences(SleepTimer.PREFS, Context.MODE_PRIVATE);
        gainDb = normalize(prefs.getFloat(KEY_GAIN_DB, MAX_DB));
        legacy = prefs.getBoolean(KEY_LEGACY, false);
        discovery = isSupported() ? new AudibleAudioSessions(context, (next, failure) -> {
            sessions = next;
            detectionError = failure;
            apply();
        }) : null;
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    public float getGainDb() {
        return gainDb;
    }

    /** Engine configuration was verified; this cannot measure the phone's audible output. */
    public boolean isApplied() {
        return applied;
    }

    public boolean isOverridden() {
        return overridden;
    }

    public boolean isLegacyMode() {
        return legacy;
    }

    public int getSessionCount() {
        return legacy ? 0 : sessions.size();
    }

    public boolean hasDetectionPermission() {
        return isSupported() && AudibleAudioSessions.hasDetectionPermission(appContext);
    }

    @Nullable
    public String getError() {
        return error != null ? error : detectionError;
    }

    public void setGainDb(float db) {
        gainDb = normalize(db);
        prefs.edit().putFloat(KEY_GAIN_DB, gainDb).apply();
        updateDiscovery();
        apply();
    }

    public void setLegacyMode(boolean enabled) {
        if (legacy != enabled) {
            releaseEffects();
            legacy = enabled;
            prefs.edit().putBoolean(KEY_LEGACY, legacy).apply();
        }
        refresh();
    }

    public void refresh() {
        updateDiscovery();
        apply();
    }

    public void activate() {
        active = true;
        refresh();
    }

    public void release() {
        active = false;
        if (isSupported() && discovery != null) discovery.stop();
        sessions.clear();
        releaseEffects();
        detectionError = null;
        error = null;
    }

    private void updateDiscovery() {
        if (!isSupported() || discovery == null) return;
        if (active && !legacy) {
            discovery.start(gainDb < MAX_DB);
        } else {
            discovery.stop();
            sessions.clear();
            detectionError = null;
        }
    }

    private static float normalize(float db) {
        if (Float.isNaN(db)) return MAX_DB;
        float clamped = Math.max(MIN_DB, Math.min(MAX_DB, db));
        return Math.round(clamped / STEP_DB) * STEP_DB;
    }

    private void apply() {
        error = null;
        applied = false;
        overridden = false;
        if (!active || !isSupported() || gainDb >= MAX_DB) {
            releaseEffects();
            changed();
            return;
        }
        Set<Integer> targets = new HashSet<>(sessions);
        if (legacy) targets.add(0);
        for (int session : new HashSet<>(effects.keySet())) {
            if (!targets.contains(session)) releaseEffect(session);
        }
        boolean allConfigured = !targets.isEmpty();
        for (int session : targets) {
            try {
                if (!configureEffect(session)) allConfigured = false;
            } catch (RuntimeException first) {
                Log.w(TAG, "Gain effect failed for session " + session + ", recreating", first);
                releaseEffect(session);
                try {
                    if (!configureEffect(session)) allConfigured = false;
                } catch (RuntimeException second) {
                    Log.e(TAG, "Gain effect unavailable for session " + session, second);
                    releaseEffect(session);
                    error = "Android could not configure attenuation for this audio session.";
                    allConfigured = false;
                }
            }
        }
        applied = allConfigured && !overridden && error == null;
        changed();
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private boolean configureEffect(int session) {
        DynamicsProcessing effect = effects.get(session);
        if (effect == null) {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2, false, 0, false, 0, false, 0, false)
                    .setInputGainAllChannelsTo(gainDb)
                    .build();
            effect = new DynamicsProcessing(0, session, config);
            effects.put(session, effect);
            effect.setControlStatusListener((source, control) -> handler.post(() -> {
                if (effects.get(session) == source) apply();
            }));
            effect.setEnableStatusListener((source, enabled) -> handler.post(() -> {
                if (effects.get(session) == source && !enabled) {
                    applied = false;
                    changed();
                }
            }));
        }
        if (!effect.hasControl()) {
            overridden = true;
            return false;
        }
        effect.setInputGainAllChannelsTo(gainDb);
        if (effect.setEnabled(true) != AudioEffect.SUCCESS || !effect.getEnabled()) {
            throw new IllegalStateException("Audio effect was not enabled");
        }
        int channels = effect.getChannelCount();
        if (channels <= 0) throw new IllegalStateException("Audio effect has no channels");
        for (int channel = 0; channel < channels; channel++) {
            float actual = effect.getInputGainByChannelIndex(channel);
            if (!Float.isFinite(actual) || Math.abs(actual - gainDb) > 0.05f) {
                throw new IllegalStateException("Android did not retain the requested gain");
            }
        }
        return true;
    }

    private void releaseEffects() {
        for (int session : new HashSet<>(effects.keySet())) releaseEffect(session);
        applied = false;
        overridden = false;
    }

    private void releaseEffect(int session) {
        DynamicsProcessing effect = effects.remove(session);
        if (effect == null) return;
        try {
            // Releasing a non-controlling handle must not disable another app's effect.
            if (effect.hasControl()) effect.setEnabled(false);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not disable gain effect", e);
        }
        try {
            effect.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not release gain effect", e);
        }
    }

    private void changed() {
        if (!AppShutdown.isClosing()) SleepTimer.get(appContext).notifyChanged();
    }
}
