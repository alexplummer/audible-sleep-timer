package com.sleeptimer.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Fine-grained output attenuation that works below the lowest system volume step.
 *
 * Applies a DynamicsProcessing input gain on the global output mix (session 0), the same
 * technique "Precise Volume" uses. Android volume levels are untouched. The effect exists only
 * while the app's service is running and is released when the app is closed.
 * All methods must be called on the main thread.
 */
public final class GainController {

    public static final float MIN_DB = -40f;
    public static final float MAX_DB = 0f;
    private static final float STEP_DB = 0.5f;
    private static final String KEY_GAIN_DB = "gain_db";
    private static final int EFFECT_PRIORITY = Integer.MAX_VALUE;
    private static final String TAG = "GainController";

    private static GainController instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float gainDb;
    private boolean active;
    private boolean hasControl = true;
    @Nullable private String error;
    @Nullable private DynamicsProcessing effect;

    public static synchronized GainController get(Context context) {
        if (instance == null) {
            instance = new GainController(context.getApplicationContext());
        }
        return instance;
    }

    private GainController(Context context) {
        appContext = context;
        prefs = context.getSharedPreferences(SleepTimer.PREFS, Context.MODE_PRIVATE);
        gainDb = normalize(prefs.getFloat(KEY_GAIN_DB, MAX_DB));
    }

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    public float getGainDb() {
        return gainDb;
    }

    /** True when attenuation is currently being applied to the audio output. */
    public boolean isApplied() {
        return effect != null && hasControl && error == null;
    }

    /** True when another app (e.g. Precise Volume) has taken control of the effect. */
    public boolean isOverridden() {
        return effect != null && !hasControl;
    }

    @Nullable
    public String getError() {
        return error;
    }

    public void setGainDb(float db) {
        gainDb = normalize(db);
        prefs.edit().putFloat(KEY_GAIN_DB, gainDb).apply();
        apply();
    }

    /** Called when the service starts. */
    public void activate() {
        active = true;
        apply();
    }

    /** Removes all attenuation and frees the effect. */
    public void release() {
        active = false;
        releaseEffect();
        error = null;
    }

    private static float normalize(float db) {
        if (Float.isNaN(db)) return MAX_DB;
        float clamped = Math.max(MIN_DB, Math.min(MAX_DB, db));
        return Math.round(clamped / STEP_DB) * STEP_DB;
    }

    private void apply() {
        if (!active || !isSupported() || gainDb >= MAX_DB) {
            releaseEffect();
            error = null;
            changed();
            return;
        }
        try {
            applyToEffect();
        } catch (RuntimeException first) {
            // The effect dies if the audio server restarts; recreate it once.
            Log.w(TAG, "Gain effect failed, recreating", first);
            releaseEffect();
            try {
                applyToEffect();
            } catch (RuntimeException second) {
                Log.e(TAG, "Gain effect unavailable", second);
                releaseEffect();
                error = second.getMessage() != null ? second.getMessage() : second.getClass().getSimpleName();
            }
        }
        changed();
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private void applyToEffect() {
        if (effect == null) {
            effect = createEffect();
        }
        if (!hasControl) {
            error = null;
            return;
        }
        effect.setInputGainAllChannelsTo(gainDb);
        if (!effect.getEnabled()) {
            effect.setEnabled(true);
        }
        error = null;
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private DynamicsProcessing createEffect() {
        // Stereo, every processing stage off: only the input gain is used.
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, false, 0, false, 0, false, 0, false)
                .setInputGainAllChannelsTo(gainDb)
                .build();
        DynamicsProcessing dp = new DynamicsProcessing(EFFECT_PRIORITY, 0, config);
        hasControl = dp.hasControl();
        dp.setControlStatusListener((e, controlGranted) -> handler.post(() -> {
            if (e != effect) return;
            hasControl = controlGranted;
            if (controlGranted) {
                apply();
            } else {
                changed();
            }
        }));
        return dp;
    }

    private void releaseEffect() {
        if (effect != null) {
            try {
                effect.setEnabled(false);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not disable gain effect", e);
            }
            try {
                effect.release();
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not release gain effect", e);
            }
            effect = null;
        }
        hasControl = true;
    }

    private void changed() {
        if (!AppShutdown.isClosing()) {
            SleepTimer.get(appContext).notifyChanged();
        }
    }
}
