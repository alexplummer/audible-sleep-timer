<img width="280" src="https://github.com/user-attachments/assets/57f3e4c5-6dcc-4288-a0e9-d45732df3dba" />

# Audible Sleep Timer

A small Android companion app for Audible. I built it for my own use: I listen in bed with Bluetooth sleep headband headphones and want to control audiobooks without looking at the phone screen.

Audible's own sleep timer is unreliable. It often fails to restart, and you have to unlock the phone to set it again. This app replaces it with a timer that follows Audible's playback, so pressing play on the headphones starts a new countdown.

## Features

- **Headphone-driven timer** – press play on your headphones and the timer starts with Audible; it pauses and resumes with playback.
- **Auto-pause** – Audible pauses when the timer runs out. Press play again for another full timer.
- **Short-pause memory** – resume within five minutes to continue the remaining time; later resumes start a full timer.
- **Notification controls** – live countdown, 15 / 45 minute presets and Close, all from the notification.
- **Runs with the screen off** – the countdown is native and uses exact alarms, so it survives Doze.
- **Volume fine-tune** – lower Audible's output from 0 to -40 dB in 0.5 dB steps, below Android's quietest volume step (Android 9+).
- **Chapter back** – double-press volume down while the app is open.
- **Button log** – shows what your headphone buttons send to the phone (key presses, volume changes, device connects).
- **Clean close** – Close stops the timer, removes volume changes and fully shuts the app down. It never pauses Audible on close.

## Install

1. Download the APK from [Releases](https://github.com/alexplummer/audible-sleep-timer/releases/latest) and install it (allow installs from unknown sources).
2. Open the app and allow notifications.
3. Grant **notification access** from the in-app button. It is used only to see and control Audible's playback; notification contents are not read.
4. Set a duration, then press play on your headphones.

Requires Android 7+ and the Audible app.

### Volume fine-tune setup

Keep Android's media volume above mute and lower the in-app slider. Turn off other equalizers such as Precise Volume. If the app says no Audible audio session was detected, grant audio detection once from a computer with USB debugging enabled:

```sh
adb -d shell pm grant com.sleeptimer android.permission.DUMP
```

Then play Audible and tap **Retry audio detection**. Revoke with `adb -d shell pm revoke com.sleeptimer android.permission.DUMP`. **Legacy mix** is a fallback that affects all audio and is bypassed on some phones.

### Button log

The log at the bottom of the app records:

- `KEY` – a button press, with the input device name (for example the phone's built-in keys or a Bluetooth `AVRCP` device).
- `VOLUME` – a media volume change and the current output device.
- `DEVICE` / `AUDIBLE` – headphones connecting, and Audible playing or pausing.

Without extra setup, keys are only logged while the app is open. To log them with the screen off, tap **Enable key filter** and turn on **Sleep timer button log** in Accessibility settings. On Android 13+, if the switch is greyed out, open App info › ⋮ › **Allow restricted settings** first. The filter only observes keys. Buttons keep working normally and no screen content is read. Turn it off when you are done.

## Build

```bash
npm install
npm test -- --runInBand && npx tsc --noEmit && npm run lint
npm run build   # android/app/build/outputs/apk/release/app-release.apk
```

To publish, bump `package.json`, the lockfile, and Android `versionName`/`versionCode`, then push a `vX.Y.Z` tag. The **Android release** workflow builds the APK and attaches it to a GitHub release. Release APKs use the repository's debug signing key so they install over earlier versions. They are not suitable for app stores.
