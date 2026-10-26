/**
 * @format
 */

import React from 'react';
import mockSafeAreaContext from 'react-native-safe-area-context/jest/mock';
import { DeviceEventEmitter, NativeModules } from 'react-native';
import ReactTestRenderer from 'react-test-renderer';
import App, {
  BUTTON_LOG_EVENT,
  formatTime,
  gainPercent,
  NativeTimerState,
  STATE_EVENT,
} from '../App';

jest.mock('react-native-safe-area-context', () => mockSafeAreaContext);

const baseState: NativeTimerState = {
  state: 'idle',
  remainingMs: 30 * 60_000,
  durationMinutes: 30,
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

const resolved = (overrides: Partial<NativeTimerState> = {}) =>
  jest.fn(() => Promise.resolve({ ...baseState, ...overrides }));

function mockModule() {
  const module = {
    getState: resolved(),
    setDuration: jest.fn((minutes: number) =>
      Promise.resolve({ ...baseState, durationMinutes: minutes }),
    ),
    start: resolved({ state: 'running' }),
    stop: resolved(),
    resume: resolved({ state: 'running' }),
    setGain: jest.fn((db: number) =>
      Promise.resolve({ ...baseState, gainDb: db }),
    ),
    setGainLegacyMode: jest.fn((enabled: boolean) =>
      Promise.resolve({ ...baseState, gainLegacyMode: enabled }),
    ),
    openNotificationAccessSettings: jest.fn(() => Promise.resolve()),
    close: jest.fn(() => Promise.resolve()),
    getButtonLog: jest.fn(() =>
      Promise.resolve(['12:00:00.000  Logging started']),
    ),
    clearButtonLog: jest.fn(() => Promise.resolve()),
    openAccessibilitySettings: jest.fn(() => Promise.resolve()),
    setHeadsetSteps: jest.fn((enabled: boolean) =>
      Promise.resolve({ ...baseState, headsetStepsEnabled: enabled }),
    ),
  };
  NativeModules.SleepTimer = module;
  return module;
}

async function render() {
  let renderer!: ReactTestRenderer.ReactTestRenderer;
  await ReactTestRenderer.act(async () => {
    renderer = ReactTestRenderer.create(<App />);
  });
  renderers.push(renderer);
  return renderer;
}

const renderers: ReactTestRenderer.ReactTestRenderer[] = [];

const press = async (
  renderer: ReactTestRenderer.ReactTestRenderer,
  id: string,
) => {
  await ReactTestRenderer.act(async () => {
    renderer.root.findByProps({ testID: id }).props.onPress();
  });
};

const text = (renderer: ReactTestRenderer.ReactTestRenderer, id: string) =>
  renderer.root.findByProps({ testID: id }).props.children;

afterEach(async () => {
  await ReactTestRenderer.act(async () => {
    renderers.splice(0).forEach(renderer => renderer.unmount());
  });
  delete NativeModules.SleepTimer;
  jest.useRealTimers();
});

test('renders without the native module', async () => {
  const renderer = await render();
  expect(text(renderer, 'timer-text')).toBe('15:00');
});

test('loads the native state on mount', async () => {
  const module = mockModule();
  const renderer = await render();
  expect(module.getState).toHaveBeenCalled();
  expect(text(renderer, 'timer-text')).toBe('30:00');
});

test('presets, start, gain and close call the native module', async () => {
  const module = mockModule();
  const renderer = await render();

  await press(renderer, 'preset-45');
  expect(module.setDuration).toHaveBeenCalledWith(45);

  await press(renderer, 'main-button');
  expect(module.start).toHaveBeenCalled();

  await press(renderer, 'gain-down');
  expect(module.setGain).toHaveBeenCalledWith(-0.5);
  expect(text(renderer, 'gain-value')).toBe('-0.5 dB');

  await press(renderer, 'close-button');
  expect(module.close).toHaveBeenCalled();
});

test('follows native state events', async () => {
  mockModule();
  const renderer = await render();
  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit(STATE_EVENT, {
      ...baseState,
      state: 'paused',
      remainingMs: 61_000,
    });
  });
  expect(text(renderer, 'timer-text')).toBe('01:01');
  expect(
    renderer.root.findAllByProps({ testID: 'cancel-button' }),
  ).not.toHaveLength(0);
});

