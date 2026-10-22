
<img width="280" src="https://github.com/user-attachments/assets/57f3e4c5-6dcc-4288-a0e9-d45732df3dba" />


# 🎧 Audible Sleep Timer

This fixes the built in sleep timer for Audible allowing you to restart the timer from your bluetooth headphones throughtout the night without looking at the screen.
 
This is a [**React Native**](https://reactnative.dev) project, bootstrapped using [`@react-native-community/cli`](https://github.com/react-native-community/cli).

---

## ✨ Features

### 🎛️ Core Functionality
- **🎛️ Hardware Button Control**: Start, pause, and resume the timer using your earbuds' play/pause button  
- **⏰ Real-time Countdown**: Remaining time shown directly in your notification panel  
- **⏸️ Smart Pause/Resume**: Timer automatically pauses/resumes along with playback  
- **🔕 Auto-Pause**: Automatically pauses Audible when the timer expires  
- **📱 Persistent Notification**: Always shows current timer status and remaining time  
- **🎯 One-Touch Close**: Close the app entirely from the notification  
- **🔉 Volume Fine-Tune**: Reduce output gain from 0 to -40 dB in 0.5 dB steps without changing Android's volume level (Android 9+, device support required)

### 🆕 Advanced Features
- **⚡ Quick Timer Presets**: Set timer instantly with notification buttons (15, 45 min)  
- **🌙 Dark Theme**: Beautiful dark interface optimized for bedtime use  
- **⏮️ Chapter Navigation**: Double-press volume down to go back a chapter (when app is open)   

---

## 🚀 How it Works

### 🎯 Basic Usage
1. **Set your timer** – Choose duration with slider, presets (15-60 min), or text input  
2. **Start the timer** – Press the play button on your earbuds to start both Audible and the timer  
3. **Sleep peacefully** – The timer counts down in real-time, visible in your notification  
4. **Automatic shutoff** – Audible pauses when the timer expires  
5. **Full control** – Pause/resume anytime using your earbuds  
6. **Finish listening** – Tap **Close app** or the notification's **Close** action to cancel the timer, remove gain attenuation, release wake locks and media-session observation, and remove the app's task. It stays disabled until you open the app again; closing does not pause Audible.

On first launch, allow notifications and grant **notification access** using the in-app settings button. Notification access is used only to observe and control Audible's media session; notification contents are not read. Without it, automatic headphone playback tracking is unavailable.

### ⚡ Quick Controls
- **Notification Presets**: Tap 15m or 45m buttons directly from notification  
- **Auto-Restart**: Change timer duration while running - it automatically restarts with new time  
- **Volume Navigation**: Double-press volume down to go back a chapter (when app is open)  
- **Playback pauses**: Resuming within five minutes continues the remaining timer; resuming later starts a full timer.
- **Fine-tune**: Keep Android's media volume above mute, then lower the in-app gain slider. **0 dB** disables attenuation; **-20 dB** targets 10% and **-40 dB** targets 1% of the current signal amplitude (not perceived loudness). The default **Audible sessions** mode attaches Android's DynamicsProcessing input gain to Audible's audio-session IDs. Turn off conflicting equalizers such as Precise Volume first.

The app no longer plays a silent audio loop, creates a competing media session, or requests audio focus. Audible and Android handle headphone buttons directly; the timer follows Audible's playback state. The countdown runs natively, including while the screen is off.

### Fine-tune compatibility and session detection

Version 2.1.0 only attached gain to Android's global output mix (session 0). Android deprecates this attachment, and some phones accept the configuration but bypass it during playback. The slider also displayed a percentage without checking that an effect was active. Version 2.1.1 defaults to Audible's own audio sessions, verifies effect control, enablement and per-channel gain readback, and reports waiting/error states instead of implying that the slider always works. Configuration checks cannot measure actual sound output.

- **Audible sessions** listens for Android's standard audio-effect session open/close broadcasts. Open the timer app, then pause and resume Audible. A media-session token used for headphone controls is not an audio-session ID.
- **Legacy mix** is an explicit, persisted fallback. It targets session 0 and may affect all audio, or do nothing on an unsupported device. It is not a guaranteed fix.
- If Audible does not broadcast an audio-session ID, **better audio detection** is optional on Android 10+. Connect a trusted computer with Android platform-tools, enable USB debugging and authorize that computer, then run:

  ```sh
  adb shell pm grant com.sleeptimer android.permission.DUMP
  ```

  Select **Audible sessions**, play Audible, and tap **Retry audio detection**. Notification access is still needed for the timer's playback tracking. DUMP is a broad diagnostic permission, not a normal in-app permission prompt: the app reads audio-policy diagnostics locally, keeps only Audible's output-session IDs and does not save or log the raw dump. To remove this permission:

  ```sh
  adb shell pm revoke com.sleeptimer android.permission.DUMP
  ```

  Turn USB debugging off when finished. Android 9 supports broadcast-based discovery and Legacy mode, but not the optional diagnostic path.

Session discovery and effects stop when the app is closed. Manufacturers, Bluetooth/offloaded playback paths and other equalizers can still bypass or take control of effects. If an effect is configured but the sound does not change, try the other mode and check for competing effects; no hardware-independent guarantee is possible.

Precise Volume's official documentation describes [Legacy global-stream mode versus better audio-session detection](https://precisevolume.phascinate.com/docs/better-audio-detection/), [compatibility troubleshooting](https://precisevolume.phascinate.com/docs/faq/app-no-work/), and [DSP gain controls](https://precisevolume.phascinate.com/docs/faq/eq-effects-explained/). Its proprietary implementation is not public; this app uses Android's documented [AudioEffect session attachment](https://developer.android.com/reference/android/media/audiofx/AudioEffect) and [DynamicsProcessing input gain](https://developer.android.com/reference/android/media/audiofx/DynamicsProcessing), not copied Precise Volume internals.

### 🎛️ Hardware Button Functions
- **Single Play/Pause**: Start timer + Audible, or pause/resume playback  
- **Volume Down Double-Press**: Previous chapter (500ms window)  
- **Volume Single Press**: Normal volume control  

---

## 🎵 Perfect For

- **Bedtime listening** – Fall asleep to audiobooks without them playing all night  
- **Meditation sessions** – Focused listening periods  
- **Commutes** – Automatically pause at your destination  
- **Study sessions** – Time-boxed learning with audiobooks  

---

## 📱 Requirements

- Android device (Android 7 / API 24+; volume fine-tuning requires Android 9 / API 28+ and a compatible output effect)
- Audible app installed  
- Earbuds or headphones with a play/pause button  

---

## 🔧 Installation & Setup

### Option 1: Install APK (Recommended)

1. Download the latest APK from [Releases](https://github.com/alexplummer/audible-sleep-timer/releases/latest)
2. Enable **Install from unknown sources** in Android settings  
3. Install the APK file  
4. Open the app and set your desired timer duration  

### Option 2: Build from Source

#### Prerequisites
- Node.js (v20 or higher)  
- Android Studio with Android SDK  
- React Native development environment  

#### Build Steps
```bash
# Clone the repo
git clone https://github.com/alexplummer/audible-sleep-timer.git
cd audible-sleep-timer

# Install dependencies
npm install

# Run app on Android
npm run android

# Build production APK
npm run build
```

The standalone APK is written to `android/app/build/outputs/apk/release/app-release.apk`; it includes the JavaScript bundle and does not need Metro. Releases currently retain the repository's existing debug signing certificate for compatibility with earlier sideloaded versions. This is not a private production signing key and must not be used for app-store distribution.

### Publishing a Release

Update the package version, lockfile, and Android `versionName`, and increase Android `versionCode` before creating an annotated `vX.Y.Z` tag. Push the commit and tag to this repository. The **Android release** GitHub Actions workflow validates and builds the tagged source, then publishes a standalone APK and `SHA256SUMS.txt` using GitHub's built-in workflow token. No personal access token is needed; an SSH key with repository write access is sufficient to push the tag. The tag must match `package.json`.

### Validation

```bash
npm test -- --runInBand
npx tsc --noEmit
npm run lint
cd android
bash ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Before relying on it overnight, check on the target phone and headphones that Audible remains audible after more than an hour with the screen off, both notification presets reset the countdown, and both Close actions remove the notification and leave no timer service running. Verify gain below system volume level 1 and that closing restores normal output. Host tests cannot verify device-specific audio effects, Bluetooth behavior, or vendor battery restrictions.
