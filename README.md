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
- **Headphone volume fine steps** – while Audible plays, your Bluetooth headphones' volume buttons move the fine-tune by 0.5 dB instead of a whole system volume step. The phone's own buttons still change the system volume.
- **Spoken time** – double-press volume down on your headphones to hear the current time, e.g. "16:29", at your listening level.
- **Chapter back** – double-press the phone's volume down button while the app is open.
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

Then play Audible and tap **Retry**. Revoke with `adb -d shell pm revoke com.sleeptimer android.permission.DUMP`. **Reset** returns the fine-tune to 0 dB.

### Headphone volume buttons

Many Bluetooth headphones (using "absolute volume") don't send button presses. They change the phone's media volume directly. While Audible is playing over Bluetooth, with the screen off or the app open, the app treats such a change as a headphone press. It puts the system volume back and moves the fine-tune instead:

- **Volume down / up** – 0.5 dB quieter / louder. At 0 dB, volume up raises the system volume. At -40 dB, volume down lowers it.
- **Double-press volume down** – speaks the time in 24-hour format. The first press's step is undone.
- Holding volume up is sent by most headphones as "next" straight to Audible, so it can't be used.

You may briefly hear the system volume change by one step before it is put back. Keep the system volume below maximum, because at maximum the headphones send nothing for volume up. Choose **System volume** in the app to turn this off.

With the screen off, Android may hide the phone's own volume-button presses from apps. Tap **Enable phone button detection** and turn on **Sleep timer phone buttons** in Accessibility settings so the app can recognise them and leave them as normal system volume changes. On Android 13+, if the switch is greyed out, open App info › ⋮ › **Allow restricted settings** first. It only observes volume keys; no screen content is read.

## Build

```bash
npm install
npm test -- --runInBand && npx tsc --noEmit && npm run lint
npm run build   # android/app/build/outputs/apk/release/app-release.apk
```

To publish, bump `package.json`, the lockfile, and Android `versionName`/`versionCode`, then push a `vX.Y.Z` tag. The **Android release** workflow builds the APK and attaches it to a GitHub release. Release APKs use the repository's debug signing key so they install over earlier versions. They are not suitable for app stores.
