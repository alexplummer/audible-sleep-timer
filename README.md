# Audible Sleep Timer# Audible Sleep TimerThis is a new [**React Native**](https://reactnative.dev) project, bootstrapped using [`@react-native-community/cli`](https://github.com/react-native-community/cli).



An Android app that creates a customizable sleep timer for Audible audiobooks, controlled entirely through your earbuds' hardware buttons.



## 🎧 What it doesAn Android app that creates a customizable sleep timer for Audible audiobooks, controlled entirely through your earbuds' hardware buttons.# Getting Started



This app allows you to set a sleep timer for Audible audiobooks and control it using your earbuds' play/pause buttons - perfect for falling asleep to audiobooks without worrying about them playing all night.



### Key Features## 🎧 What it does> **Note**: Make sure you have completed the [Set Up Your Environment](https://reactnative.dev/docs/set-up-your-environment) guide before proceeding.



- **🎛️ Hardware Button Control**: Start, pause, and resume the timer using your earbuds' play/pause button

- **⏰ Real-time Countdown**: See the remaining time directly in your notification panel with live countdown

- **⏸️ Smart Pause/Resume**: Timer automatically pauses when you pause playbook and resumes when you playThis app allows you to set a sleep timer for Audible audiobooks and control it using your earbuds' play/pause buttons - perfect for falling asleep to audiobooks without worrying about them playing all night.## Step 1: Start Metro

- **🔕 Auto-Pause**: Automatically pauses Audible when the timer expires

- **📱 Persistent Notification**: Always shows current timer status and remaining time

- **🎯 One-Touch Close**: Close the app entirely from the notification

### Key FeaturesFirst, you will need to run **Metro**, the JavaScript build tool for React Native.

## 🚀 How it works



1. **Set your timer** - Choose how long you want to listen (15 minutes to 2 hours)

2. **Start the timer** - Press the play button on your earbuds to start both Audible and the timer- **🎛️ Hardware Button Control**: Start, pause, and resume the timer using your earbuds' play/pause buttonTo start the Metro dev server, run the following command from the root of your React Native project:

3. **Sleep peacefully** - The timer counts down in real-time, visible in your notification

4. **Automatic shutoff** - When time expires, Audible automatically pauses- **⏰ Real-time Countdown**: See the remaining time directly in your notification panel with live countdown

5. **Full control** - Pause/resume the timer anytime using your earbuds' hardware buttons

- **⏸️ Smart Pause/Resume**: Timer automatically pauses when you pause playbook and resumes when you play```sh

## 🎵 Perfect for

- **🔕 Auto-Pause**: Automatically pauses Audible when the timer expires# Using npm

- **Bedtime listening** - Fall asleep to audiobooks without them playing all night

- **Meditation sessions** - Set focused listening periods- **📱 Persistent Notification**: Always shows current timer status and remaining timenpm start

- **Commute timing** - Automatically pause at your destination

- **Study sessions** - Time-boxed learning with audiobooks- **🎯 One-Touch Close**: Close the app entirely from the notification



## 📱 Requirements# OR using Yarn



- Android device (API level 21 or higher)## 🚀 How it worksyarn start

- Audible app installed

- Earbuds or headphones with play/pause button```



## 🔧 Installation & Setup1. **Set your timer** - Choose how long you want to listen (15 minutes to 2 hours)



### Option 1: Install APK (Recommended)2. **Start the timer** - Press the play button on your earbuds to start both Audible and the timer## Step 2: Build and run your app

1. Download the latest APK from releases

2. Enable "Install from unknown sources" in Android settings3. **Sleep peacefully** - The timer counts down in real-time, visible in your notification

3. Install the APK file

4. Open the app and set your desired timer duration4. **Automatic shutoff** - When time expires, Audible automatically pausesWith Metro running, open a new terminal window/pane from the root of your React Native project, and use one of the following commands to build and run your Android or iOS app:



### Option 2: Build from Source5. **Full control** - Pause/resume the timer anytime using your earbuds' hardware buttons



#### Prerequisites### Android

- Node.js (v20 or higher)

