package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification preset buttons (15m / 45m). */
public class PresetTimerReceiver extends BroadcastReceiver {

    public static final String ACTION_SET_TIMER = "com.sleeptimer.SET_TIMER";
    public static final String EXTRA_MINUTES = "minutes";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SET_TIMER.equals(intent.getAction())) return;
        // Never revive a closed app from a stale notification.
        if (AppShutdown.isClosing() || !ForegroundService.isRunning()) return;
        int minutes = intent.getIntExtra(EXTRA_MINUTES, 0);
        if (minutes > 0) {
            SleepTimer.get(context).applyPreset(minutes);
        }
    }
}
