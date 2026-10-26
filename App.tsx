/**
 * Audible Sleep Timer
 *
 * The timer itself lives in native code (it keeps running with the screen off); this screen
 * mirrors its state and sends commands to it.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AppState,
  DeviceEventEmitter,
  NativeModules,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import Slider from '@react-native-community/slider';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

export type TimerState = 'idle' | 'running' | 'paused';

export interface NativeTimerState {
  state: TimerState;
  remainingMs: number;
  durationMinutes: number;
  minMinutes: number;
  maxMinutes: number;
  audiblePlaying: boolean;
  audibleInstalled: boolean;
  notificationAccess: boolean;
  gainDb: number;
  gainMinDb: number;
  gainMaxDb: number;
  gainSupported: boolean;
  gainApplied: boolean;
  gainOverridden: boolean;
  gainLegacyMode: boolean;
  gainDetectionGranted: boolean;
  gainSessionCount: number;
  gainError: string | null;
  keyFilterEnabled: boolean;
  headsetStepsEnabled: boolean;
}

interface SleepTimerModule {
  getState(): Promise<NativeTimerState>;
  setDuration(minutes: number): Promise<NativeTimerState>;
  start(): Promise<NativeTimerState>;
  stop(): Promise<NativeTimerState>;
  resume(): Promise<NativeTimerState>;
  setGain(db: number): Promise<NativeTimerState>;
  openNotificationAccessSettings(): Promise<void>;
  openAccessibilitySettings(): Promise<void>;
  setHeadsetSteps(enabled: boolean): Promise<NativeTimerState>;
  close(): Promise<void>;
}

export const STATE_EVENT = 'SleepTimerStateChanged';
const PRESET_MINUTES = [15, 20, 30, 45, 60];
const GAIN_STEP_DB = 0.5;

const DEFAULT_STATE: NativeTimerState = {
  state: 'idle',
  remainingMs: 15 * 60_000,
  durationMinutes: 15,
  minMinutes: 1,
  maxMinutes: 120,
  audiblePlaying: false,
  audibleInstalled: true,
  notificationAccess: true,
  gainDb: 0,
  gainMinDb: -40,
  gainMaxDb: 0,
  gainSupported: true,
  gainApplied: false,
  gainOverridden: false,
  gainLegacyMode: false,
  gainDetectionGranted: false,
  gainSessionCount: 0,
  gainError: null,
  keyFilterEnabled: false,
  headsetStepsEnabled: true,
};

const nativeTimer = (): SleepTimerModule | undefined =>
  NativeModules.SleepTimer;

const clamp = (value: number, min: number, max: number) =>
  Math.min(max, Math.max(min, value));

const roundGain = (db: number) => Math.round(db / GAIN_STEP_DB) * GAIN_STEP_DB;

const logError = (action: string) => (error: unknown) =>
  console.warn(`SleepTimer.${action} failed`, error);

export function formatTime(ms: number) {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60)
    .toString()
    .padStart(2, '0');
  const s = (total % 60).toString().padStart(2, '0');
  return h > 0 ? `${h}:${m}:${s}` : `${m}:${s}`;
}

export function formatMinutes(minutes: number) {
  if (minutes < 60) {
    return `${minutes}m`;
  }
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  return mins === 0 ? `${hours}h` : `${hours}h ${mins}m`;
}

const App = () => {
  const [native, setNative] = useState<NativeTimerState>(DEFAULT_STATE);
  const [minutes, setMinutes] = useState(DEFAULT_STATE.durationMinutes);
  const [inputValue, setInputValue] = useState(
    String(DEFAULT_STATE.durationMinutes),
  );
  const [gainDb, setGainDb] = useState(DEFAULT_STATE.gainDb);
  const [endAt, setEndAt] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const editingDuration = useRef(false);
  const editingGain = useRef(false);

  const applyState = useCallback((next: NativeTimerState) => {
    const timestamp = Date.now();
    setNative(next);
    setEndAt(timestamp + next.remainingMs);
    setNow(timestamp);
    if (!editingDuration.current) {
      setMinutes(next.durationMinutes);
      setInputValue(String(next.durationMinutes));
    }
    if (!editingGain.current) {
      setGainDb(next.gainDb);
    }
  }, []);

  const refresh = useCallback(() => {
    nativeTimer()?.getState().then(applyState).catch(logError('getState'));
  }, [applyState]);

  useEffect(() => {
    refresh();
    const stateSub = DeviceEventEmitter.addListener(STATE_EVENT, applyState);
    const appStateSub = AppState.addEventListener('change', status => {
      if (status === 'active') {
        refresh();
      }
    });
    return () => {
      stateSub.remove();
      appStateSub.remove();
    };
  }, [applyState, refresh]);

  useEffect(() => {
    if (native.state !== 'running') {
      return;
    }
    const id = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(id);
  }, [native.state]);

  const remainingMs =
    native.state === 'running'
      ? Math.max(0, endAt - now)
      : native.state === 'paused'
      ? native.remainingMs
      : minutes * 60_000;

  const commitDuration = useCallback(
    (value: number) => {
      const next = clamp(
        Math.round(value),
        native.minMinutes,
        native.maxMinutes,
      );
      editingDuration.current = false;
      setMinutes(next);
      setInputValue(String(next));
      nativeTimer()
        ?.setDuration(next)
        .then(applyState)
        .catch(logError('setDuration'));
    },
    [applyState, native.minMinutes, native.maxMinutes],
  );

  const handleDurationSlide = (value: number) => {
    editingDuration.current = true;
    const next = Math.round(value);
    setMinutes(next);
    setInputValue(String(next));
  };

  const handleInputChange = (text: string) => {
    editingDuration.current = true;
    setInputValue(text.replace(/[^0-9]/g, ''));
  };

  const handleInputEnd = () => {
    const value = parseInt(inputValue, 10);
    if (Number.isFinite(value) && value > 0) {
      commitDuration(value);
    } else {
      editingDuration.current = false;
      setInputValue(String(minutes));
    }
  };

  const handleMainButton = () => {
    const module = nativeTimer();
    if (!module) {
      return;
    }
    const action =
      native.state === 'running'
        ? module.stop()
        : native.state === 'paused'
        ? module.resume()
        : module.start();
    action.then(applyState).catch(logError('startStop'));
  };

  const handleCancel = () => {
    nativeTimer()?.stop().then(applyState).catch(logError('stop'));
  };

  const sendGain = useCallback(
    (db: number) => {
      nativeTimer()?.setGain(db).then(applyState).catch(logError('setGain'));
    },
    [applyState],
  );

  const handleGainSlide = (value: number) => {
    editingGain.current = true;
    const db = roundGain(value);
    if (db !== gainDb) {
      setGainDb(db);
      sendGain(db);
    }
  };

  const handleGainComplete = (value: number) => {
    editingGain.current = false;
    const db = roundGain(value);
    setGainDb(db);
    sendGain(db);
  };

  const nudgeGain = (delta: number) => {
    const db = clamp(
      roundGain(gainDb + delta),
      native.gainMinDb,
      native.gainMaxDb,
    );
    setGainDb(db);
    sendGain(db);
  };

  const handleOpenAccess = () => {
    nativeTimer()
      ?.openNotificationAccessSettings()
      .catch(logError('openNotificationAccessSettings'));
  };

  const handleOpenKeyFilter = () => {
    nativeTimer()
      ?.openAccessibilitySettings()
      .catch(logError('openAccessibilitySettings'));
  };

  const handleHeadsetSteps = (enabled: boolean) => {
    nativeTimer()
      ?.setHeadsetSteps(enabled)
      .then(applyState)
      .catch(logError('setHeadsetSteps'));
  };

  const handleClose = () => {
    nativeTimer()?.close().catch(logError('close'));
  };

  const statusText =
    native.state === 'running'
      ? 'Running'
      : native.state === 'paused'
      ? 'Paused – continues when Audible plays'
      : native.notificationAccess
      ? 'Starts when Audible plays'
      : 'Stopped';

  const gainProblem = !native.gainSupported
    ? 'Needs Android 9 or newer.'
    : native.gainError
    ? `Unavailable on this device (${native.gainError}).`
    : native.gainOverridden
    ? 'Another equalizer app is controlling the audio effect.'
    : null;

  return (
    <SafeAreaProvider>
      <SafeAreaView style={styles.container}>
        <StatusBar barStyle="light-content" backgroundColor="#121212" />

        <ScrollView
          contentContainerStyle={styles.scrollContent}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
        >
          <View style={styles.header}>
            <Text style={styles.title}>Sleep Timer</Text>
            <Text style={styles.subtitle}>
              Pauses Audible when the timer runs out
            </Text>
          </View>

          {!native.notificationAccess && (
            <View style={[styles.card, styles.warningCard]}>
              <Text style={styles.cardTitle}>Allow notification access</Text>
              <Text style={styles.infoText}>
                Needed so the timer can see when Audible starts or stops (for
                example from your headphone button) and pause it reliably. No
                notifications are read.
              </Text>
              <TouchableOpacity
                testID="open-access"
                style={styles.secondaryButton}
                onPress={handleOpenAccess}
              >
                <Text style={styles.secondaryButtonText}>Open settings</Text>
              </TouchableOpacity>
            </View>
          )}

          {!native.audibleInstalled && (
            <View style={[styles.card, styles.warningCard]}>
              <Text style={styles.infoText}>
                Audible doesn't appear to be installed.
              </Text>
            </View>
          )}

          <View style={styles.timerRow}>
            <View style={styles.timerDisplay}>
              <Text style={styles.timerText} testID="timer-text">
                {formatTime(remainingMs)}
              </Text>
              <Text style={styles.timerStatus}>{statusText}</Text>
              {native.notificationAccess && (
                <Text style={styles.audibleStatus}>
                  Audible{' '}
                  {native.audiblePlaying ? 'is playing' : 'is not playing'}
                </Text>
              )}
            </View>
            <View>
              <TouchableOpacity
                testID="main-button"
                style={[
                  styles.startButton,
                  native.state === 'running' && styles.stopButton,
                ]}
                onPress={handleMainButton}
              >
                <Text style={styles.startButtonText}>
                  {native.state === 'running'
                    ? 'Stop'
                    : native.state === 'paused'
                    ? 'Resume'
                    : 'Start'}
                </Text>
              </TouchableOpacity>
              {native.state === 'paused' && (
                <TouchableOpacity
                  testID="cancel-button"
                  style={styles.cancelButton}
                  onPress={handleCancel}
                >
                  <Text style={styles.cancelButtonText}>Cancel</Text>
                </TouchableOpacity>
              )}
            </View>
          </View>

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Timer duration (minutes)</Text>
            <TextInput
              testID="duration-input"
              style={styles.input}
              value={inputValue}
              onChangeText={handleInputChange}
              onEndEditing={handleInputEnd}
              keyboardType="number-pad"
              placeholder="Minutes"
              placeholderTextColor="#777777"
              maxLength={3}
            />
            <Slider
              testID="duration-slider"
              style={styles.slider}
              minimumValue={native.minMinutes}
              maximumValue={native.maxMinutes}
              step={1}
              value={minutes}
              onValueChange={handleDurationSlide}
              onSlidingComplete={commitDuration}
              minimumTrackTintColor="#bb86fc"
              maximumTrackTintColor="#404040"
              thumbTintColor="#bb86fc"
            />
            <View style={styles.sliderLabels}>
              <Text style={styles.sliderLabel}>
                {formatMinutes(native.minMinutes)}
              </Text>
              <Text style={styles.sliderLabel}>{formatMinutes(minutes)}</Text>
              <Text style={styles.sliderLabel}>
                {formatMinutes(native.maxMinutes)}
              </Text>
            </View>
            <View style={styles.presetsGrid}>
              {PRESET_MINUTES.map(preset => (
                <TouchableOpacity
                  key={preset}
                  testID={`preset-${preset}`}
                  style={[
                    styles.presetButton,
                    minutes === preset && styles.presetButtonActive,
                  ]}
                  onPress={() => commitDuration(preset)}
                >
                  <Text
                    style={[
                      styles.presetButtonText,
                      minutes === preset && styles.presetButtonTextActive,
                    ]}
                  >
                    {formatMinutes(preset)}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          </View>

          <View style={styles.card}>
            <View style={styles.gainHeader}>
              <Text style={styles.cardTitle}>Volume fine-tune</Text>
              <Text style={styles.gainValue} testID="gain-value">
                {gainDb >= 0 ? '0 dB' : `${gainDb.toFixed(1)} dB`}
              </Text>
            </View>
            <View style={styles.gainRow}>
              <TouchableOpacity
                testID="gain-down"
                style={styles.nudgeButton}
                onPress={() => nudgeGain(-GAIN_STEP_DB)}
                disabled={!native.gainSupported}
              >
                <Text style={styles.nudgeButtonText}>−</Text>
              </TouchableOpacity>
              <Slider
                testID="gain-slider"
                style={styles.gainSlider}
                minimumValue={native.gainMinDb}
                maximumValue={native.gainMaxDb}
                step={GAIN_STEP_DB}
                value={gainDb}
                disabled={!native.gainSupported}
                onValueChange={handleGainSlide}
                onSlidingComplete={handleGainComplete}
                minimumTrackTintColor="#03dac6"
                maximumTrackTintColor="#404040"
                thumbTintColor="#03dac6"
              />
              <TouchableOpacity
                testID="gain-up"
                style={styles.nudgeButton}
                onPress={() => nudgeGain(GAIN_STEP_DB)}
                disabled={!native.gainSupported}
              >
                <Text style={styles.nudgeButtonText}>+</Text>
              </TouchableOpacity>
            </View>
            {gainProblem && (
              <Text testID="gain-status" style={styles.infoText}>
                {gainProblem}
              </Text>
            )}
            <View style={styles.presetsGrid}>
              {!native.gainDetectionGranted && (
                <TouchableOpacity
                  testID="gain-retry"
                  style={styles.presetButton}
                  onPress={refresh}
                >
                  <Text style={styles.presetButtonText}>Retry</Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity
                testID="gain-reset"
                style={styles.presetButton}
                onPress={() => nudgeGain(-gainDb)}
              >
                <Text style={styles.presetButtonText}>Reset</Text>
              </TouchableOpacity>
            </View>
          </View>

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Headphone buttons</Text>
            <View style={styles.presetsGrid}>
              <TouchableOpacity
                testID="headset-steps-on"
                style={[
                  styles.presetButton,
                  native.headsetStepsEnabled && styles.presetButtonActive,
                ]}
                onPress={() => handleHeadsetSteps(true)}
                disabled={!native.gainSupported}
              >
                <Text style={styles.presetButtonText}>Fine steps</Text>
              </TouchableOpacity>
              <TouchableOpacity
                testID="headset-steps-off"
                style={[
                  styles.presetButton,
                  !native.headsetStepsEnabled && styles.presetButtonActive,
                ]}
                onPress={() => handleHeadsetSteps(false)}
              >
                <Text style={styles.presetButtonText}>System volume</Text>
              </TouchableOpacity>
            </View>
            {native.headsetStepsEnabled && !native.keyFilterEnabled && (
              <TouchableOpacity
                testID="open-key-filter"
                style={styles.secondaryButton}
                onPress={handleOpenKeyFilter}
              >
                <Text style={styles.secondaryButtonText}>
                  Enable phone button detection
                </Text>
              </TouchableOpacity>
            )}
          </View>

          <TouchableOpacity
            testID="close-button"
            style={styles.closeButton}
            onPress={handleClose}
          >
            <Text style={styles.closeButtonText}>Close app</Text>
          </TouchableOpacity>
          <Text style={styles.closeHint}>
            Stops the timer and volume fine-tune and fully shuts the app down.
          </Text>
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
};

const cardShadow = {
  elevation: 2,
  shadowColor: '#000',
  shadowOffset: { width: 0, height: 2 },
  shadowOpacity: 0.3,
  shadowRadius: 4,
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
    marginBottom: 24,
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
  card: {
    backgroundColor: '#1e1e1e',
    borderRadius: 12,
    padding: 20,
    marginBottom: 20,
    ...cardShadow,
  },
  warningCard: {
    borderWidth: 1,
    borderColor: '#cf6679',
  },
  cardTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#ffffff',
    marginBottom: 10,
  },
  timerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: '#1e1e1e',
    borderRadius: 12,
    padding: 20,
    marginBottom: 20,
    ...cardShadow,
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
    fontSize: 15,
    color: '#b0b0b0',
    marginTop: 8,
    textAlign: 'center',
  },
  audibleStatus: {
    fontSize: 13,
    color: '#808080',
    marginTop: 4,
  },
  startButton: {
    backgroundColor: '#bb86fc',
    paddingHorizontal: 25,
    paddingVertical: 15,
    borderRadius: 25,
    marginLeft: 20,
    alignItems: 'center',
    ...cardShadow,
  },
  stopButton: {
    backgroundColor: '#cf6679',
  },
  startButtonText: {
    color: '#000000',
    fontSize: 18,
    fontWeight: 'bold',
  },
  cancelButton: {
    marginLeft: 20,
    marginTop: 10,
    paddingVertical: 8,
    alignItems: 'center',
  },
  cancelButtonText: {
    color: '#b0b0b0',
    fontSize: 15,
  },
  input: {
    alignSelf: 'center',
    borderWidth: 2,
    borderColor: '#404040',
    borderRadius: 8,
    padding: 12,
    fontSize: 18,
    textAlign: 'center',
    backgroundColor: '#2a2a2a',
    color: '#ffffff',
    width: 120,
    fontWeight: 'bold',
  },
  slider: {
    width: '100%',
    height: 40,
    marginTop: 16,
  },
  sliderLabels: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  sliderLabel: {
    fontSize: 12,
    color: '#b0b0b0',
  },
  presetsGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 16,
  },
  presetButton: {
    backgroundColor: '#2a2a2a',
    borderWidth: 2,
    borderColor: '#404040',
    borderRadius: 8,
    paddingVertical: 12,
    flex: 1,
    marginHorizontal: 3,
    alignItems: 'center',
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
  gainHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'baseline',
  },
  gainValue: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#03dac6',
    fontFamily: 'monospace',
  },
  gainRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  gainSlider: {
    flex: 1,
    height: 40,
    marginHorizontal: 8,
  },
  nudgeButton: {
    width: 44,
    height: 44,
    borderRadius: 22,
    backgroundColor: '#2a2a2a',
    borderWidth: 2,
    borderColor: '#404040',
    alignItems: 'center',
    justifyContent: 'center',
  },
  nudgeButtonText: {
    color: '#ffffff',
    fontSize: 22,
    fontWeight: 'bold',
  },
  infoText: {
    fontSize: 14,
    color: '#b0b0b0',
    lineHeight: 20,
  },
  infoTextSmall: {
    fontSize: 12,
    color: '#808080',
    lineHeight: 18,
    marginTop: 8,
  },
  secondaryButton: {
    alignSelf: 'flex-start',
    marginTop: 12,
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#bb86fc',
  },
  secondaryButtonText: {
    color: '#bb86fc',
    fontSize: 15,
    fontWeight: '600',
  },
  closeButton: {
    backgroundColor: '#2a2a2a',
    borderWidth: 2,
    borderColor: '#cf6679',
    borderRadius: 25,
    paddingVertical: 14,
    alignItems: 'center',
  },
  closeButtonText: {
    color: '#cf6679',
    fontSize: 17,
    fontWeight: 'bold',
  },
  closeHint: {
    fontSize: 12,
    color: '#808080',
    textAlign: 'center',
    marginTop: 8,
    marginBottom: 20,
  },
});

export default App;
