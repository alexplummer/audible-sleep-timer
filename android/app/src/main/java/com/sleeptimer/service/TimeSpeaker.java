package com.sleeptimer.service;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TtsSpan;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Calendar;
import java.util.Locale;

/** Speaks the current 24-hour time over the media output at Audible's attenuated level. */
public final class TimeSpeaker implements TextToSpeech.OnInitListener {

    private static final String TAG = "TimeSpeaker";
    private static TimeSpeaker instance;

    private final Context appContext;
    @Nullable private TextToSpeech tts;
    private boolean ready;
    private boolean pending;

    public static synchronized TimeSpeaker get(Context context) {
        if (instance == null) instance = new TimeSpeaker(context.getApplicationContext());
        return instance;
    }

    private TimeSpeaker(Context context) {
        appContext = context;
    }

    public void speakTime() {
        if (tts == null) {
            pending = true;
            tts = new TextToSpeech(appContext, this);
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            return;
        }
        if (!ready) {
            pending = true;
            return;
        }
        speakNow();
    }

    public void release() {
        pending = false;
        ready = false;
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (RuntimeException e) {
                Log.w(TAG, "TTS shutdown failed", e);
            }
            tts = null;
        }
    }

    @Override
    public void onInit(int status) {
        if (tts == null) return;
        if (status != TextToSpeech.SUCCESS) {
            ButtonLog.get(appContext).add("TIME text-to-speech unavailable (" + status + ")");
            release();
            return;
        }
        ready = true;
        if (pending) speakNow();
    }

    private void speakNow() {
        pending = false;
        if (tts == null) return;
        Calendar now = Calendar.getInstance();
        int hours = now.get(Calendar.HOUR_OF_DAY);
        int minutes = now.get(Calendar.MINUTE);
        String text = timeText(hours, minutes);
        SpannableString spoken = new SpannableString(text);
        spoken.setSpan(new TtsSpan.TimeBuilder(hours, minutes).build(), 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume());
        int result = tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, "time");
        ButtonLog.get(appContext).add("TIME " + text
                + (result == TextToSpeech.SUCCESS ? "" : " (speak failed)"));
    }

    static String timeText(int hours, int minutes) {
        return String.format(Locale.US, "%d:%02d", hours, minutes);
    }

    /** Matches the speech to Audible's level when session gain is attenuating it. */
    private float volume() {
        GainController gain = GainController.get(appContext);
        if (!gain.isApplied() || gain.isLegacyMode()) return 1f;
        return (float) Math.pow(10, gain.getGainDb() / 20.0);
    }
}
