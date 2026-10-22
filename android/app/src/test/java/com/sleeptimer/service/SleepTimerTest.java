package com.sleeptimer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPowerManager;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 35}, application = Application.class)
public class SleepTimerTest {

    private Application app;
    private SleepTimer timer;
    private ServiceController<ForegroundService> service;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        ReflectionHelpers.setStaticField(SleepTimer.class, "instance", null);
        ReflectionHelpers.setStaticField(AudibleController.class, "instance", null);
        ReflectionHelpers.setStaticField(GainController.class, "instance", null);
        ReflectionHelpers.setStaticField(ForegroundService.class, "instance", null);
        ReflectionHelpers.setStaticField(AppShutdown.class, "closing", false);
        ReflectionHelpers.setStaticField(AppShutdown.class, "closed", false);
        AppShutdown.openApp(app);
        timer = SleepTimer.get(app);
    }

    @After
    public void tearDown() {
        if (service != null) service.destroy();
        timer.shutdown();
        AudibleController.get(app).shutdown();
        GainController.get(app).release();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }

    @Test
    public void timerExpiresAfterTwoHoursAndReleasesBackgroundResources() {
        timer.setDuration(120);
        timer.onAudiblePlayingChanged(true);
        assertEquals(SleepTimer.State.RUNNING, timer.getState());
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(120));

        assertEquals(SleepTimer.State.IDLE, timer.getState());
        assertNoBackgroundResources();
    }

    @Test
    public void backupAlarmHonoursElapsedTimeDuringDeepSleep() {
        timer.setDuration(15);
        timer.onAudiblePlayingChanged(true);
        ShadowSystemClock.simulateDeepSleep(Duration.ofMinutes(16));

        timer.onAlarm();

        assertEquals(SleepTimer.State.IDLE, timer.getState());
        assertNoBackgroundResources();
    }

    @Test
    public void observingAlreadyPlayingAudibleStartsTimerAndTracksHeadphonePlayback() {
        ComponentName listener = new ComponentName(app, AudibleListenerService.class);
        Settings.Secure.putString(app.getContentResolver(),
                "enabled_notification_listeners", listener.flattenToString());
        MediaSession session = new MediaSession(app, "audible-test");
        try {
            MediaController controller = session.getController();
            shadowOf(controller).setPackageName(AudibleController.AUDIBLE_PACKAGE);
            shadowOf(controller).setPlaybackState(playbackState(PlaybackState.STATE_PLAYING));
            MediaSessionManager sessions = (MediaSessionManager)
                    app.getSystemService(Context.MEDIA_SESSION_SERVICE);
            shadowOf(sessions).addController(controller);

            AudibleController.get(app).startObserving();
            assertEquals(SleepTimer.State.RUNNING, timer.getState());

            shadowOf(controller).executeOnPlaybackStateChanged(
                    playbackState(PlaybackState.STATE_PAUSED));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(SleepTimer.State.PAUSED, timer.getState());
            assertNoBackgroundResources();

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(1));
            shadowOf(controller).executeOnPlaybackStateChanged(
                    playbackState(PlaybackState.STATE_PLAYING));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(SleepTimer.State.RUNNING, timer.getState());
            assertEquals(15 * 60_000L, timer.getRemainingMs());
        } finally {
            session.release();
        }
    }

    @Test
    public void briefPlaybackPauseResumesRemainingTimeButLongPauseRestartsTimer() {
        timer.setDuration(15);
        timer.onAudiblePlayingChanged(true);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(2));
        timer.onAudiblePlayingChanged(false);
        assertEquals(SleepTimer.State.PAUSED, timer.getState());
        assertEquals(13 * 60_000L, timer.getRemainingMs());
        assertNoBackgroundResources();

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1));
        timer.onAudiblePlayingChanged(true);
        assertEquals(13 * 60_000L, timer.getRemainingMs());
        timer.onAudiblePlayingChanged(false);

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6));
        timer.onAudiblePlayingChanged(true);
        assertEquals(15 * 60_000L, timer.getRemainingMs());
    }

    @Test
    public void durationChangesResetBothRunningAndPausedCountdowns() {
        timer.onAudiblePlayingChanged(true);
        timer.setDuration(45);
        assertEquals(45 * 60_000L, timer.getRemainingMs());

        timer.onAudiblePlayingChanged(false);
        timer.setDuration(15);
        assertEquals(SleepTimer.State.PAUSED, timer.getState());
        assertEquals(15 * 60_000L, timer.getRemainingMs());
        assertNoBackgroundResources();
    }

    @Test
    public void notificationPresetsReachNativeTimerWithoutReact() throws Exception {
        startService();
        timer.onAudiblePlayingChanged(true);
        Notification notification = notification();
        assertEquals(3, notification.actions.length);
        assertEquals("15 min", notification.actions[1].title.toString());
        assertEquals("45 min", notification.actions[2].title.toString());
        assertEquals(15, shadowOf(notification.actions[1].actionIntent)
                .getSavedIntent().getIntExtra(PresetTimerReceiver.EXTRA_MINUTES, 0));
        assertEquals(45, shadowOf(notification.actions[2].actionIntent)
                .getSavedIntent().getIntExtra(PresetTimerReceiver.EXTRA_MINUTES, 0));

        notification.actions[2].actionIntent.send();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(45, timer.getDurationMinutes());
        assertEquals(45 * 60_000L, timer.getRemainingMs());
        notification().actions[1].actionIntent.send();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(15, timer.getDurationMinutes());
        assertEquals(15 * 60_000L, timer.getRemainingMs());
    }

    @Test
    public void notificationCloseCleansUpAndStaysClosedAfterProcessRestart() throws Exception {
        startService();
        timer.onAudiblePlayingChanged(true);

        notification().actions[0].actionIntent.send();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(AppShutdown.isClosing());
        assertEquals(SleepTimer.State.IDLE, timer.getState());
        assertTrue(shadowOf(service.get()).isStoppedBySelf());
        assertNull(notification());
        assertNoBackgroundResources();

        // Simulate losing in-memory state when the OS starts a stale component.
        ReflectionHelpers.setStaticField(AppShutdown.class, "closing", false);
        ReflectionHelpers.setStaticField(AppShutdown.class, "closed", false);
        AppShutdown.initialize(app);
        assertTrue(AppShutdown.isClosing());
        timer.onAudiblePlayingChanged(true);
        ForegroundService.start(app);
        assertEquals(SleepTimer.State.IDLE, timer.getState());
        assertNull(shadowOf(app).getNextStartedService());

        AppShutdown.openApp(app);
        assertFalse(AppShutdown.isClosing());
        timer.onAudiblePlayingChanged(true);
        assertEquals(SleepTimer.State.RUNNING, timer.getState());
    }

    @Test
    public void destroyingServiceReleasesTimerAndDoesNotRequestRestart() {
        startService();
        timer.onAudiblePlayingChanged(true);
        assertEquals(Service.START_NOT_STICKY, service.get().onStartCommand(null, 0, 1));

        service.destroy();
        service = null;

        assertFalse(ForegroundService.isRunning());
        assertEquals(SleepTimer.State.IDLE, timer.getState());
        assertNoBackgroundResources();
    }

    private void startService() {
        service = Robolectric.buildService(ForegroundService.class).create();
    }

    private PlaybackState playbackState(int state) {
        return new PlaybackState.Builder().setState(state, 0, 1).build();
    }

    private Notification notification() {
        NotificationManager manager = (NotificationManager)
                app.getSystemService(Context.NOTIFICATION_SERVICE);
        return shadowOf(manager).getNotification(ForegroundService.NOTIFICATION_ID);
    }

    private void assertNoBackgroundResources() {
        PowerManager.WakeLock lock = ShadowPowerManager.getLatestWakeLock();
        if (lock != null) assertFalse(lock.isHeld());
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        assertTrue(shadowOf(alarms).getScheduledAlarms().isEmpty());
    }
}
