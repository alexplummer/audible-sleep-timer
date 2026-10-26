package com.sleeptimer.service;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional, user-enabled accessibility service that only observes key events so the phone's own
 * volume keys can be told apart from Bluetooth headset volume changes, including with the screen
 * off. It never consumes a key and reads no screen content. (The class name is kept so an existing
 * accessibility grant survives updates.)
 */
public class ButtonLoggerService extends AccessibilityService {

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!AppShutdown.isClosing()) HeadsetButtons.get(this).onKeyEvent(event);
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
