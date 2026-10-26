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
  setGainLegacyMode(enabled: boolean): Promise<NativeTimerState>;
  openNotificationAccessSettings(): Promise<void>;
  getButtonLog(): Promise<string[]>;
  clearButtonLog(): Promise<void>;
  openAccessibilitySettings(): Promise<void>;
  setHeadsetSteps(enabled: boolean): Promise<NativeTimerState>;
  close(): Promise<void>;
}

export const STATE_EVENT = 'SleepTimerStateChanged';
export const BUTTON_LOG_EVENT = 'SleepTimerButtonLog';
const MAX_LOG_ENTRIES = 300;
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

/** Output level relative to the current system volume, as a percentage. */
export function gainPercent(db: number) {
  const percent = Math.pow(10, db / 20) * 100;
  return percent < 10 ? percent.toFixed(1) : Math.round(percent).toString();
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
  const [buttonLog, setButtonLog] = useState<string[]>([]);
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
    nativeTimer()
      ?.getButtonLog()
      .then(entries => setButtonLog(entries.slice(-MAX_LOG_ENTRIES)))
      .catch(logError('getButtonLog'));
    const logSub = DeviceEventEmitter.addListener(
      BUTTON_LOG_EVENT,
      (entry: string) =>
        setButtonLog(prev => [...prev, entry].slice(-MAX_LOG_ENTRIES)),
    );
    return () => logSub.remove();
  }, []);

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

  const handleClearLog = () => {
    setButtonLog([]);
    nativeTimer()?.clearButtonLog().catch(logError('clearButtonLog'));
  };

  const handleClose = () => {
    nativeTimer()?.close().catch(logError('close'));
  };

  const handleGainMode = (legacy: boolean) => {
    nativeTimer()
      ?.setGainLegacyMode(legacy)
      .then(applyState)
      .catch(logError('setGainLegacyMode'));
  };

  const statusText =
    native.state === 'running'
      ? 'Running'
      : native.state === 'paused'
      ? 'Paused – continues when Audible plays'
      : native.notificationAccess
      ? 'Starts when Audible plays'
      : 'Stopped';

  const gainNote = !native.gainSupported
    ? 'Volume fine-tuning needs Android 9 or newer.'
    : native.gainError
    ? `Volume fine-tuning is unavailable on this device (${native.gainError}).`
    : native.gainOverridden
    ? 'Another app (e.g. Precise Volume) is controlling the audio effect. Turn it off to use this slider.'
    : gainDb >= 0
    ? 'Off – audio plays at the normal system volume.'
    : !native.gainApplied
    ? native.gainLegacyMode
      ? 'Not applied. The Legacy mix effect is not active; try Audible sessions instead.'
      : native.gainSessionCount > 0
      ? 'Attenuation is not active on the detected session. Pause and resume Audible, then retry.'
      : native.gainDetectionGranted
      ? 'Not applied yet. Audio detection permission is granted, but no Audible audio session was found. Start Audible playback, then retry audio detection.'
      : 'Not applied: no Audible audio session detected. Pause and resume Audible. If it stays undetected, complete the audio detection setup below (Android 10+).'
    : native.gainLegacyMode
    ? 'Legacy output-mix effect configured. Some phones bypass it; use Audible sessions if the sound does not change.'
    : `Audible session effect configured: target ${gainPercent(
        gainDb,
      )}% of the current system volume.`;

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
            <View style={styles.presetsGrid}>
              <TouchableOpacity
                testID="gain-session-mode"
                style={[
                  styles.presetButton,
                  !native.gainLegacyMode && styles.presetButtonActive,
                ]}
                onPress={() => handleGainMode(false)}
              >
                <Text style={styles.presetButtonText}>Audible sessions</Text>
              </TouchableOpacity>
              <TouchableOpacity
                testID="gain-legacy-mode"
                style={[
                  styles.presetButton,
                  native.gainLegacyMode && styles.presetButtonActive,
                ]}
                onPress={() => handleGainMode(true)}
              >
                <Text style={styles.presetButtonText}>Legacy mix</Text>
              </TouchableOpacity>
            </View>
            <Text testID="gain-status" style={styles.infoText}>
              {gainNote}
            </Text>
            <Text style={styles.infoTextSmall}>
              Targets gain below the quietest system volume step without
              changing Android's volume levels. Device support is required. Turn
              off other equalizers such as Precise Volume. Legacy mix can affect
              all audio. Removed when the app is closed.
            </Text>
            {!native.gainLegacyMode && !native.gainDetectionGranted && (
              <View testID="gain-detection-help">
                <Text style={styles.infoText}>
                  Audio detection permission is not granted. When Audible does
                  not announce its session, the slider cannot change its volume.
                  On your Pixel 7, use Audible sessions and enable audio
                  detection from a trusted computer with USB debugging
                  authorized (Android 10+). The app cannot grant this diagnostic
                  permission itself.
                </Text>
                <Text selectable style={styles.infoTextSmall}>
                  adb shell pm grant com.sleeptimer android.permission.DUMP
                </Text>
                <Text style={styles.infoTextSmall}>
                  After granting it, play Audible and tap Retry audio detection.
                  Legacy mix is not a replacement for this setup on phones that
                  bypass global effects.
                </Text>
              </View>
            )}
            {!native.gainLegacyMode && native.gainDetectionGranted && (
              <Text
                testID="gain-detection-granted"
                style={styles.infoTextSmall}
              >
                Audio detection permission: granted.
              </Text>
            )}
            <TouchableOpacity
              testID="gain-retry"
              style={styles.secondaryButton}
              onPress={refresh}
            >
              <Text style={styles.secondaryButtonText}>
                Retry audio detection
              </Text>
            </TouchableOpacity>
            {gainDb < 0 && (
              <TouchableOpacity
                testID="gain-reset"
                style={styles.secondaryButton}
                onPress={() => nudgeGain(-gainDb)}
              >
                <Text style={styles.secondaryButtonText}>Reset to 0 dB</Text>
              </TouchableOpacity>
            )}
          </View>

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Headphone buttons</Text>
            <Text style={styles.infoText}>
              Play/pause starts Audible and the timer with the screen off; when
              the timer runs out Audible pauses. Double-press the phone's volume
              down while the app is open to skip back.
            </Text>
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
            <Text testID="headset-steps-status" style={styles.infoTextSmall}>
              {!native.gainSupported
                ? 'Fine steps need Android 9 or newer.'
                : native.headsetStepsEnabled
                ? `Bluetooth headphone volume buttons move volume fine-tune by ${GAIN_STEP_DB} dB while Audible plays (screen off or this app open). At 0 dB, volume up raises the system volume. Double-press volume up to hear the time (24-hour). ${
                    native.keyFilterEnabled
                      ? 'Phone volume buttons are recognised and change the system volume as usual.'
                      : "Turn on the key filter below so the phone's own volume buttons keep changing the system volume with the screen off."
                  }`
                : 'Headphone volume buttons change the system volume as usual.'}
            </Text>
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

          <View style={styles.card} testID="button-log">
            <Text style={styles.cardTitle}>Button log</Text>
            <Text style={styles.infoText}>
              Press your headphone buttons to see what reaches the phone: KEY
              lines are button presses, VOLUME lines are media-volume changes.
            </Text>
            <Text testID="key-filter-status" style={styles.infoTextSmall}>
              {native.keyFilterEnabled
                ? 'Key filter: on – keys are logged with the screen off.'
                : 'Key filter: off – keys are only logged while this screen is open. Enable "Sleep timer button log" in Accessibility settings. If it is greyed out, open App info › ⋮ › Allow restricted settings first.'}
            </Text>
            <View style={styles.presetsGrid}>
              {!native.keyFilterEnabled && (
                <TouchableOpacity
                  testID="open-key-filter"
                  style={styles.presetButton}
                  onPress={handleOpenKeyFilter}
                >
                  <Text style={styles.presetButtonText}>Enable key filter</Text>
                </TouchableOpacity>
              )}
              <TouchableOpacity
                testID="clear-log"
                style={styles.presetButton}
                onPress={handleClearLog}
              >
                <Text style={styles.presetButtonText}>Clear</Text>
              </TouchableOpacity>
            </View>
            <View style={styles.logBox}>
              {buttonLog.length === 0 ? (
                <Text style={styles.logLine}>No events yet.</Text>
              ) : (
                buttonLog
                  .slice()
                  .reverse()
                  .map((entry, index) => (
                    <Text
                      key={`${buttonLog.length - index}`}
                      selectable
                      style={styles.logLine}
                      testID="log-entry"
                    >
                      {entry}
                    </Text>
                  ))
              )}
            </View>
          </View>
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
  logBox: {
    marginTop: 16,
    padding: 10,
    borderRadius: 8,
    backgroundColor: '#0a0a0a',
  },
  logLine: {
    fontSize: 11,
    color: '#c0c0c0',
    fontFamily: 'monospace',
    marginBottom: 4,
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
