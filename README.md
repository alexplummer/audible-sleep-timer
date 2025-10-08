<img width="280" alt="Screenshot_20250923-211050" src="https://github.com/user-attachments/assets/4d18d823-c933-48cf-859f-56beddb7adb8" />

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

### ⚡ Quick Controls
- **Notification Presets**: Tap 15m or 45m buttons directly from notification  
- **Auto-Restart**: Change timer duration while running - it automatically restarts with new time  
- **Volume Navigation**: Double-press volume down to go back a chapter (when app is open)  

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

- Android device (API 21+)  
- Audible app installed  
- Earbuds or headphones with a play/pause button  

---

## 🔧 Installation & Setup

### Option 1: Install APK (Recommended)

1. Download the latest APK from [Releases](https://github.com/yourusername/audible-sleep-timer/releases)  
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
git clone https://github.com/yourusername/audible-sleep-timer.git
cd audible-sleep-timer

# Install dependencies
npm install

# Run app on Android
npm run android

# Build production APK
npm run build