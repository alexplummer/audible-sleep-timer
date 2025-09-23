package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;

public class PauseAudibleReceiver extends BroadcastReceiver {
    private static final String TAG = "PauseAudibleReceiver";
    
    public PauseAudibleReceiver() {
        Log.d(TAG, "PauseAudibleReceiver instantiated");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Attempting to pause Audible playback");
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            try {
                // Request audio focus to signal other apps to pause
                AudioManager.OnAudioFocusChangeListener afChangeListener = new AudioManager.OnAudioFocusChangeListener() {
                    public void onAudioFocusChange(int focusChange) {
                        // Do nothing - we just want to trigger pause in other apps
                    }
                };
                
                int result = audioManager.requestAudioFocus(afChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);

                long eventTime = System.currentTimeMillis();
                
                // Send pause command
                sendMediaKeyEvent(audioManager, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, eventTime);
                
                // Also try stop as fallback
                Thread.sleep(100);
                sendMediaKeyEvent(audioManager, android.view.KeyEvent.KEYCODE_MEDIA_STOP, eventTime);
                
                Log.d(TAG, "Media pause commands sent");
            } catch (Exception e) {
                Log.e(TAG, "Error sending media control events", e);
            }
        } else {
            Log.e(TAG, "Could not get AudioManager system service");
        }
    }

    private void sendMediaKeyEvent(AudioManager audioManager, int keyCode, long eventTime) {
        try {
            android.view.KeyEvent downEvent = new android.view.KeyEvent(
                eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
            android.view.KeyEvent upEvent = new android.view.KeyEvent(
                eventTime, eventTime, android.view.KeyEvent.ACTION_UP, keyCode, 0);

            audioManager.dispatchMediaKeyEvent(downEvent);
            Thread.sleep(50); // Small delay between down and up events
            audioManager.dispatchMediaKeyEvent(upEvent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send media key event: " + keyCode, e);
        }
    }
}
