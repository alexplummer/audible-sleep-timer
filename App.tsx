/**
 * Sleep Timer App
 * Configurable timer with hardware media button support
 */

import React, { useState, useRef, useEffect } from 'react';
import { NativeEventEmitter, NativeModules } from 'react-native';
import { 
  View, 
  Text, 
  StyleSheet, 
  StatusBar, 
  TextInput, 
  TouchableOpacity,
  ScrollView,
  PanResponder,
  Dimensions
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

const DEFAULT_TIMER_DURATION = 15; // 15 minutes default
const MAX_MINUTES = 60;
const PRESET_MINUTES = [15, 20, 30, 45, 60];

// Simple in-memory storage for the last selected timer duration
let lastSelectedDuration = DEFAULT_TIMER_DURATION;

function formatTime(seconds: number) {
  const m = Math.floor(seconds / 60).toString().padStart(2, '0');
  const s = (seconds % 60).toString().padStart(2, '0');
  return `${m}:${s}`;
}

function formatMinutes(minutes: number) {
  if (minutes < 60) {
    return `${minutes}m`;
  } else {
    const hours = Math.floor(minutes / 60);
    const mins = minutes % 60;
    return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`;
  }
}

const App = () => {
  const [timerMinutes, setTimerMinutes] = useState(lastSelectedDuration);
  const [inputValue, setInputValue] = useState(lastSelectedDuration.toString());
  const [timer, setTimer] = useState(lastSelectedDuration * 60);
  const [running, setRunning] = useState(false);
  const [paused, setPaused] = useState(false);
  const intervalRef = useRef<number | null>(null);
  const sliderWidth = Dimensions.get('window').width - 80; // Account for padding

  // Update the last selected duration whenever timerMinutes changes
  useEffect(() => {
    lastSelectedDuration = timerMinutes;
  }, [timerMinutes]);

  // Pan responder for slider dragging
  const panResponder = PanResponder.create({
    onStartShouldSetPanResponder: () => true,
    onMoveShouldSetPanResponder: () => true,
    onPanResponderGrant: (event) => {
      const { locationX } = event.nativeEvent;
      updateSliderValue(locationX);
    },
    onPanResponderMove: (event, gestureState) => {
      const { dx } = gestureState;
      const currentPosition = (timerMinutes / MAX_MINUTES) * sliderWidth;
      const newPosition = currentPosition + dx;
      updateSliderValue(newPosition);
    },
  });

  const updateSliderValue = (position: number) => {
    const percentage = Math.max(0, Math.min(1, position / sliderWidth));
    const newValue = Math.round(percentage * MAX_MINUTES);
    const finalValue = Math.max(1, newValue);
    setTimerMinutes(finalValue);
    setInputValue(finalValue.toString());
  };

  // Listen for hardware media button events
  useEffect(() => {
    const mediaButtonEventEmitter = new NativeEventEmitter(NativeModules.MediaButtonEvent);
    const subscription = mediaButtonEventEmitter.addListener('MediaButtonPlayPressed', () => {
      console.log('Hardware play button pressed - starting timer');
      // Only start if not already running to prevent double timers
      if (!running) {
        setTimer(timerMinutes * 60);
        setRunning(true);
      } else {
        console.log('Timer already running, ignoring hardware button press');
      }
    });
    return () => subscription.remove();
  }, [timerMinutes, running]);

  // Listen for app close events
  useEffect(() => {
    const mediaButtonEventEmitter = new NativeEventEmitter(NativeModules.MediaButtonEvent);
    const subscription = mediaButtonEventEmitter.addListener('AppCloseRequested', () => {
      console.log('App close requested - cleaning up and closing');
      // Clean up any running timers
      if (intervalRef.current) {
        clearInterval(intervalRef.current);
        intervalRef.current = null;
      }
      // Note: The native side will handle the actual app closure
    });
    return () => subscription.remove();
  }, []);

  // Listen for native timer completion events
  useEffect(() => {
    const mediaButtonEventEmitter = new NativeEventEmitter(NativeModules.MediaButtonEvent);
    const subscription = mediaButtonEventEmitter.addListener('TimerCompleted', () => {
      console.log('Native timer completed - stopping UI timer');
      setRunning(false);
      setPaused(false);
      setTimer(timerMinutes * 60); // Reset to original duration
    });
    return () => subscription.remove();
  }, [timerMinutes]);

  // Listen for native timer pause events
  useEffect(() => {
    const mediaButtonEventEmitter = new NativeEventEmitter(NativeModules.MediaButtonEvent);
    const subscription = mediaButtonEventEmitter.addListener('TimerPaused', () => {
      console.log('Native timer paused - pausing UI timer');
      setPaused(true);
      setRunning(false); // Stop the UI countdown
    });
    return () => subscription.remove();
  }, []);

  // Listen for native timer resume events
  useEffect(() => {
    const mediaButtonEventEmitter = new NativeEventEmitter(NativeModules.MediaButtonEvent);
    const subscription = mediaButtonEventEmitter.addListener('TimerResumed', () => {
      console.log('Native timer resumed - resuming UI timer');
      setPaused(false);
      setRunning(true); // Resume the UI countdown
    });
    return () => subscription.remove();
  }, []);

  // Update native timer duration whenever timerMinutes changes
  useEffect(() => {
    if (NativeModules.TimerConfig) {
      NativeModules.TimerConfig.setTimerDuration(timerMinutes)
        .then((message: string) => console.log(message))
        .catch((error: any) => console.error('Failed to set timer duration:', error));
    }
  }, [timerMinutes]);

  // Handle timer countdown
  useEffect(() => {
    if (running && timer > 0) {
      intervalRef.current = setInterval(() => {
        setTimer(t => t > 0 ? t - 1 : 0);
      }, 1000);
    } else if (!running && intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [running]);

  // Handle timer completion
  useEffect(() => {
    if (running && timer === 0) {
      console.log('Timer completed');
      setRunning(false);
      setPaused(false);
    }
  }, [timer, running]);

  const handleInputChange = (text: string) => {
    setInputValue(text);
    const value = parseInt(text) || 0;
    if (value > 0 && value <= 999) {
      setTimerMinutes(value);
    }
  };

  const handlePresetPress = (minutes: number) => {
    setTimerMinutes(minutes);
    setInputValue(minutes.toString());
  };

  const handleStartStop = () => {
    if (running) {
      setRunning(false);
      setPaused(false);
      setTimer(timerMinutes * 60);
    } else if (paused) {
      // Resume from paused state
      setPaused(false);
      setRunning(true);
      
      // Trigger Audible to resume playing
      if (NativeModules.MediaButtonEvent) {
        NativeModules.MediaButtonEvent.startAudible()
          .then((message: string) => console.log('Audible resumed from paused state:', message))
          .catch((error: any) => console.error('Failed to resume Audible:', error));
      }
    } else {
      setTimer(timerMinutes * 60);
      setRunning(true);
      setPaused(false);
      
      // Trigger Audible to start playing when start button is pressed
      if (NativeModules.MediaButtonEvent) {
        NativeModules.MediaButtonEvent.startAudible()
          .then((message: string) => console.log('Audible started from start button:', message))
          .catch((error: any) => console.error('Failed to start Audible:', error));
      }
    }
  };

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container}>
        <StatusBar barStyle="light-content" backgroundColor="#121212" />
        
        <ScrollView contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
          {/* Header */}
          <View style={styles.header}>
            <Text style={styles.title}>Sleep Timer</Text>
            <Text style={styles.subtitle}>Configure your sleep timer duration</Text>
          </View>

          {/* Timer Input Controls */}
          <View style={styles.controlsContainer}>
            <View style={styles.inputContainer}>
              <Text style={styles.inputLabel}>Timer Duration (minutes)</Text>
              <TextInput
                style={styles.input}
                value={inputValue}
                onChangeText={handleInputChange}
                keyboardType="numeric"
                placeholder="Minutes"
                maxLength={3}
              />
            </View>
            
            <View style={styles.sliderContainer}>
              <View 
                style={styles.sliderTrack}
                {...panResponder.panHandlers}
              >
                <View 
                  style={[
                    styles.sliderProgress, 
                    { width: `${(timerMinutes / MAX_MINUTES) * 100}%` }
                  ]} 
                />
                <View 
                  style={[
                    styles.sliderThumb,
                    { left: `${(timerMinutes / MAX_MINUTES) * 100}%` }
                  ]}
                />
              </View>
              <View style={styles.sliderLabels}>
                <Text style={styles.sliderLabel}>1m</Text>
                <Text style={styles.sliderLabel}>{formatMinutes(timerMinutes)}</Text>
                <Text style={styles.sliderLabel}>60m</Text>
              </View>
            </View>
          </View>

          {/* Preset Buttons */}
          <View style={styles.presetsContainer}>
            <Text style={styles.presetsTitle}>Quick Select</Text>
            <View style={styles.presetsGrid}>
              {PRESET_MINUTES.map((minutes) => (
                <TouchableOpacity
                  key={minutes}
                  style={[
                    styles.presetButton,
                    timerMinutes === minutes && styles.presetButtonActive
                  ]}
                  onPress={() => handlePresetPress(minutes)}
                >
                  <Text style={[
                    styles.presetButtonText,
                    timerMinutes === minutes && styles.presetButtonTextActive
                  ]}>
                    {formatMinutes(minutes)}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>

          {/* Timer Display and Controls */}
          <View style={styles.timerSection}>
            <View style={styles.timerRow}>
              <View style={styles.timerDisplay}>
                <Text style={styles.timerText}>{formatTime(timer)}</Text>
                <Text style={styles.timerStatus}>
                  {running ? 'Running' : paused ? 'Paused' : 'Stopped'}
                </Text>
              </View>
              
              <TouchableOpacity 
                style={[styles.startButton, running && styles.stopButton]} 
                onPress={handleStartStop}
              >
                <Text style={styles.startButtonText}>
                  {running ? 'Stop' : paused ? 'Resume' : 'Start'} Timer
                </Text>
              </TouchableOpacity>
            </View>
          </View>

          {/* Hardware Button Info */}
          <View style={styles.infoContainer}>
            <Text style={styles.infoTitle}>Bluetooth Hardware Button</Text>
            <Text style={styles.infoText}>
              Press your device's media play button to start the timer even when the screen is off. 
              Audible will automatically pause when the timer completes.
            </Text>
          </View>
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#121212',
  },
  scrollContent: {
    flexGrow: 1,
    padding: 20,
  },
  header: {
    alignItems: 'center',
    marginBottom: 40,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#ffffff',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 16,
    color: '#b0b0b0',
    textAlign: 'center',
  },
  controlsContainer: {
    marginBottom: 40,
    backgroundColor: '#1e1e1e',
    borderRadius: 12,
    padding: 20,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
  },
  inputContainer: {
    alignItems: 'center',
    marginBottom: 30,
  },
  inputLabel: {
    fontSize: 16,
    color: '#ffffff',
    marginBottom: 10,
    fontWeight: '500',
  },
  input: {
    borderWidth: 2,
    borderColor: '#404040',
    borderRadius: 8,
    padding: 15,
    fontSize: 18,
    textAlign: 'center',
    backgroundColor: '#2a2a2a',
    color: '#ffffff',
    width: 120,
    fontWeight: 'bold',
  },
  sliderContainer: {
    width: '100%',
  },
  sliderTrack: {
    height: 8,
    backgroundColor: '#404040',
    borderRadius: 4,
    position: 'relative',
    marginVertical: 20,
  },
  sliderProgress: {
    height: '100%',
    backgroundColor: '#bb86fc',
    borderRadius: 4,
  },
  sliderThumb: {
    width: 20,
    height: 20,
    backgroundColor: '#bb86fc',
    borderRadius: 10,
    position: 'absolute',
    top: -6,
    marginLeft: -10,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
  },
  sliderLabels: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 5,
  },
  sliderLabel: {
    fontSize: 12,
    color: '#b0b0b0',
  },
  presetsContainer: {
    marginBottom: 30,
  },
  presetsTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 15,
    textAlign: 'center',
  },
  presetsGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 10,
  },
  presetButton: {
    backgroundColor: '#2a2a2a',
    borderWidth: 2,
    borderColor: '#404040',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 12,
    flex: 1,
    marginHorizontal: 3,
    alignItems: 'center',
    elevation: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.3,
    shadowRadius: 2,
  },
  presetButtonActive: {
    borderColor: '#bb86fc',
    backgroundColor: '#3e2f5b',
  },
  presetButtonText: {
    fontSize: 16,
    fontWeight: '500',
    color: '#ffffff',
  },
  presetButtonTextActive: {
    color: '#bb86fc',
    fontWeight: 'bold',
  },
  timerSection: {
    marginBottom: 30,
  },
  timerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#1e1e1e',
    borderRadius: 12,
    padding: 20,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
  },
  timerDisplay: {
    alignItems: 'center',
    flex: 1,
  },
  timerText: {
    fontSize: 48,
    fontWeight: 'bold',
    color: '#ffffff',
    fontFamily: 'monospace',
  },
  timerStatus: {
    fontSize: 16,
    color: '#b0b0b0',
    marginTop: 8,
  },
  startButton: {
    backgroundColor: '#bb86fc',
    paddingHorizontal: 25,
    paddingVertical: 15,
    borderRadius: 25,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
    marginLeft: 20,
  },
  stopButton: {
    backgroundColor: '#cf6679',
  },
  startButtonText: {
    color: '#000000',
    fontSize: 18,
    fontWeight: 'bold',
  },
  infoContainer: {
    backgroundColor: '#1e1e1e',
    padding: 20,
    borderRadius: 12,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.3,
    shadowRadius: 4,
  },
  infoTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 10,
  },
  infoText: {
    fontSize: 14,
    color: '#b0b0b0',
    lineHeight: 20,
  },
});

export default App;
