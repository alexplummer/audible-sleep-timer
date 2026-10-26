package com.sleeptimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.sleeptimer.service.AppShutdown
import com.sleeptimer.service.AudibleController
import com.sleeptimer.service.ButtonLog
import com.sleeptimer.service.ForegroundService
import com.sleeptimer.service.SleepTimer

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "sleeptimer"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  private var lastVolumeDownTime = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    AppShutdown.openApp(this)
    super.onCreate(savedInstanceState)
    AppShutdown.setActivity(this)
    requestNotificationPermissionOnce()
  }

  override fun onResume() {
    super.onResume()
    if (AppShutdown.isClosing()) return
    // Started only from the visible app; it is never restarted by the system after Close.
    ForegroundService.start(this)
    AudibleController.get(this).refresh()
    SleepTimer.get(this).notifyChanged()
  }

  override fun onDestroy() {
    AppShutdown.clearActivity(this)
    super.onDestroy()
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    // With the key filter on, the accessibility service already logs every key.
    if (!ButtonLog.isKeyFilterEnabled(this)) ButtonLog.get(this).logKey("app", event)
    return super.dispatchKeyEvent(event)
  }

  /** Double-press volume down (while the app is open) skips back in Audible. */
  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && (event?.repeatCount ?: 0) == 0) {
      val now = SystemClock.uptimeMillis()
      if (lastVolumeDownTime > 0 && now - lastVolumeDownTime <= DOUBLE_PRESS_MS) {
        lastVolumeDownTime = 0
        AudibleController.get(this).previous()
        return true
      }
      lastVolumeDownTime = now
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onRequestPermissionsResult(
      requestCode: Int,
      permissions: Array<String>,
      grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
      // Re-post the service notification now that it can be shown.
      SleepTimer.get(this).notifyChanged()
    }
  }

  private fun requestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
    val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
    if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
    prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
  }

  companion object {
    private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
    private const val DOUBLE_PRESS_MS = 500L
    private const val PREFS = "main_activity"
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notification_permission"
  }
}
