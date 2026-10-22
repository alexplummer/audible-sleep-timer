package com.sleeptimer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.os.Looper;
import android.os.Parcel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioEffect;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, application = Application.class,
        shadows = {GainControllerTest.ControlledAudioEffect.class,
                GainControllerTest.ControlledDynamicsProcessing.class})
public class GainControllerTest {

    @Implements(AudioEffect.class)
    public static class ControlledAudioEffect extends ShadowAudioEffect {
        static boolean control;
        static boolean failEnable;

        @Implementation(minSdk = 35)
        protected int native_setup(Object reference, String type, String uuid, int priority,
                int session, int deviceType, String deviceAddress, int[] id, Object[] descriptor,
                Parcel attribution, boolean probe) {
            return super.native_setup(reference, type, uuid, priority, session, id, descriptor,
                    "com.sleeptimer");
        }

        @Override
        protected Optional<ByteBuffer> getDefaultParameter(ByteBuffer parameter) {
            // The native DynamicsProcessing engine reports stereo channels before configuration.
            if (parameter.equals(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(0, 16))) {
                return Optional.of(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(0, 2));
            }
            return super.getDefaultParameter(parameter);
        }

        @Implementation
        protected boolean native_hasControl() {
            return control;
        }

        @Override
        @Implementation
        protected int native_setEnabled(boolean enabled) {
            return enabled && failEnable ? AudioEffect.ERROR_INVALID_OPERATION : super.native_setEnabled(enabled);
        }
    }

    @Implements(DynamicsProcessing.class)
    public static class ControlledDynamicsProcessing extends ControlledAudioEffect {}

    private Application app;
    private GainController gain;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(GainController.class, "instance", null);
        ReflectionHelpers.setStaticField(SleepTimer.class, "instance", null);
        ReflectionHelpers.setStaticField(AudibleController.class, "instance", null);
        ReflectionHelpers.setStaticField(AppShutdown.class, "closing", false);
        ReflectionHelpers.setStaticField(AppShutdown.class, "closed", false);
        ControlledAudioEffect.control = true;
        ControlledAudioEffect.failEnable = false;
        gain = GainController.get(app);
        gain.activate();
    }

    @After
    public void tearDown() {
        gain.release();
        SleepTimer.get(app).shutdown();
        AudibleController.get(app).shutdown();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    @Config(sdk = {24, 26})
    public void olderAndroidVersionsRemainUsableWithoutLoadingUnsupportedAudioApis() {
        gain.setGainDb(-20);
        gain.setLegacyMode(true);
        assertFalse(GainController.isSupported());
        assertFalse(gain.isApplied());
        assertFalse(gain.hasDetectionPermission());
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty());
    }

    @Test
    public void gainWithoutAnAudibleSessionNeverClaimsItIsApplied() {
        gain.setGainDb(-20);
        assertFalse(gain.isLegacyMode());
        assertFalse(gain.isApplied());
        assertEquals(0, gain.getSessionCount());
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty());
    }

    @Test
    public void capturesSessionBeforeSliderMovesAndSetsBothChannelsWithoutChangingVolume() {
        AudioManager audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0);
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 123);

        gain.setGainDb(-20);

        DynamicsProcessing effect = effect();
        assertEquals(123, shadowOf((AudioEffect) effect).getAudioSession());
        assertEquals(-20, effect.getInputGainByChannelIndex(0), 0.001f);
        assertEquals(-20, effect.getInputGainByChannelIndex(1), 0.001f);
        assertTrue(effect.getEnabled());
        assertTrue(gain.isApplied());
        assertEquals(1, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        assertEquals(1, gain.getSessionCount());
    }

    @Test
    public void ignoresOtherPlayersAndInvalidSessionIdentifiers() {
        gain.setGainDb(-20);
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, "other.player", 123);
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 0);
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, -1);
        assertEquals(0, gain.getSessionCount());
        assertFalse(gain.isApplied());
    }

    @Test
    public void closesOldSessionAndAttachesReplacementWithoutDoubleAttenuation() {
        gain.setGainDb(-20);
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 123);
        broadcast(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 123);
        assertFalse(gain.isApplied());
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty());

        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 456);
        assertEquals(1, ShadowAudioEffect.getAudioEffects().size());
        assertEquals(456, shadowOf((AudioEffect) effect()).getAudioSession());
        assertTrue(gain.isApplied());
    }

    @Test
    public void legacyIsExplicitAndSwitchingModesReleasesGlobalEffect() {
        gain.setGainDb(-20);
        gain.setLegacyMode(true);
        assertEquals(0, shadowOf((AudioEffect) effect()).getAudioSession());
        assertTrue(gain.isLegacyMode());

        gain.setLegacyMode(false);
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty());
        assertFalse(gain.isApplied());
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 123);
        assertEquals(123, shadowOf((AudioEffect) effect()).getAudioSession());
    }

    @Test
    public void reportsControlLossAndEnableFailureInsteadOfReportingSuccess() {
        gain.setGainDb(-20);
        ControlledAudioEffect.control = false;
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 123);
        assertTrue(gain.isOverridden());
        assertFalse(gain.isApplied());

        ControlledAudioEffect.control = true;
        ControlledAudioEffect.failEnable = true;
        gain.refresh();
        assertFalse(gain.isApplied());
        assertNotNull(gain.getError());
    }

    @Test
    public void zeroGainAndShutdownReleaseEffectsAndCannotReactivateFromBroadcasts() {
        gain.setGainDb(-40);
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 123);
        assertTrue(gain.isApplied());
        gain.setGainDb(0);
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty());
        assertFalse(gain.isApplied());
        gain.setGainDb(-20);
        assertTrue(gain.isApplied());

        gain.release();
        broadcast(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                AudibleController.AUDIBLE_PACKAGE, 456);
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty());
        assertFalse(gain.isApplied());
        assertEquals(0, gain.getSessionCount());
    }

    private DynamicsProcessing effect() {
        assertEquals("Gain configuration logs: " + ShadowLog.getLogsForTag("GainController"),
                1, ShadowAudioEffect.getAudioEffects().size());
        return (DynamicsProcessing) ShadowAudioEffect.getAudioEffects().get(0);
    }

    private void broadcast(String action, String player, int session) {
        app.sendBroadcast(new Intent(action)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, player)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session));
        shadowOf(Looper.getMainLooper()).idle();
    }
}
