package com.sleeptimer.service;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discovers Audible's audio-session IDs, not its unrelated MediaSession token. */
@RequiresApi(Build.VERSION_CODES.P)
final class AudibleAudioSessions {

    interface Listener {
        void onSessionsChanged(Set<Integer> sessions, @Nullable String error);
    }

    private static final String TAG = "AudibleAudioSessions";
    private static final long SCAN_INTERVAL_MS = 5_000;
    private static final int MAX_DUMP_CHARS = 1_000_000;
    private static final Pattern OUTPUTS_HEADER = Pattern.compile("Outputs(?: dump:| \\(\\d+\\):)");
    private static final Pattern INPUTS_HEADER = Pattern.compile("Inputs(?: dump:| \\(\\d+\\):)");
    private static final Pattern POLICY_SESSION = Pattern.compile(
            "\\bSession\\s+Id:\\s*(\\d+)\\s*;?\\s*UID:?\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final Set<Integer> broadcastSessions = new LinkedHashSet<>();
    private Set<Integer> detectedSessions = Collections.emptySet();
    private ExecutorService worker;
    @Nullable private volatile Process scanProcess;
    @Nullable private String scanError;
    private boolean active;
    private boolean detecting;
    private boolean scanning;
    private int generation;

    private final Runnable scanAgain = this::refresh;
    private final AudioManager.AudioPlaybackCallback playbackCallback =
            new AudioManager.AudioPlaybackCallback() {
                @Override
                public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                    refresh();
                }
            };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            if (!active || !AudibleController.AUDIBLE_PACKAGE.equals(
                    intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))) return;
            int session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0);
            if (session <= 0) return;
            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
                broadcastSessions.add(session);
            } else if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
                broadcastSessions.remove(session);
                detectedSessions = new HashSet<>(detectedSessions);
                detectedSessions.remove(session);
            } else {
                return;
            }
            publish();
            refresh();
        }
    };

    AudibleAudioSessions(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    static boolean hasDetectionPermission(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP)
                == PackageManager.PERMISSION_GRANTED;
    }

    void start(boolean detectSessions) {
        detecting = detectSessions;
        if (active) {
            refresh();
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        // Player apps send these broadcasts; they are intentionally received across UIDs.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED);
        active = true;
        if (audio != null) audio.registerAudioPlaybackCallback(playbackCallback, main);
        refresh();
    }

    void stop() {
        if (!active) return;
        active = false;
        generation++;
        main.removeCallbacks(scanAgain);
        context.unregisterReceiver(receiver);
        if (audio != null) audio.unregisterAudioPlaybackCallback(playbackCallback);
        Process process = scanProcess;
        if (process != null) process.destroy();
        if (worker != null) worker.shutdownNow();
        worker = null;
        scanning = false;
        broadcastSessions.clear();
        detectedSessions = Collections.emptySet();
        scanError = null;
    }

    void refresh() {
        if (!active) return;
        main.removeCallbacks(scanAgain);
        if (!detecting || !hasDetectionPermission(context)) {
            detectedSessions = Collections.emptySet();
            scanError = null;
            publish();
            return;
        }
        if (scanning) return;
        final int uid;
        try {
            uid = context.getPackageManager().getApplicationInfo(
                    AudibleController.AUDIBLE_PACKAGE, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            scanError = "Audible is not installed.";
            publish();
            return;
        }
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        scanning = true;
        int request = generation;
        worker.execute(() -> {
            Set<Integer> sessions = Collections.emptySet();
            String error = null;
            try {
                sessions = readSessions(uid);
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                Log.w(TAG, "Audio-session detection failed", e);
                error = "Audio-session detection failed. Try Legacy mode or check device support.";
            }
            Set<Integer> result = sessions;
            String failure = error;
            main.post(() -> {
                if (!active || request != generation) return;
                scanning = false;
                if (!detecting || !hasDetectionPermission(context)) return;
                detectedSessions = result;
                scanError = failure;
                publish();
                if (audio != null && audio.isMusicActive()) {
                    main.postDelayed(scanAgain, SCAN_INTERVAL_MS);
                }
            });
        });
    }

    private Set<Integer> readSessions(int uid) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("/system/bin/dumpsys", "-t", "3", "media.audio_policy")
                .redirectErrorStream(true).start();
        scanProcess = process;
        Runnable timeout = process::destroy;
        main.postDelayed(timeout, 5_000);
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    if (output.length() + count > MAX_DUMP_CHARS) {
                        throw new IOException("Audio-policy dump exceeded size limit");
                    }
                    output.append(buffer, 0, count);
                }
            }
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Audio-policy dump did not complete");
            }
            return parseSessions(output.toString(), uid);
        } finally {
            main.removeCallbacks(timeout);
            process.destroy();
            if (scanProcess == process) scanProcess = null;
        }
    }

    static Set<Integer> parseSessions(String dump, int audibleUid) throws IOException {
        Set<Integer> sessions = new LinkedHashSet<>();
        boolean outputs = false;
        boolean sawOutputs = false;
        try (BufferedReader reader = new BufferedReader(new StringReader(dump))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (OUTPUTS_HEADER.matcher(trimmed).matches()) {
                    outputs = true;
                    sawOutputs = true;
                } else if (INPUTS_HEADER.matcher(trimmed).matches()) {
                    outputs = false;
                }
                if (!outputs) continue;
                Matcher match = POLICY_SESSION.matcher(line);
                if (match.find()) {
                    try {
                        int session = Integer.parseInt(match.group(1));
                        int uid = Integer.parseInt(match.group(2));
                        if (session > 0 && uid == audibleUid) sessions.add(session);
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid audio-session identifier", e);
                    }
                }
            }
        }
        if (!sawOutputs) throw new IOException("Unsupported audio-policy dump or permission denied");
        return sessions;
    }

    private void publish() {
        Set<Integer> all = new LinkedHashSet<>(broadcastSessions);
        all.addAll(detectedSessions);
        listener.onSessionsChanged(all, scanError);
    }
}
