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
      setTimer(timerMinutes * 60);
      setRunning(true);
    });
    return () => subscription.remove();
  }, [timerMinutes]);

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
      setTimer(timerMinutes * 60);
    } else {
      setTimer(timerMinutes * 60);
      setRunning(true);
      
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
        <StatusBar barStyle="dark-content" backgroundColor="#f5f5f5" />
        
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
                  {running ? 'Running' : 'Stopped'}
                </Text>
              </View>
              
              <TouchableOpacity 
                style={[styles.startButton, running && styles.stopButton]} 
                onPress={handleStartStop}
              >
                <Text style={styles.startButtonText}>
                  {running ? 'Stop' : 'Start'} Timer
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
    backgroundColor: '#f5f5f5',
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
    color: '#333',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 16,
    color: '#666',
    textAlign: 'center',
  },
  controlsContainer: {
    marginBottom: 40,
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 20,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  inputContainer: {
    alignItems: 'center',
    marginBottom: 30,
  },
  inputLabel: {
    fontSize: 16,
    color: '#333',
    marginBottom: 10,
    fontWeight: '500',
  },
  input: {
    borderWidth: 2,
    borderColor: '#ddd',
    borderRadius: 8,
    padding: 15,
    fontSize: 18,
    textAlign: 'center',
    backgroundColor: 'white',
    width: 120,
    fontWeight: 'bold',
  },
  sliderContainer: {
    width: '100%',
  },
  sliderTrack: {
    height: 8,
    backgroundColor: '#e0e0e0',
    borderRadius: 4,
    position: 'relative',
    marginVertical: 20,
  },
  sliderProgress: {
    height: '100%',
    backgroundColor: '#4CAF50',
    borderRadius: 4,
  },
  sliderThumb: {
    width: 20,
    height: 20,
    backgroundColor: '#4CAF50',
    borderRadius: 10,
    position: 'absolute',
    top: -6,
    marginLeft: -10,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
  },
  sliderLabels: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 5,
  },
  sliderLabel: {
    fontSize: 12,
    color: '#666',
  },
  presetsContainer: {
    marginBottom: 30,
  },
  presetsTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
    marginBottom: 15,
    textAlign: 'center',
  },
  presetsGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 10,
  },
  presetButton: {
    backgroundColor: 'white',
    borderWidth: 2,
    borderColor: '#ddd',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 12,
    flex: 1,
    marginHorizontal: 3,
    alignItems: 'center',
    elevation: 1,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1,
    shadowRadius: 2,
  },
  presetButtonActive: {
    borderColor: '#4CAF50',
    backgroundColor: '#f1f8e9',
  },
  presetButtonText: {
    fontSize: 16,
    fontWeight: '500',
    color: '#333',
  },
  presetButtonTextActive: {
    color: '#4CAF50',
    fontWeight: 'bold',
  },
  timerSection: {
    marginBottom: 30,
  },
  timerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: 'white',
    borderRadius: 12,
    padding: 20,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  timerDisplay: {
    alignItems: 'center',
    flex: 1,
  },
  timerText: {
    fontSize: 48,
    fontWeight: 'bold',
    color: '#333',
    fontFamily: 'monospace',
  },
  timerStatus: {
    fontSize: 16,
    color: '#666',
    marginTop: 8,
  },
  startButton: {
    backgroundColor: '#4CAF50',
    paddingHorizontal: 25,
    paddingVertical: 15,
    borderRadius: 25,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.2,
    shadowRadius: 4,
    marginLeft: 20,
  },
  stopButton: {
    backgroundColor: '#f44336',
  },
  startButtonText: {
    color: 'white',
    fontSize: 18,
    fontWeight: 'bold',
  },
  infoContainer: {
    backgroundColor: 'white',
    padding: 20,
    borderRadius: 12,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 2 },
    shadowOpacity: 0.1,
    shadowRadius: 4,
  },
  infoTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
    marginBottom: 10,
  },
  infoText: {
    fontSize: 14,
    color: '#666',
    lineHeight: 20,
  },
});

export default App;
