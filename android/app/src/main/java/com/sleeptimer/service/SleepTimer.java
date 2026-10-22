package com.sleeptimer.service;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Single source of truth for the sleep timer. All methods must be called on the main thread.
 *
 * Time is measured with {@link SystemClock#elapsedRealtime()} so deep sleep and wall-clock
 * changes cannot skew the countdown. Expiry is driven by a main-thread Handler while a partial
 * wake lock is held, with an exact alarm as a backup in case the process is killed.
 */
public final class SleepTimer implements AudibleController.Listener {

    public enum State { IDLE, RUNNING, PAUSED }

    public interface Listener {
        void onSleepTimerChanged();
    }

    private static final String TAG = "SleepTimer";

    public static final int DEFAULT_MINUTES = 15;
    public static final int MIN_MINUTES = 1;
    public static final int MAX_MINUTES = 120;

    static final String PREFS = "sleep_timer";
    private static final String KEY_DURATION = "duration_minutes";
    private static final String KEY_ALARM_PENDING = "alarm_pending";

    /** Resuming Audible within this window continues the countdown; later resumes restart it. */
    private static final long RESUME_WINDOW_MS = 5 * 60_000L;
    private static final long WAKE_LOCK_MARGIN_MS = 60_000L;
    private static final long ALARM_GRACE_MS = 2_000L;
    private static final int ALARM_REQUEST_CODE = 300;

    private static SleepTimer instance;

    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Runnable expiryCheck = this::checkExpiry;

    private State state = State.IDLE;
    private int durationMinutes;
    private long deadlineElapsed;
    private long pausedRemainingMs;
    private long pausedAtElapsed;
    private PowerManager.WakeLock wakeLock;

    public static synchronized SleepTimer get(Context context) {
        if (instance == null) {
            instance = new SleepTimer(context.getApplicationContext());
        }
        return instance;
    }

    private SleepTimer(Context context) {
        appContext = context;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        durationMinutes = clampMinutes(prefs.getInt(KEY_DURATION, DEFAULT_MINUTES));
        AudibleController.get(context).setListener(this);
    }

    public static int clampMinutes(int minutes) {
        return Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
    }

    public State getState() {
        return state;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public long getRemainingMs() {
        switch (state) {
            case RUNNING:
                return Math.max(0, deadlineElapsed - SystemClock.elapsedRealtime());
            case PAUSED:
                return pausedRemainingMs;
            default:
                return durationMinutes * 60_000L;
        }
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void notifyChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onSleepTimerChanged();
            } catch (RuntimeException e) {
                Log.e(TAG, "Listener failed", e);
            }
        }
    }

    /** Duration chosen in the app: a running or paused countdown restarts at the new length. */
    public void setDuration(int minutes) {
        if (AppShutdown.isClosing()) return;
        durationMinutes = clampMinutes(minutes);
        prefs.edit().putInt(KEY_DURATION, durationMinutes).apply();
        long durationMs = durationMinutes * 60_000L;
        if (state == State.RUNNING) {
            startRunning(durationMs);
        } else if (state == State.PAUSED) {
            pausedRemainingMs = durationMs;
            pausedAtElapsed = SystemClock.elapsedRealtime();
        }
        notifyChanged();
    }

    /**
     * Notification preset. Same as {@link #setDuration}, but when idle it starts the countdown if
     * Audible is already playing (or if playback can't be observed), so the button always acts.
     */
    public void applyPreset(int minutes) {
        if (AppShutdown.isClosing()) return;
        setDuration(minutes);
        if (state == State.IDLE) {
            AudibleController audible = AudibleController.get(appContext);
            if (audible.isAudiblePlaying() || !audible.isObserving()) {
                startRunning(durationMinutes * 60_000L);
                notifyChanged();
            }
        }
    }

    /** In-app Start: run a full countdown and make sure Audible is playing. */
    public void start() {
        if (AppShutdown.isClosing()) return;
        startRunning(durationMinutes * 60_000L);
        notifyChanged();
        AudibleController.get(appContext).play(true);
    }

    /** In-app Resume: continue a paused countdown and resume Audible. */
    public void resume() {
        if (AppShutdown.isClosing()) return;
        if (state != State.PAUSED) {
            start();
            return;
        }
        startRunning(pausedRemainingMs);
        notifyChanged();
        AudibleController.get(appContext).play(true);
    }

    /** In-app Stop: cancel the countdown and leave Audible alone. */
    public void stop() {
        cancelAll();
        state = State.IDLE;
        notifyChanged();
    }

    /** Releases everything without notifying listeners. Used when the app is closed. */
    public void shutdown() {
        cancelAll();
        state = State.IDLE;
    }

    @Override
    public void onAudiblePlayingChanged(boolean playing) {
        if (AppShutdown.isClosing()) return;
        if (playing) {
            if (state == State.IDLE) {
                startRunning(durationMinutes * 60_000L);
            } else if (state == State.PAUSED) {
                long pausedFor = SystemClock.elapsedRealtime() - pausedAtElapsed;
                startRunning(pausedFor <= RESUME_WINDOW_MS && pausedRemainingMs > 0
                        ? pausedRemainingMs
                        : durationMinutes * 60_000L);
            }
        } else if (state == State.RUNNING) {
            pausedRemainingMs = getRemainingMs();
            pausedAtElapsed = SystemClock.elapsedRealtime();
            cancelAll();
            state = State.PAUSED;
        }
        notifyChanged();
    }

    @Override
    public void onAudibleInfoChanged() {
        notifyChanged();
    }

    /** Backup alarm fired (process may have been restarted just for this). */
    void onAlarm() {
        if (AppShutdown.isClosing()) return;
        if (state == State.RUNNING) {
            checkExpiry();
        } else if (state == State.IDLE && prefs.getBoolean(KEY_ALARM_PENDING, false)) {
            // The countdown was lost with a previous process; still honour it.
            Log.w(TAG, "Alarm fired for a timer from a previous process - pausing Audible");
            clearAlarmFlag();
            AudibleController.get(appContext).pauseForTimer();
        }
    }

    private void startRunning(long durationMs) {
        cancelAll();
        AudibleController.get(appContext).cancelPendingPause();
        state = State.RUNNING;
        deadlineElapsed = SystemClock.elapsedRealtime() + Math.max(0, durationMs);
        acquireWakeLock(durationMs + WAKE_LOCK_MARGIN_MS);
        scheduleAlarm(deadlineElapsed + ALARM_GRACE_MS);
        handler.postDelayed(expiryCheck, Math.max(0, durationMs));
    }

    private void checkExpiry() {
        if (state != State.RUNNING) return;
        long remaining = deadlineElapsed - SystemClock.elapsedRealtime();
        if (remaining > 250) {
            handler.removeCallbacks(expiryCheck);
            handler.postDelayed(expiryCheck, remaining);
            return;
        }
        Log.i(TAG, "Timer expired - pausing Audible");
        cancelAll();
        state = State.IDLE;
        notifyChanged();
        AudibleController.get(appContext).pauseForTimer();
    }

    private void cancelAll() {
        handler.removeCallbacks(expiryCheck);
        releaseWakeLock();
        cancelAlarm();
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
                if (pm == null) return;
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleeptimer:countdown");
                wakeLock.setReferenceCounted(false);
            }
            wakeLock.acquire(timeoutMs);
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not acquire wake lock", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not release wake lock", e);
        }
    }

    private PendingIntent alarmIntent() {
        Intent intent = new Intent(appContext, TimerAlarmReceiver.class)
                .setAction(TimerAlarmReceiver.ACTION_TIMER_ALARM);
        return PendingIntent.getBroadcast(appContext, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void scheduleAlarm(long triggerElapsed) {
        AlarmManager am = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, alarmIntent());
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerElapsed, alarmIntent());
            }
            prefs.edit().putBoolean(KEY_ALARM_PENDING, true).apply();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not schedule backup alarm", e);
        }
    }

    private void cancelAlarm() {
        AlarmManager am = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try {
                am.cancel(alarmIntent());
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not cancel backup alarm", e);
            }
        }
        clearAlarmFlag();
    }

    private void clearAlarmFlag() {
        prefs.edit().remove(KEY_ALARM_PENDING).apply();
    }
}