- Android Studio with Android SDK## 🎵 Perfect for

- React Native development environment set up for Android

```sh

#### Build Steps

- **Bedtime listening** - Fall asleep to audiobooks without them playing all night# Using npm

```bash

# Clone the repository- **Meditation sessions** - Set focused listening periodsnpm run android

git clone https://github.com/yourusername/audible-sleep-timer.git

cd audible-sleep-timer- **Commute timing** - Automatically pause at your destination



# Install dependencies- **Study sessions** - Time-boxed learning with audiobooks# OR using Yarn

npm install

yarn android

# Build production APK

npm run build## 📱 Requirements```



# OR build debug APK for testing

npm run build:debug

- Android device### iOS

# OR run in development mode (requires connected Android device or emulator)

npm run android- Audible app installed

```

- Earbuds or headphones with play/pause buttonFor iOS, remember to install CocoaPods dependencies (this only needs to be run on first clone or after updating native deps).

The APK will be generated in `android/app/build/outputs/apk/`



## 🎮 Usage Instructions

## 🔧 Installation & SetupThe first time you create a new project, run the Ruby bundler to install CocoaPods itself:

### Initial Setup

1. **Launch the app** and set your preferred sleep timer duration using the slider

2. **Grant permissions** when prompted (needed for media button handling)

3. The app will create a persistent notification### Option 1: Install APK (Recommended)```sh



### Using the Timer1. Download the latest APK from releasesbundle install

1. **Start Audible** and begin playing your audiobook

2. **Press the play/pause button** on your earbuds once to start the sleep timer2. Enable "Install from unknown sources" in Android settings```

3. **Monitor progress** - The notification shows real-time countdown

4. **Pause anytime** - Press pause button to pause both Audible and timer3. Install the APK file

5. **Resume** - Press play button to resume both Audible and timer

6. **Auto-shutoff** - Timer automatically pauses Audible when time expires4. Open the app and set your desired timer durationThen, and every time you update your native dependencies, run:



### Hardware Button Controls

- **Single press Play**: Start timer (if not running) or Resume timer (if paused)

- **Single press Pause**: Pause both timer and Audible playback### Option 2: Build from Source```sh

- **Notification**: Shows current status and remaining time with live countdown

bundle exec pod install

## 🔧 Technical Details

#### Prerequisites```

Built with React Native and native Android modules for hardware button integration:

- Node.js (v20 or higher)

- **MediaSession API** - Captures hardware button presses from earbuds

