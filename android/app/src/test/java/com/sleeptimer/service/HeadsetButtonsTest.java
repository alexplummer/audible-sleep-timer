package com.sleeptimer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class HeadsetButtonsTest {

    private static final class FakeEnv implements HeadsetButtons.Env {
        int volume = 10;
        int max = 25;
        boolean bluetooth = true;
        boolean playing = true;
        boolean context = true;
        float gain = -20f;
        int spoken;
        long now = 10_000L;
        final List<String> logs = new ArrayList<>();

        @Override public int getVolume() { return volume; }
        @Override public int getMaxVolume() { return max; }
        @Override public void setVolume(int v) { volume = v; }
        @Override public boolean isBluetoothOutput() { return bluetooth; }
        @Override public boolean isAudiblePlaying() { return playing; }
        @Override public boolean isRemapContext() { return context; }
        @Override public float getGainDb() { return gain; }
        @Override public void setGainDb(float db) { gain = db; }
        @Override public void speakTime() { spoken++; }
        @Override public void log(String message) { logs.add(message); }
        @Override public long now() { return now; }
    }

    private FakeEnv env;
    private HeadsetButtons buttons;

    @Before
    public void setUp() {
        env = new FakeEnv();
        buttons = new HeadsetButtons(env);
        buttons.resetBase();
    }

    /** Simulates the headset changing the media volume, followed by the resulting broadcast(s). */
    private void headset(int delta) {
        env.volume += delta;
        buttons.onVolumeChanged(env.volume);
        buttons.onVolumeChanged(env.volume);
        env.now += 1_000L;
    }

    @Test
    public void headsetPressesMoveGainAndRestoreSystemVolume() {
        headset(-1);
        assertEquals(-20.5f, env.gain, 0.001f);
        assertEquals(10, env.volume);
        headset(1);
        headset(1);
        assertEquals(-19.5f, env.gain, 0.001f);
        assertEquals(10, env.volume);
        assertEquals(0, env.spoken);
    }

    @Test
    public void doubleUpSpeaksTimeAndUndoesFirstStep() {
        env.volume = 11;
        buttons.onVolumeChanged(11);
        env.volume = 10;
        buttons.onVolumeChanged(10);
        assertEquals(-19.5f, env.gain, 0.001f);
        env.now += HeadsetButtons.DOUBLE_TAP_MS - 100;
        env.volume = 11;
        buttons.onVolumeChanged(11);
        assertEquals(1, env.spoken);
        assertEquals(-20f, env.gain, 0.001f);
        assertEquals(10, env.volume);
    }

    @Test
    public void phoneKeysAndOtherOutputsPassThrough() {
        buttons.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP));
        headset(1);
        assertEquals(11, env.volume);
        assertEquals(-20f, env.gain, 0.001f);

        env.now += HeadsetButtons.PHONE_KEY_WINDOW_MS;
        env.bluetooth = false;
        headset(-1);
        env.bluetooth = true;
        env.playing = false;
        headset(-1);
        env.playing = true;
        env.context = false;
        headset(-1);
        assertEquals(8, env.volume);
        assertEquals(-20f, env.gain, 0.001f);
        assertTrue(env.logs.get(env.logs.size() - 1).contains("app in background"));
    }

    @Test
    public void gainLimitsHandOverToSystemVolume() {
        env.gain = 0f;
        headset(1);
        assertEquals(11, env.volume);
        assertEquals(0f, env.gain, 0.001f);

        env.gain = -40f;
        headset(-1);
        assertEquals(10, env.volume);
        assertEquals(-40f, env.gain, 0.001f);

        env.gain = -10f;
        env.volume = 25;
        buttons.resetBase();
        headset(-1);
        assertEquals(24, env.volume);
        assertEquals(-10f, env.gain, 0.001f);
    }

    @Test
    public void disabledRemapLeavesVolumeAlone() {
        HeadsetButtons disabled = new HeadsetButtons(env);
        disabled.resetBase();
        disabled.setEnabled(false);
        env.volume = 9;
        disabled.onVolumeChanged(9);
        assertEquals(9, env.volume);
        assertEquals(-20f, env.gain, 0.001f);
    }

    @Test
    public void timeTextIs24Hour() {
        assertEquals("4:02", TimeSpeaker.timeText(4, 2));
        assertEquals("16:29", TimeSpeaker.timeText(16, 29));
    }
}
