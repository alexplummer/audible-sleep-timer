package com.sleeptimer.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import java.util.List;

/**
 * Observes and controls Audible through the platform media-session APIs.
 *
 * Commands go through Audible's MediaController (or AudioManager media-key dispatch as a
 * fallback), which is the same path a headset button takes. That lets the system grant Audible
 * the temporary allowances it needs to restart playback from the background, which raw
 * MEDIA_BUTTON broadcasts do not. Observing requires the user to grant notification access.
 * All methods must be called on the main thread.
 */
public final class AudibleController {

    public interface Listener {
        void onAudiblePlayingChanged(boolean playing);

        void onAudibleInfoChanged();
    }

    public static final String AUDIBLE_PACKAGE = "com.audible.application";

    private static final String TAG = "AudibleController";
    private static final long PENDING_PLAY_WINDOW_MS = 15_000L;
    private static final long[] PAUSE_VERIFY_DELAYS_MS = {2_500L, 6_000L};

    private static AudibleController instance;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName listenerComponent;

    @Nullable private Listener listener;
    @Nullable private MediaController controller;
    @Nullable private Boolean lastPlaying;
    private boolean observing;
    private long pendingPlayUntil;
    private int pauseAttempt;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            controllers -> onSessionsChanged(controllers);

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            onPlayingObserved(mapPlaying(state));
        }

        @Override
        public void onSessionDestroyed() {
            detach();
            onPlayingObserved(Boolean.FALSE);
            notifyInfo();
        }
    };

    private final Runnable verifyPause = this::verifyPause;

    public static synchronized AudibleController get(Context context) {
        if (instance == null) {
            instance = new AudibleController(context.getApplicationContext());
        }
        return instance;
    }

    private AudibleController(Context context) {
        appContext = context;
        listenerComponent = new ComponentName(context, AudibleListenerService.class);
    }

    void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public static boolean hasNotificationAccess(Context context) {
        return NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.getPackageName());
    }

    public boolean isAudibleInstalled() {
        return appContext.getPackageManager().getLaunchIntentForPackage(AUDIBLE_PACKAGE) != null;
    }

    public boolean isObserving() {
        return observing;
    }

    public boolean isAudiblePlaying() {
        if (controller != null) {
            Boolean playing = mapPlaying(controller.getPlaybackState());
            if (playing != null) return playing;
        }
        return Boolean.TRUE.equals(lastPlaying);
    }

    /** Starts (or re-checks) observation. Safe to call repeatedly. */
    public void refresh() {
        if (AppShutdown.isClosing()) return;
        boolean access = hasNotificationAccess(appContext);
        if (observing && !access) {
            stopObserving();
            notifyInfo();
        } else if (!observing && access) {
            startObserving();
        }
    }

    public void startObserving() {
        if (observing || AppShutdown.isClosing()) return;
        MediaSessionManager msm = sessionManager();
        if (msm == null || !hasNotificationAccess(appContext)) return;
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler);
            observing = true;
            MediaController audible = findAudible(msm.getActiveSessions(listenerComponent));
            if (audible != null) {
                attach(audible);
                onPlayingObserved(mapPlaying(audible.getPlaybackState()));
            }
        } catch (SecurityException e) {
            Log.w(TAG, "Notification access not usable yet", e);
            stopObserving();
        }
        notifyInfo();
    }

    public void stopObserving() {
        MediaSessionManager msm = sessionManager();
        if (observing && msm != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not remove sessions listener", e);
            }
        }
        observing = false;
        detach();
        lastPlaying = null;
        pendingPlayUntil = 0;
    }

    /** Stops observing and cancels pending work. Used when the app is closed. */
    public void shutdown() {
        cancelPendingPause();
        stopObserving();
        listener = null;
    }

    /**
     * Plays Audible. When called from the visible UI and Audible has no media session yet, Audible
     * is launched and playback starts as soon as its session appears.
     */
    public void play(boolean fromForegroundUi) {
        cancelPendingPause();
        MediaController target = currentOrLookup();
        if (target != null) {
            target.getTransportControls().play();
            return;
        }
        if (observing && fromForegroundUi && launchAudible()) {
            pendingPlayUntil = SystemClock.elapsedRealtime() + PENDING_PLAY_WINDOW_MS;
            return;
        }
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    /** Pauses Audible because the timer expired, then verifies and retries if needed. */
    public void pauseForTimer() {
        cancelPendingPause();
        pendingPlayUntil = 0;
        sendPause(false);
        pauseAttempt = 0;
        handler.postDelayed(verifyPause, PAUSE_VERIFY_DELAYS_MS[0]);
    }

    public void cancelPendingPause() {
        handler.removeCallbacks(verifyPause);
    }

    public void previous() {
        MediaController target = currentOrLookup();
        if (target != null) {
            target.getTransportControls().skipToPrevious();
        } else {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    private void verifyPause() {
        MediaController target = currentOrLookup();
        // Without a controller we can only know Audible isn't playing if we're observing sessions.
        boolean stillPlaying = target != null
                ? Boolean.TRUE.equals(mapPlaying(target.getPlaybackState()))
                : !observing;
        if (!stillPlaying) return;
        Log.w(TAG, "Audible still playing after pause (attempt " + (pauseAttempt + 1) + ") - retrying");
        // Alternate routes so a misbehaving one can't block the pause.
        sendPause(pauseAttempt % 2 == 0);
        pauseAttempt++;
        if (pauseAttempt < PAUSE_VERIFY_DELAYS_MS.length) {
            handler.postDelayed(verifyPause, PAUSE_VERIFY_DELAYS_MS[pauseAttempt]);
        }
    }

    private void sendPause(boolean preferKeyDispatch) {
        MediaController target = currentOrLookup();
        if (target != null && !preferKeyDispatch) {
            target.getTransportControls().pause();
        } else {
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE);
        }
    }

    private void onSessionsChanged(@Nullable List<MediaController> controllers) {
        if (!observing) return;
        MediaController audible = findAudible(controllers);
        if (audible == null) return;
        if (controller != null && controller.getSessionToken().equals(audible.getSessionToken())) return;
        attach(audible);
        // A new Audible session (e.g. Audible restarted by a headset press) counts as a transition.
        onPlayingObserved(mapPlaying(audible.getPlaybackState()));
        notifyInfo();
    }

    private void attach(MediaController audible) {
        detach();
        controller = audible;
        audible.registerCallback(controllerCallback, handler);
        if (pendingPlayUntil > SystemClock.elapsedRealtime()) {
            pendingPlayUntil = 0;
            audible.getTransportControls().play();
        }
    }

    private void detach() {
        if (controller != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not unregister controller callback", e);
            }
            controller = null;
        }
    }

    private void onPlayingObserved(@Nullable Boolean playing) {
        if (playing == null || playing.equals(lastPlaying)) return;
        lastPlaying = playing;
        if (listener != null) listener.onAudiblePlayingChanged(playing);
    }

    private void notifyInfo() {
        if (listener != null) listener.onAudibleInfoChanged();
    }

    @Nullable
    private MediaController currentOrLookup() {
        if (controller != null) return controller;
        // Not observing (e.g. alarm fired in a fresh process): try a one-off lookup.
        MediaSessionManager msm = sessionManager();
        if (msm == null || !hasNotificationAccess(appContext)) return null;
        try {
            return findAudible(msm.getActiveSessions(listenerComponent));
        } catch (SecurityException e) {
            Log.w(TAG, "Notification access unavailable for media session lookup", e);
            return null;
        }
    }

    @Nullable
    private static MediaController findAudible(@Nullable List<MediaController> controllers) {
        if (controllers == null) return null;
        for (MediaController c : controllers) {
            if (AUDIBLE_PACKAGE.equals(c.getPackageName())) return c;
        }
        return null;
    }

    /** true = playing (or about to), false = not playing, null = transient state to ignore. */
    @Nullable
    private static Boolean mapPlaying(@Nullable PlaybackState state) {
        if (state == null) return Boolean.FALSE;
        switch (state.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
                return Boolean.TRUE;
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return null;
            default:
                return Boolean.FALSE;
        }
    }

    private boolean launchAudible() {
        Intent launch = appContext.getPackageManager().getLaunchIntentForPackage(AUDIBLE_PACKAGE);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            appContext.startActivity(launch);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not launch Audible", e);
            return false;
        }
    }

    private void dispatchMediaKey(int keyCode) {
        AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    @Nullable
    private MediaSessionManager sessionManager() {
        return (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }
}