- **Foreground Service** - Maintains persistent notification and timer functionality- Android Studio with Android SDKFor more information, please visit [CocoaPods Getting Started guide](https://guides.cocoapods.org/using/getting-started.html).

- **BroadcastReceiver** - Handles media button events and Audible integration

- **Real-time Updates** - Notification updates every second during countdown- React Native development environment set up

- **Android-only** - Designed specifically for Android's media session system

```sh

## 🐛 Troubleshooting

#### Build Steps# Using npm

**Timer not starting?**

- Ensure Audible app is installed and has played content beforenpm run ios

- Check that media button permissions are granted

- Try restarting both apps```bash



**Hardware buttons not working?**# Clone the repository# OR using Yarn

- Verify earbuds are properly connected to your Android device

- Test play/pause buttons work with other media appsgit clone https://github.com/yourusername/audible-sleep-timer.gityarn ios

- Check Android's media session priorities in Settings

cd audible-sleep-timer```

**Notification not updating?**

- Ensure the app has notification permissions

- Check that battery optimization is disabled for the app

- Verify the app is running in the background# Install dependenciesIf everything is set up correctly, you should see your new app running in the Android Emulator, iOS Simulator, or your connected device.



**App closing unexpectedly?**npm install

- Check Android battery optimization settings

- Ensure the app has permission to run in backgroundThis is one way to run your app — you can also build it directly from Android Studio or Xcode.

- Try disabling battery optimization for this app

# Build production APK

## 📄 License

npm run build## Step 3: Modify your app

This project is licensed under the MIT License - see the LICENSE file for details.



## 🤝 Contributing

# OR build debug APK for testingNow that you have successfully run the app, let's make changes!

Contributions are welcome! Please feel free to submit a Pull Request.

npm run build:debug

## 📞 Support

Open `App.tsx` in your text editor of choice and make some changes. When you save, your app will automatically update and reflect these changes — this is powered by [Fast Refresh](https://reactnative.dev/docs/fast-refresh).

If you encounter any issues or have feature requests, please open an issue on GitHub.

# OR run in development mode

---

npm run androidWhen you want to forcefully reload, for example to reset the state of your app, you can perform a full reload:

*Perfect for audiobook lovers who want hands-free timer control while falling asleep! 🎧😴*

```

**Android Only** - This app is specifically designed for Android devices and takes advantage of Android's MediaSession API for seamless hardware button integration.
- **Android**: Press the <kbd>R</kbd> key twice or select **"Reload"** from the **Dev Menu**, accessed via <kbd>Ctrl</kbd> + <kbd>M</kbd> (Windows/Linux) or <kbd>Cmd ⌘</kbd> + <kbd>M</kbd> (macOS).

The APK will be generated in `android/app/build/outputs/apk/`- **iOS**: Press <kbd>R</kbd> in iOS Simulator.



## 🎮 Usage Instructions## Congratulations! :tada:



### Initial SetupYou've successfully run and modified your React Native App. :partying_face:

1. **Launch the app** and set your preferred sleep timer duration using the slider

2. **Grant permissions** when prompted (needed for media button handling)### Now what?

3. The app will create a persistent notification

- If you want to add this new React Native code to an existing application, check out the [Integration guide](https://reactnative.dev/docs/integration-with-existing-apps).

### Using the Timer- If you're curious to learn more about React Native, check out the [docs](https://reactnative.dev/docs/getting-started).

1. **Start Audible** and begin playing your audiobook

2. **Press the play/pause button** on your earbuds once to start the sleep timer# Troubleshooting

3. **Monitor progress** - The notification shows real-time countdown

4. **Pause anytime** - Press pause button to pause both Audible and timerIf you're having issues getting the above steps to work, see the [Troubleshooting](https://reactnative.dev/docs/troubleshooting) page.

5. **Resume** - Press play button to resume both Audible and timer

6. **Auto-shutoff** - Timer automatically pauses Audible when time expires# Learn More



### Hardware Button ControlsTo learn more about React Native, take a look at the following resources:

- **Single press Play**: Start timer (if not running) or Resume timer (if paused)

- **Single press Pause**: Pause both timer and Audible playback- [React Native Website](https://reactnative.dev) - learn more about React Native.

- **Notification**: Shows current status and remaining time with live countdown- [Getting Started](https://reactnative.dev/docs/environment-setup) - an **overview** of React Native and how setup your environment.

- [Learn the Basics](https://reactnative.dev/docs/getting-started) - a **guided tour** of the React Native **basics**.

## 🔧 Technical Details- [Blog](https://reactnative.dev/blog) - read the latest official React Native **Blog** posts.

- [`@facebook/react-native`](https://github.com/facebook/react-native) - the Open Source; GitHub **repository** for React Native.

Built with React Native and native Android modules for hardware button integration:

- **MediaSession API** - Captures hardware button presses
- **Foreground Service** - Maintains persistent notification and timer functionality
- **BroadcastReceiver** - Handles media button events and Audible integration
- **Real-time Updates** - Notification updates every second during countdown

## 🐛 Troubleshooting

**Timer not starting?**
- Ensure Audible app is installed and has played content before
- Check that media button permissions are granted
- Try restarting both apps

**Hardware buttons not working?**
- Verify earbuds are properly connected
- Test play/pause buttons work with other media apps
- Check Android's media session priorities

**Notification not updating?**
- Ensure the app has notification permissions
- Check that battery optimization is disabled for the app

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📞 Support

If you encounter any issues or have feature requests, please open an issue on GitHub.

---

*Perfect for audiobook lovers who want hands-free timer control while falling asleep! 🎧😴*