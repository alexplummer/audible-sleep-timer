package com.sleeptimer.service;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;

import java.lang.ref.WeakReference;

/**
 * The single shutdown path used by the notification "Close" action and the in-app Close button.
 * It stops every piece of background work, removes the task, and finally kills the process so
 * nothing can keep running or bring the app back.
 */
public final class AppShutdown {

    private static final String TAG = "AppShutdown";
    private static final String PREFS = "app_lifecycle";
    private static final String KEY_CLOSED = "closed";
    private static final long KILL_DELAY_MS = 500L;

    private static volatile boolean closing;
    private static volatile boolean closed;
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);

    private AppShutdown() {}

    public static void initialize(Context context) {
        closed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_CLOSED, false);
    }

    /** Only launching the activity may re-enable background work after an explicit close. */
    public static void openApp(Context context) {
        if (closing) return;
        closed = false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CLOSED, false).apply();
    }

    public static boolean isClosing() {
        return closing || closed;
    }

    public static void setActivity(Activity activity) {
        activityRef = new WeakReference<>(activity);
    }

    public static void clearActivity(Activity activity) {
        if (activityRef.get() == activity) {
            activityRef = new WeakReference<>(null);
        }
    }

    /**
     * Closes the app completely. {@code onBeforeKill} (optional) runs just before the process is
     * killed, e.g. to finish a BroadcastReceiver's pending result.
     */
    public static void closeApp(Context context, @Nullable Runnable onBeforeKill) {
        Handler main = new Handler(Looper.getMainLooper());
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> closeApp(context, onBeforeKill));
            return;
        }
        if (closing) {
            if (onBeforeKill != null) onBeforeKill.run();
            return;
        }
        closing = true;
        closed = true;
        Log.i(TAG, "Closing app");
        Context app = context.getApplicationContext();
        if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CLOSED, true).commit()) {
            Log.e(TAG, "Could not persist closed state");
        }

        runSafely(() -> SleepTimer.get(app).shutdown());
        runSafely(() -> AudibleController.get(app).shutdown());
        runSafely(() -> GainController.get(app).release());
        runSafely(() -> AudibleListenerService.unbind(app));
        runSafely(() -> HeadsetButtons.get(app).stopObserving());
        runSafely(() -> ForegroundService.stop(app));
        runSafely(() -> NotificationManagerCompat.from(app).cancel(ForegroundService.NOTIFICATION_ID));
        runSafely(() -> finishTasks(app));

        main.postDelayed(() -> {
            if (onBeforeKill != null) runSafely(onBeforeKill);
            Process.killProcess(Process.myPid());
        }, KILL_DELAY_MS);
    }

    private static void finishTasks(Context app) {
        Activity activity = activityRef.get();
        if (activity != null && !activity.isFinishing()) {
            activity.finishAndRemoveTask();
        }
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            for (ActivityManager.AppTask task : am.getAppTasks()) {
                runSafely(task::finishAndRemoveTask);
            }
        }
    }

    private static void runSafely(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            Log.e(TAG, "Shutdown step failed", e);
        }
    }
}
