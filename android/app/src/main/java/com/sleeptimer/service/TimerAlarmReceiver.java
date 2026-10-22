package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Backup for the in-process countdown; fires even in Doze or after the process was killed. */
public class TimerAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_TIMER_ALARM = "com.sleeptimer.TIMER_ALARM";
    private static final long KEEP_ALIVE_MS = 8_000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_TIMER_ALARM.equals(intent.getAction())) return;
        if (AppShutdown.isClosing()) return;
        PendingResult result = goAsync();
        SleepTimer.get(context).onAlarm();
        // Keep the process alive long enough for the pause to be verified.
        new Handler(Looper.getMainLooper()).postDelayed(result::finish, KEEP_ALIVE_MS);
    }
}
