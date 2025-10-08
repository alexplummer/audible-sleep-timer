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
    private static boolean timerPaused = false;
    private static android.os.Handler timerHandler;
    private static Runnable timerRunnable;
    private static long timerStartTime;
    private static long timerDuration;
    private static long pausedTimeRemaining;
    private static android.os.Handler notificationUpdateHandler;
    private static Runnable notificationUpdateRunnable;

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
                
                int keyCode = keyEvent.getKeyCode();
                if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY || 
                    keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    keyCode == 127) {
                    Log.d(TAG, "Play/Pause button pressed (keycode: " + keyCode + ")");
                    handlePlayButton(context);
                } else if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ||
                          keyCode == android.view.KeyEvent.KEYCODE_MEDIA_STOP) {
                    Log.d(TAG, "Pause/Stop button pressed (keycode: " + keyCode + ")");
                    handlePauseButton(context);
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
                    public void onPause() {
                        Log.d(TAG, "MediaSession onPause called");
                        handlePauseButton(context);
                    }
                    
                    @Override
                    public void onStop() {
                        Log.d(TAG, "MediaSession onStop called");
                        handlePauseButton(context);
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
                                } else if (keyEvent.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ||
                                          keyEvent.getKeyCode() == android.view.KeyEvent.KEYCODE_MEDIA_STOP) {
                                    Log.d(TAG, "MediaSession handling pause button");
                                    handlePauseButton(context);
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
        if (timerPaused) {
            Log.d(TAG, "Play button pressed - resuming paused timer");
            resumeTimer(context);
        } else if (timerRunning) {
            Log.d(TAG, "Timer already running - resuming Audible playback only");
            resumeAudibleOnly(context);
        } else {
            Log.d(TAG, "Play button pressed - starting new timer");
            startNewTimer(context);
        }
    }
    
    private static void handlePauseButton(Context context) {
        if (timerRunning && !timerPaused) {
            Log.d(TAG, "Pause button pressed - pausing Audible only (timer continues)");
            pauseAudibleOnly(context);
        } else {
            Log.d(TAG, "Timer not running, ignoring pause button");
        }
    }
    
    private static void startNewTimer(Context context) {
        timerRunning = true;
        timerPaused = false;
        timerDuration = TimerConfigModule.getTimerDurationSeconds() * 1000;
        timerStartTime = System.currentTimeMillis();
        
        // Start Audible by sending a play command
        startAudible(context);
        
        // Notify React Native about the button press
        try {
            Class<?> clazz = Class.forName("com.sleeptimer.MediaButtonEventModule");
            clazz.getMethod("sendPlayButtonEvent").invoke(null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to emit play event to JS", e);
        }
        
        // Start the timer
        startTimer(context);
        
        // Start notification updates
        startNotificationUpdates(context);
    }
    
    private static void startTimer(Context context) {
        timerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        timerRunnable = () -> {
            Log.d(TAG, "Timer completed - pausing Audible");
            completeTimer(context);
        };
        
        long delay = timerPaused ? pausedTimeRemaining : timerDuration;
        timerHandler.postDelayed(timerRunnable, delay);
        Log.d(TAG, "Timer started with delay: " + delay + "ms");
    }
    
    private static void pauseAudibleOnly(Context context) {
        Log.d(TAG, "Pausing Audible playbook only - timer continues running");
        
        // Just pause Audible, don't touch the timer
        pauseAudible(context);
        
        // No timer state changes - timer keeps running
        // No notification of pause to React Native - timer state unchanged
        Log.d(TAG, "Audible paused, timer continues running");
    }
    
    private static void pauseTimer(Context context) {
        if (timerHandler != null && timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            
            // Calculate remaining time
            long elapsed = System.currentTimeMillis() - timerStartTime;
            pausedTimeRemaining = timerDuration - elapsed;
            if (pausedTimeRemaining < 0) pausedTimeRemaining = 0;
            
            timerPaused = true;
            Log.d(TAG, "Timer paused with " + pausedTimeRemaining + "ms remaining");
            
            // Notify React Native about timer pause
            try {
                Class<?> clazz = Class.forName("com.sleeptimer.MediaButtonEventModule");
                clazz.getMethod("sendTimerPausedEvent").invoke(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to emit timer paused event to JS", e);
            }
            
            // Stop notification updates and update notification with paused status
            stopNotificationUpdates();
            ForegroundService.updateNotification("Timer Paused", pausedTimeRemaining);
        }
    }
    
    private static void resumeAudibleOnly(Context context) {
        Log.d(TAG, "Resuming Audible playback only - timer already running");
        
        // Just send a simple play command without heavy MediaSession manipulation
        sendPlayCommandToAudible(context);
        
        Log.d(TAG, "Sent resume command to Audible, timer continues running");
    }
    
    private static void resumeTimer(Context context) {
        if (timerPaused) {
            timerPaused = false;
            timerStartTime = System.currentTimeMillis();
            
            // Start Audible again
            startAudible(context);
            
            // Notify React Native about timer resume
            try {
                Class<?> clazz = Class.forName("com.sleeptimer.MediaButtonEventModule");
                clazz.getMethod("sendTimerResumedEvent").invoke(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to emit timer resumed event to JS", e);
            }
            
            // Resume the timer with remaining time
            startTimer(context);
            Log.d(TAG, "Timer resumed with " + pausedTimeRemaining + "ms remaining");
            
            // Restart notification updates
            startNotificationUpdates(context);
        }
    }
    
    private static void completeTimer(Context context) {
        // Deactivate our media session BEFORE starting pause process
        if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession.setActive(false);
            Log.d(TAG, "Deactivated media session before pause process");
        }
        
        pauseAudible(context);
        
        // Notify React Native that timer completed
        try {
            Class<?> clazz = Class.forName("com.sleeptimer.MediaButtonEventModule");
            clazz.getMethod("sendTimerCompletedEvent").invoke(null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to emit timer completed event to JS", e);
        }
        
        // Reset timer state
        timerRunning = false;
        timerPaused = false;
        pausedTimeRemaining = 0;
        Log.d(TAG, "Timer completed and reset");
        
        // Stop notification updates and update notification
        stopNotificationUpdates();
        ForegroundService.updateNotification("Timer completed", 0);
    }
    
    private static void startAudible(Context context) {
        Log.d(TAG, "Attempting to start Audible playbook");
        
        try {
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage("com.audible.application");
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
                Log.d(TAG, "Launched Audible app directly");
                
                // Send play command after a short delay
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        mediaSession.setActive(false);
                        Log.d(TAG, "Deactivated media session before sending play command");
                    }
                    
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        sendPlayCommandToAudible(context);
                        
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                mediaSession.setActive(true);
                                Log.d(TAG, "Reactivated media session after sending play command");
                            }
                        }, 1000);
                    }, 200);
                }, 1500);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in startAudible", e);
        }
    }
    
    private static void sendPlayCommandToAudible(Context context) {
        try {
            Log.d(TAG, "Sending play command to Audible");
            
            Intent playIntent = new Intent("android.intent.action.MEDIA_BUTTON");
            playIntent.setPackage("com.audible.application");
            
            long eventTime = System.currentTimeMillis();
            android.view.KeyEvent playEvent = new android.view.KeyEvent(
                eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, 
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0);
            
            playIntent.putExtra(Intent.EXTRA_KEY_EVENT, playEvent);
            context.sendBroadcast(playIntent);
            Log.d(TAG, "Sent direct intent to Audible");
            
            android.view.KeyEvent playUpEvent = new android.view.KeyEvent(
                eventTime, eventTime, android.view.KeyEvent.ACTION_UP, 
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0);
            playIntent.putExtra(Intent.EXTRA_KEY_EVENT, playUpEvent);
            context.sendBroadcast(playIntent);
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending play commands", e);
        }
    }
    
    private static void pauseAudible(Context context) {
        Log.d(TAG, "Attempting to pause Audible");
        
        try {
            Intent pauseIntent = new Intent("android.intent.action.MEDIA_BUTTON");
            pauseIntent.setPackage("com.audible.application");
            
            long eventTime = System.currentTimeMillis();
            
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
            
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (mediaSession != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mediaSession.setActive(true);
                    Log.d(TAG, "Reactivated media session after pause delay");
                }
            }, 2000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in pauseAudible", e);
        }
    }
    
    public static void triggerStartAudible(Context context) {
        Log.d(TAG, "triggerStartAudible called from React Native module");
        startAudible(context);
    }
    
    private static void startNotificationUpdates(Context context) {
        notificationUpdateHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        notificationUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateNotificationWithRemainingTime(context);
                // Schedule next update in 1 second for real-time countdown
                if (timerRunning && !timerPaused) {
                    notificationUpdateHandler.postDelayed(this, 1000);
                }
            }
        };
        // Start immediately
        notificationUpdateHandler.post(notificationUpdateRunnable);
    }
    
    private static void stopNotificationUpdates() {
        if (notificationUpdateHandler != null && notificationUpdateRunnable != null) {
            notificationUpdateHandler.removeCallbacks(notificationUpdateRunnable);
        }
    }
    
    private static void updateNotificationWithRemainingTime(Context context) {
        if (timerRunning && !timerPaused) {
            long elapsed = System.currentTimeMillis() - timerStartTime;
            long remaining = timerDuration - elapsed;
            if (remaining < 0) remaining = 0;
            
            ForegroundService.updateNotification("Timer Running", remaining);
        }
    }
    
    public static void updateRunningTimerDuration(android.content.Context context) {
        if (timerRunning && !timerPaused) {
            Log.d(TAG, "Updating running timer with new duration");
            
            // Calculate how much time has already elapsed
            long elapsed = System.currentTimeMillis() - timerStartTime;
            
            // Get the new timer duration
            long newTimerDuration = TimerConfigModule.getTimerDurationSeconds() * 1000;
            
            // Cancel the current timer
            if (timerHandler != null && timerRunnable != null) {
                timerHandler.removeCallbacks(timerRunnable);
            }
            
            // Update timer duration and restart time
            timerDuration = newTimerDuration;
            timerStartTime = System.currentTimeMillis(); // Reset start time to now
            
            // Restart the timer with the new duration
            startTimer(context);
            
            Log.d(TAG, "Timer updated - new duration: " + (newTimerDuration / 1000) + " seconds");
            
            // Update the UI timer as well
            try {
                Class<?> clazz = Class.forName("com.sleeptimer.MediaButtonEventModule");
                clazz.getMethod("sendTimerUpdatedEvent", long.class).invoke(null, newTimerDuration / 1000);
            } catch (Exception e) {
                Log.w(TAG, "Could not send timer updated event to React Native", e);
            }
        }
    }
}
