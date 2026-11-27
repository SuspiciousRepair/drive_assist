package com.geely.voiceprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.IBinder;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Records bounded evidence files and remains alive while another app takes audio focus. */
public final class AudioProbeService extends Service {
    static final String ACTION_STOP = "com.geely.voiceprobe.STOP";
    static final String ACTION_CONTENTION = "com.geely.voiceprobe.CONTENTION";
    private static final int SAMPLE_RATE = 16000;
    private static final int RECORD_SECONDS = 10;
    private static final int NOTIFICATION_ID = 7301;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread worker;
    private BroadcastReceiver carplayReceiver;
    private PhoneStateListener phoneListener;

    @Override public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, notification());
        registerStateObservers();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running.compareAndSet(false, true)) {
            boolean contention = intent != null && ACTION_CONTENTION.equals(intent.getAction());
            worker = new Thread(contention ? this::runContention : this::runMatrix,
                "voice-probe-audio");
            worker.start();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        running.set(false);
        if (worker != null) worker.interrupt();
        unregisterStateObservers();
        ProbeLog.write(this, "audio probe stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void runMatrix() {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo[] all = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        List<AudioDeviceInfo> microphones = new ArrayList<>();
        for (AudioDeviceInfo device : all) {
            ProbeLog.write(this, "input id=" + device.getId() + " type=" + device.getType()
                + " address=" + device.getAddress() + " product=" + device.getProductName());
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) microphones.add(device);
        }
        ProbeLog.write(this, "AEC available=" + AcousticEchoCanceler.isAvailable()
            + " NS available=" + NoiseSuppressor.isAvailable());

        recordOne(MediaRecorder.AudioSource.MIC, null, "mic-default", RECORD_SECONDS);
        recordOne(MediaRecorder.AudioSource.VOICE_RECOGNITION, null,
            "voice-recognition-default", RECORD_SECONDS);
        recordOne(MediaRecorder.AudioSource.VOICE_COMMUNICATION, null,
            "voice-communication-default", RECORD_SECONDS);
        recordOne(MediaRecorder.AudioSource.CAMCORDER, null, "camcorder-default", RECORD_SECONDS);
        for (AudioDeviceInfo device : microphones) {
            recordOne(MediaRecorder.AudioSource.VOICE_RECOGNITION, device,
                "builtin-mic-" + device.getId(), RECORD_SECONDS);
        }
        ProbeLog.write(this, "capture matrix complete; service stopping");
        stopSelf();
    }

    private void runContention() {
        ProbeLog.write(this, "contention capture: invoke Siri/call now, then release it; watching 90s");
        recordOne(MediaRecorder.AudioSource.VOICE_RECOGNITION, null, "contention", 90);
        ProbeLog.write(this, "contention capture complete; service stopping");
        stopSelf();
    }

    private void recordOne(int source, AudioDeviceInfo preferred, String name, int seconds) {
        if (!running.get()) return;
        int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(minimum, SAMPLE_RATE * 2);
        AudioRecord record = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        File output = new File(getExternalFilesDir(null), name + ".wav");
        long sumSquares = 0;
        long sampleCount = 0;
        int peak = 0;
        int zeroReads = 0;
        int errorReads = 0;
        boolean captureWasLost = false;
        try (WavWriter wav = new WavWriter(output)) {
            record = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            boolean routed = preferred == null || record.setPreferredDevice(preferred);
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(record.getAudioSessionId());
                if (ns != null) ns.setEnabled(true);
            }
            ProbeLog.write(this, "capture start file=" + output.getName() + " source=" + source
                + " preferredAccepted=" + routed + " routedId=" + deviceId(record.getRoutedDevice())
                + " aec=" + enabled(aec) + " ns=" + enabled(ns));
            short[] samples = new short[bufferBytes / 2];
            record.startRecording();
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            while (running.get() && System.nanoTime() < deadline) {
                int count = record.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    if (captureWasLost) {
                        ProbeLog.write(this, "capture recovered file=" + output.getName()
                            + " after zeroReads=" + zeroReads + " errorReads=" + errorReads);
                        captureWasLost = false;
                    }
                    wav.write(samples, count);
                    sampleCount += count;
                    for (int i = 0; i < count; i++) {
                        int value = Math.abs((int) samples[i]);
                        peak = Math.max(peak, value);
                        sumSquares += (long) value * value;
                    }
                } else if (count == 0) {
                    zeroReads++;
                    captureWasLost = true;
                } else {
                    errorReads++;
                    captureWasLost = true;
                    // A call can make read() return immediately for tens of seconds. Log the
                    // transition and sparse progress, not thousands of identical lines.
                    if (errorReads == 1 || errorReads % 1000 == 0) {
                        ProbeLog.write(this, "capture read error file=" + output.getName()
                            + " code=" + count + " count=" + errorReads);
                    }
                }
            }
            double rms = sampleCount == 0 ? 0 : Math.sqrt((double) sumSquares / sampleCount);
            ProbeLog.write(this, String.format(Locale.US,
                "capture end file=%s samples=%d rms=%.1f peak=%d zeroReads=%d errorReads=%d routedId=%d",
                output.getName(), sampleCount, rms, peak, zeroReads, errorReads,
                deviceId(record.getRoutedDevice())));
        } catch (Throwable t) {
            ProbeLog.write(this, "capture failed file=" + output.getName() + " "
                + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (record != null) {
                try { record.stop(); } catch (Throwable ignored) { }
                record.release();
            }
            if (aec != null) aec.release();
            if (ns != null) ns.release();
        }
    }

    private void registerStateObservers() {
        carplayReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                ProbeLog.write(context, "CarPlay broadcast notification="
                    + intent.getStringExtra("notification"));
            }
        };
        registerReceiver(carplayReceiver, new IntentFilter("com.njda.carplay.broadcast"));
        TelephonyManager telephony = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        phoneListener = new PhoneStateListener() {
            @Override public void onCallStateChanged(int state, String number) {
                ProbeLog.write(AudioProbeService.this, "phone state=" + state);
            }
        };
        try {
            telephony.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException e) {
            ProbeLog.write(this, "phone-state permission denied: " + e.getMessage());
        }
    }

    private void unregisterStateObservers() {
        if (carplayReceiver != null) unregisterReceiver(carplayReceiver);
        TelephonyManager telephony = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        if (phoneListener != null) telephony.listen(phoneListener, PhoneStateListener.LISTEN_NONE);
    }

    private Notification notification() {
        String channelId = "voice_probe_capture";
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(channelId, "Voice probe",
            NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Voice probe recording")
            .setContentText("10-second diagnostic captures only")
            .build();
    }

    private static int deviceId(AudioDeviceInfo device) { return device == null ? -1 : device.getId(); }
    private static String enabled(android.media.audiofx.AudioEffect effect) {
        return effect == null ? "create-failed" : Boolean.toString(effect.getEnabled());
    }
}
