package com.sleeptimer

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

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
  override fun onResume() {
    super.onResume()
    android.util.Log.d("MainActivity", "onResume called")
    if (checkNotificationPermission()) {
      startSleepTimerService()
      registerPauseReceiver()
    }
  }
  
  private fun checkNotificationPermission(): Boolean {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissions(
          arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
          NOTIFICATION_PERMISSION_REQUEST_CODE
        )
        false
      } else {
        true
      }
    } else {
      true
    }
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
      if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
        startSleepTimerService()
        registerPauseReceiver()
      }
    }
  }

  companion object {
    private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
  }

  private fun startSleepTimerService() {
    android.util.Log.d("MainActivity", "Starting ForegroundService...")
    val intent = android.content.Intent(this, com.sleeptimer.service.ForegroundService::class.java)
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        startForegroundService(intent)
        android.util.Log.d("MainActivity", "Started ForegroundService with startForegroundService")
      } else {
        startService(intent)
        android.util.Log.d("MainActivity", "Started ForegroundService with startService")
      }
    } catch (e: Exception) {
      android.util.Log.e("MainActivity", "Error starting ForegroundService", e)
    }
  }

  private fun checkAndRequestNotificationPermission() {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
      if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        requestPermissions(
          arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
          1001
        )
      }
    }
  }

  private fun registerPauseReceiver() {
    try {
      val filter = android.content.IntentFilter("com.sleeptimer.PAUSE_AUDIBLE")
      val receiver = com.sleeptimer.service.PauseAudibleReceiver()
      if (android.os.Build.VERSION.SDK_INT >= 34) {
        registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
      } else {
        registerReceiver(receiver, filter)
      }
      android.util.Log.d("MainActivity", "PauseAudibleReceiver registered")
    } catch (e: Exception) {
      android.util.Log.e("MainActivity", "Error registering receiver", e)
    }
  }
}
