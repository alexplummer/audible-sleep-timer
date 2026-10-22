package com.sleeptimer.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Notification "Close" action: shuts the whole app down. */
public class CloseAppReceiver extends BroadcastReceiver {

    public static final String ACTION_CLOSE_APP = "com.sleeptimer.CLOSE_APP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CLOSE_APP.equals(intent.getAction())) return;
        PendingResult result = goAsync();
        AppShutdown.closeApp(context, result::finish);
    }
}