test('prompts for notification access when missing', async () => {
  const module = mockModule();
  module.getState = resolved({ notificationAccess: false });
  const renderer = await render();
  await press(renderer, 'open-access');
  expect(module.openNotificationAccessSettings).toHaveBeenCalled();
});

test('resumes a paused timer and cancels it without starting playback', async () => {
  const module = mockModule();
  module.getState = resolved({ state: 'paused', remainingMs: 61_000 });
  const renderer = await render();

  await press(renderer, 'cancel-button');
  expect(module.stop).toHaveBeenCalledTimes(1);
  expect(module.start).not.toHaveBeenCalled();

  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit(STATE_EVENT, {
      ...baseState,
      state: 'paused',
      remainingMs: 61_000,
    });
  });
  await press(renderer, 'main-button');
  expect(module.resume).toHaveBeenCalledTimes(1);
});

test('counts down native remaining time and stops through the main button', async () => {
  jest.useFakeTimers({
    doNotFake: ['nextTick', 'setImmediate', 'queueMicrotask'],
  });
  const module = mockModule();
  module.getState = resolved({ state: 'running', remainingMs: 61_000 });
  const renderer = await render();

  ReactTestRenderer.act(() => jest.advanceTimersByTime(2_000));
  expect(text(renderer, 'timer-text')).toBe('00:59');

  await press(renderer, 'main-button');
  expect(module.stop).toHaveBeenCalledTimes(1);
});

test('clamps gain and supports the slider and reset without system volume changes', async () => {
  const module = mockModule();
  module.getState = resolved({ gainDb: -40 });
  const renderer = await render();

  await press(renderer, 'gain-down');
  expect(module.setGain).toHaveBeenLastCalledWith(-40);

  await ReactTestRenderer.act(async () => {
    renderer.root
      .findByProps({ testID: 'gain-slider' })
      .props.onSlidingComplete(-20);
  });
  expect(module.setGain).toHaveBeenLastCalledWith(-20);
  expect(text(renderer, 'gain-value')).toBe('-20.0 dB');

  await press(renderer, 'gain-reset');
  expect(module.setGain).toHaveBeenLastCalledWith(0);
  expect(text(renderer, 'gain-value')).toBe('0 dB');
});

test('reports rejected native commands', async () => {
  const module = mockModule();
  const error = new Error('Playback unavailable');
  module.start.mockRejectedValueOnce(error);
  const warning = jest.spyOn(console, 'warn').mockImplementation(() => {});
  try {
    const renderer = await render();
    await press(renderer, 'main-button');
    expect(warning).toHaveBeenCalledWith('SleepTimer.startStop failed', error);
  } finally {
    warning.mockRestore();
  }
});

test('formatting helpers', () => {
  expect(formatTime(0)).toBe('00:00');
  expect(formatTime(59_001)).toBe('01:00');
  expect(formatTime(3_600_000)).toBe('1:00:00');
  expect(gainPercent(0)).toBe('100');
  expect(gainPercent(-20)).toBe('10');
  expect(gainPercent(-40)).toBe('1.0');
});

test('does not present an unattached gain slider as working attenuation', async () => {
  const module = mockModule();
  module.getState = resolved({ gainDb: -20 });
  const renderer = await render();
  expect(text(renderer, 'gain-status')).toContain(
    'Not applied: no Audible audio session detected',
  );
  expect(text(renderer, 'gain-status')).not.toContain('10%');
  expect(
    renderer.root.findAllByProps({ testID: 'gain-detection-help' }).length,
  ).toBeGreaterThan(0);
});

test('shows verified session configuration separately from legacy mix', async () => {
  const module = mockModule();
  module.getState = resolved({
    gainDb: -20,
    gainApplied: true,
    gainDetectionGranted: true,
    gainSessionCount: 1,
  });
  module.setGainLegacyMode.mockImplementation((enabled: boolean) =>
    Promise.resolve({
      ...baseState,
      gainDb: -20,
      gainApplied: true,
      gainLegacyMode: enabled,
      gainSessionCount: enabled ? 0 : 1,
    }),
  );
  const renderer = await render();
  expect(text(renderer, 'gain-status')).toContain('target 10%');
  expect(
    renderer.root.findAllByProps({ testID: 'gain-detection-help' }),
  ).toHaveLength(0);
  expect(text(renderer, 'gain-detection-granted')).toContain('granted');

  await press(renderer, 'gain-legacy-mode');
  expect(module.setGainLegacyMode).toHaveBeenCalledWith(true);
  expect(text(renderer, 'gain-status')).toContain('Some phones bypass it');
  await press(renderer, 'gain-session-mode');
  expect(module.setGainLegacyMode).toHaveBeenLastCalledWith(false);
  await press(renderer, 'gain-retry');
  expect(module.getState).toHaveBeenCalledTimes(2);
});

