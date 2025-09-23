package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.util.Log;
import com.sleeptimer.TimerConfigModule;

public class MediaButtonReceiver extends BroadcastReceiver {
    private static final String TAG = "MediaButtonReceiver";
    private static MediaSession mediaSession;
    private static boolean isRegistered = false;
    private static boolean timerRunning = false;
    private static final long DEBOUNCE_DELAY = 500; // 500ms debounce

    public MediaButtonReceiver() {
        Log.d(TAG, "MediaButtonReceiver constructor called");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "MediaButtonReceiver onReceive called with action: " + intent.getAction());
        
        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            android.view.KeyEvent keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            if (keyEvent != null && keyEvent.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                Log.d(TAG, "Received media button key code: " + keyEvent.getKeyCode());
                
                // Handle play/pause button
                int keyCode = keyEvent.getKeyCode();
                if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY || 
                    keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    keyCode == 127) { // KEYCODE_MEDIA_PLAY_PAUSE constant value
                    Log.d(TAG, "Play/Pause button pressed (keycode: " + keyCode + ")");
                    handlePlayButton(context);
                }
            }
        }
    }

    public static void register(Context context) {
        try {
            if (isRegistered) {
                Log.d(TAG, "MediaButtonReceiver already registered, skipping...");
                return;
            }
            
            Log.d(TAG, "Registering MediaButtonReceiver...");
            
            // Create MediaSession for media button handling
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaSession = new MediaSession(context, "SleepTimerMediaSession");
                mediaSession.setCallback(new MediaSession.Callback() {
                    @Override
                    public void onPlay() {
                        Log.d(TAG, "MediaSession onPlay called");
                        handlePlayButton(context);
                    }
                    
                    @Override
                    public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                        Log.d(TAG, "MediaSession onMediaButtonEvent called");
                        if (mediaButtonEvent != null && Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonEvent.getAction())) {
                            android.view.KeyEvent keyEvent = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                            if (keyEvent != null && keyEvent.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                                Log.d(TAG, "MediaSession received key code: " + keyEvent.getKeyCode());
                                if (keyEvent.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PLAY || 
                                    keyEvent.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                                    keyEvent.getKeyCode() == 127) {
                                    Log.d(TAG, "MediaSession handling play button");
                                    handlePlayButton(context);
                                    return true;
                                }
                            }
                        }
                        return super.onMediaButtonEvent(mediaButtonEvent);
                    }
                });
                
                // Set playback state to make the session active
                PlaybackState playbackState = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_STOPPED, 0, 1.0f)
                    .build();
                mediaSession.setPlaybackState(playbackState);
                mediaSession.setActive(true);
                
                Log.d(TAG, "MediaSession created and activated");
                
                // Start silent audio to maintain media focus
                SilentAudioPlayer.startSilentAudio(context);
            }
            
            // Register BroadcastReceiver
            IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            MediaButtonReceiver receiver = new MediaButtonReceiver();
            
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            
            isRegistered = true;
            Log.d(TAG, "MediaButtonReceiver registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register MediaButtonReceiver", e);
        }
    }
    
    private static void handlePlayButton(Context context) {
        // Prevent overlapping timers
        if (timerRunning) {
            Log.d(TAG, "Timer already running, ignoring button press");
            return;
        }
        
        timerRunning = true;
        Log.d(TAG, "Play button pressed - starting timer");
        
        // Start Audible by sending a play command
        startAudible(context);
        
        // Notify React Native about the button press
        try {
            Class<?> clazz = Class.forName("com.sleeptimer.MediaButtonEventModule");
            clazz.getMethod("sendPlayButtonEvent").invoke(null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to emit play event to JS", e);
        }
        
        // Start the timer (duration can be configured in React Native)
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Log.d(TAG, "Timer completed - pausing Audible");
            
            // Deactivate our media session BEFORE starting pause process
            if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaSession.setActive(false);
                Log.d(TAG, "Deactivated media session before pause process");
            }
            
            pauseAudible(context);
            
            // Reset timer flag
            timerRunning = false;
            Log.d(TAG, "Ready for next button press");
        }, TimerConfigModule.getTimerDurationSeconds() * 1000); // Use configured duration in milliseconds
    }
    
    private static void startAudible(Context context) {
        Log.d(TAG, "Attempting to start Audible playbook");
        
        try {
            // Method 1: Try launching Audible directly
            boolean launched = tryLaunchAudible(context);
            
            if (!launched) {
                // Method 2: Try media control approach with proper audio focus
                tryMediaControlApproach(context);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in startAudible", e);
        }
    }
    
    private static boolean tryLaunchAudible(Context context) {
        try {
            // Try to launch Audible app directly
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage("com.audible.application");
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                Log.d(TAG, "Launched Audible app directly");
                
                // Send play command after a short delay, ensuring our session is inactive
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    // Ensure our media session is completely inactive
                    if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mediaSession.setActive(false);
                        Log.d(TAG, "Deactivated media session before sending play command");
                    }
                    
                    // Small delay to ensure deactivation takes effect
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        sendPlayCommandToAudible(context);
                        
                        // Reactivate our session after sending commands
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                mediaSession.setActive(true);
                                Log.d(TAG, "Reactivated media session after sending play command");
                            }
                        }, 1000);
                    }, 200);
                }, 1500);
                
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch Audible directly", e);
        }
        return false;
    }
    
    private static void tryMediaControlApproach(Context context) {
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                Log.e(TAG, "AudioManager not available");
                return;
            }
            
            // Temporarily deactivate our media session
            if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mediaSession.setActive(false);
                Log.d(TAG, "Deactivated our media session");
            }
            
            // Request audio focus to signal we want to control playback
            AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    Log.d(TAG, "Audio focus changed: " + focusChange);
                }
            };
            
            int result = audioManager.requestAudioFocus(focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.d(TAG, "Audio focus gained, sending play command");
                
                // Send multiple play commands with different timing
                sendPlayCommandToAudible(context);
                
                // Release audio focus after a short delay
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    audioManager.abandonAudioFocus(focusListener);
                    Log.d(TAG, "Released audio focus");
                }, 1000);
            }
            
            // Reactivate our session after a longer delay
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mediaSession.setActive(true);
                    Log.d(TAG, "Reactivated our media session");
                }
            }, 3000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in media control approach", e);
        }
    }
    
    private static void sendPlayCommandToAudible(Context context) {
        try {
            Log.d(TAG, "Sending play command to Audible");
            
            // Method 1: Try sending intent directly to Audible
            try {
                Intent playIntent = new Intent("android.intent.action.MEDIA_BUTTON");
                playIntent.setPackage("com.audible.application");
                
                long eventTime = System.currentTimeMillis();
                android.view.KeyEvent playEvent = new android.view.KeyEvent(
                    eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, 
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0);
                
                playIntent.putExtra(Intent.EXTRA_KEY_EVENT, playEvent);
                context.sendBroadcast(playIntent);
                Log.d(TAG, "Sent direct intent to Audible");
                
                // Also send the key up event
                android.view.KeyEvent playUpEvent = new android.view.KeyEvent(
                    eventTime, eventTime, android.view.KeyEvent.ACTION_UP, 
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0);
                playIntent.putExtra(Intent.EXTRA_KEY_EVENT, playUpEvent);
                context.sendBroadcast(playIntent);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to send direct intent to Audible", e);
            }
            
            // Method 2: Use AudioManager but with our session definitely inactive
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                long eventTime = System.currentTimeMillis();
                
                // Try the most common play key codes
                int[] playCodes = {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                };
                
                for (int keyCode : playCodes) {
                    try {
                        android.view.KeyEvent downEvent = new android.view.KeyEvent(
                            eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                        android.view.KeyEvent upEvent = new android.view.KeyEvent(
                            eventTime, eventTime, android.view.KeyEvent.ACTION_UP, keyCode, 0);

                        audioManager.dispatchMediaKeyEvent(downEvent);
                        Thread.sleep(50);
                        audioManager.dispatchMediaKeyEvent(upEvent);
                        Thread.sleep(100);
                        
                        Log.d(TAG, "Sent media key event: " + keyCode);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to send media key event: " + keyCode, e);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending play commands", e);
        }
    }
    
    private static void sendMediaKeyEvent(AudioManager audioManager, int keyCode, long eventTime) {
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
    
    private static void pauseAudible(Context context) {
        Log.d(TAG, "Attempting to pause Audible");
        
        try {
            // Method 1: Send direct intent to Audible (most reliable)
            try {
                Intent pauseIntent = new Intent("android.intent.action.MEDIA_BUTTON");
                pauseIntent.setPackage("com.audible.application");
                
                long eventTime = System.currentTimeMillis();
                
                // Send pause command
                android.view.KeyEvent pauseEvent = new android.view.KeyEvent(
                    eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, 
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
                pauseIntent.putExtra(Intent.EXTRA_KEY_EVENT, pauseEvent);
                context.sendBroadcast(pauseIntent);
                
                android.view.KeyEvent pauseUpEvent = new android.view.KeyEvent(
                    eventTime, eventTime, android.view.KeyEvent.ACTION_UP, 
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0);
                pauseIntent.putExtra(Intent.EXTRA_KEY_EVENT, pauseUpEvent);
                context.sendBroadcast(pauseIntent);
                
                Log.d(TAG, "Sent direct pause intent to Audible");
                
                // Also try stop command as fallback
                Thread.sleep(200);
                android.view.KeyEvent stopEvent = new android.view.KeyEvent(
                    eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, 
                    android.view.KeyEvent.KEYCODE_MEDIA_STOP, 0);
                pauseIntent.putExtra(Intent.EXTRA_KEY_EVENT, stopEvent);
                context.sendBroadcast(pauseIntent);
                
                android.view.KeyEvent stopUpEvent = new android.view.KeyEvent(
                    eventTime, eventTime, android.view.KeyEvent.ACTION_UP, 
                    android.view.KeyEvent.KEYCODE_MEDIA_STOP, 0);
                pauseIntent.putExtra(Intent.EXTRA_KEY_EVENT, stopUpEvent);
                context.sendBroadcast(pauseIntent);
                
                Log.d(TAG, "Sent direct stop intent to Audible as fallback");
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to send direct pause intent to Audible", e);
            }
            
            // Method 2: Request audio focus to signal pause (without using AudioManager.dispatchMediaKeyEvent)
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                try {
                    AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            Log.d(TAG, "Audio focus change for pause: " + focusChange);
                        }
                    };
                    
                    // Request audio focus to signal other apps to pause
                    int result = audioManager.requestAudioFocus(focusListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                    
                    if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        Log.d(TAG, "Audio focus gained - this should signal Audible to pause");
                        
                        // Release audio focus after a short delay to not interfere
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            audioManager.abandonAudioFocus(focusListener);
                            Log.d(TAG, "Released audio focus after pause signal");
                        }, 1000);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error with audio focus for pause", e);
                }
            }
            
            // Delay before reactivating our session to give Audible time to process pause
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mediaSession.setActive(true);
                    Log.d(TAG, "Reactivated media session after pause delay");
                }
            }, 2000); // Longer delay to ensure pause takes effect
            
        } catch (Exception e) {
            Log.e(TAG, "Error in pauseAudible", e);
        }
    }
    
    // Public method to trigger startAudible from React Native
    public static void triggerStartAudible(Context context) {
        Log.d(TAG, "triggerStartAudible called from React Native module");
        startAudible(context);
    }
}
