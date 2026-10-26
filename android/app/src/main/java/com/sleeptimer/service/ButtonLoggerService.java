package com.sleeptimer.service;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional, user-enabled accessibility service that only observes key events (including with the
 * screen off) for the button diagnostic log. It never consumes a key and reads no screen content.
 */
public class ButtonLoggerService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (!AppShutdown.isClosing()) ButtonLog.get(this).add("Key filter (accessibility) connected");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (AppShutdown.isClosing()) return false;
        ButtonLog.get(this).logKey("filter", event);
        HeadsetButtons.get(this).onKeyEvent(event);
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Key events only.
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }
}
