package com.sleeptimer.service;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class SilentAudioPlayer {
    private static final String TAG = "SilentAudioPlayer";
    private static MediaPlayer mediaPlayer;
    private static boolean isPlaying = false;

    public static void startSilentAudio(Context context) {
        try {
            if (mediaPlayer != null && isPlaying) {
                Log.d(TAG, "Silent audio already playing");
                return;
            }

            Log.d(TAG, "Starting silent audio playback");
            
            // Create a very short silent audio file
            File silentFile = createSilentAudioFile(context);
            
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(silentFile.getAbsolutePath());
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mediaPlayer.setLooping(true); // Loop to keep it playing
            
            mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    Log.d(TAG, "Silent audio prepared, starting playback");
                    mp.start();
                    isPlaying = true;
                }
            });
            
            mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    Log.e(TAG, "Silent audio error: " + what + ", " + extra);
                    isPlaying = false;
                    return true;
                }
            });
            
            mediaPlayer.prepareAsync();
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start silent audio", e);
        }
    }

    public static void stopSilentAudio() {
        try {
            if (mediaPlayer != null) {
                Log.d(TAG, "Stopping silent audio");
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
                isPlaying = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping silent audio", e);
        }
    }

    private static File createSilentAudioFile(Context context) throws IOException {
        File silentFile = new File(context.getCacheDir(), "silent.wav");
        
        if (silentFile.exists()) {
            return silentFile;
        }

        Log.d(TAG, "Creating silent audio file");
        
        // Create a minimal WAV file with silence
        FileOutputStream fos = new FileOutputStream(silentFile);
        
        // WAV header for 1 second of silence at 44100 Hz, 16-bit, mono
        int sampleRate = 44100;
        int duration = 1; // 1 second
        int numSamples = sampleRate * duration;
        int numChannels = 1;
        int bitsPerSample = 16;
        int dataSize = numSamples * numChannels * bitsPerSample / 8;
        int fileSize = dataSize + 36;

        // WAV file header
        fos.write("RIFF".getBytes());
        fos.write(intToByteArray(fileSize), 0, 4);
        fos.write("WAVE".getBytes());
        fos.write("fmt ".getBytes());
        fos.write(intToByteArray(16), 0, 4); // Subchunk1Size
        fos.write(shortToByteArray((short) 1), 0, 2); // AudioFormat (PCM)
        fos.write(shortToByteArray((short) numChannels), 0, 2);
        fos.write(intToByteArray(sampleRate), 0, 4);
        fos.write(intToByteArray(sampleRate * numChannels * bitsPerSample / 8), 0, 4); // ByteRate
        fos.write(shortToByteArray((short) (numChannels * bitsPerSample / 8)), 0, 2); // BlockAlign
        fos.write(shortToByteArray((short) bitsPerSample), 0, 2);
        fos.write("data".getBytes());
        fos.write(intToByteArray(dataSize), 0, 4);

        // Write silence (zeros)
        byte[] silence = new byte[dataSize];
        fos.write(silence);
        fos.close();

        Log.d(TAG, "Silent audio file created: " + silentFile.getAbsolutePath());
        return silentFile;
    }

    private static byte[] intToByteArray(int value) {
        return new byte[]{
            (byte) (value & 0xff),
            (byte) ((value >> 8) & 0xff),
            (byte) ((value >> 16) & 0xff),
            (byte) ((value >> 24) & 0xff)
        };
    }

    private static byte[] shortToByteArray(short value) {
        return new byte[]{
            (byte) (value & 0xff),
            (byte) ((value >> 8) & 0xff)
        };
    }

    public static boolean isPlaying() {
        return isPlaying && mediaPlayer != null && mediaPlayer.isPlaying();
    }
}