test('does not claim attenuation when the detected session has no enabled effect', async () => {
  const module = mockModule();
  module.getState = resolved({ gainDb: -20, gainSessionCount: 1 });
  const renderer = await render();
  expect(text(renderer, 'gain-status')).toContain('Attenuation is not active');
  expect(text(renderer, 'gain-status')).not.toContain('10%');

  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit(STATE_EVENT, {
      ...baseState,
      gainDb: -20,
      gainSessionCount: 1,
      gainError:
        'Android could not configure attenuation for this audio session.',
    });
  });
  expect(text(renderer, 'gain-status')).toContain(
    'Android could not configure attenuation',
  );
  expect(text(renderer, 'gain-status')).not.toContain('10%');
});

test('distinguishes missing permission from granted permission with no playback session', async () => {
  mockModule().getState = resolved({ gainDb: -20 });
  const renderer = await render();
  expect(text(renderer, 'gain-status')).toContain(
    'complete the audio detection setup',
  );

  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit(STATE_EVENT, {
      ...baseState,
      gainDb: -20,
      gainDetectionGranted: true,
    });
  });

  expect(text(renderer, 'gain-status')).toContain('permission is granted');
  expect(text(renderer, 'gain-status')).toContain('Start Audible playback');
  expect(text(renderer, 'gain-status')).not.toContain('setup below');
  expect(
    renderer.root.findAllByProps({ testID: 'gain-detection-help' }),
  ).toHaveLength(0);
  expect(text(renderer, 'gain-detection-granted')).toContain('granted');
});

test('warns when another equalizer owns the effect', async () => {
  const module = mockModule();
  module.getState = resolved({ gainDb: -20, gainOverridden: true });
  const renderer = await render();
  expect(text(renderer, 'gain-status')).toContain('Another app');
  expect(text(renderer, 'gain-status')).not.toContain('10%');
});

test('shows the button log, live entries and key filter controls', async () => {
  const module = mockModule();
  const renderer = await render();
  const entries = () =>
    renderer.root
      .findAllByProps({ testID: 'log-entry' })
      .filter(node => typeof node.type === 'string')
      .map(node => node.props.children);
  expect(entries()).toEqual(['12:00:00.000  Logging started']);

  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit(BUTTON_LOG_EVENT, '12:00:01.000  KEY volume up');
  });
  expect(entries()[0]).toBe('12:00:01.000  KEY volume up');
  expect(text(renderer, 'key-filter-status')).toContain('Key filter: off');

  await press(renderer, 'open-key-filter');
  expect(module.openAccessibilitySettings).toHaveBeenCalled();
  await press(renderer, 'clear-log');
  expect(module.clearButtonLog).toHaveBeenCalled();
  expect(entries()).toHaveLength(0);

  await ReactTestRenderer.act(async () => {
    DeviceEventEmitter.emit(STATE_EVENT, {
      ...baseState,
      keyFilterEnabled: true,
    });
  });
  expect(text(renderer, 'key-filter-status')).toContain('Key filter: on');
  expect(
    renderer.root.findAllByProps({ testID: 'open-key-filter' }),
  ).toHaveLength(0);
});

test('toggles headset fine steps', async () => {
  const module = mockModule();
  const renderer = await render();
  expect(text(renderer, 'headset-steps-status')).toContain('0.5 dB');
  expect(text(renderer, 'headset-steps-status')).toContain('key filter');

  await press(renderer, 'headset-steps-off');
  expect(module.setHeadsetSteps).toHaveBeenCalledWith(false);
  expect(text(renderer, 'headset-steps-status')).toContain('as usual');

  await press(renderer, 'headset-steps-on');
  expect(module.setHeadsetSteps).toHaveBeenLastCalledWith(true);
});
