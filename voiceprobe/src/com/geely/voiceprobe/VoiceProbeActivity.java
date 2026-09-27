package com.geely.voiceprobe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;

public final class VoiceProbeActivity extends Activity {
    private static final int AUDIO_PERMISSION = 41;
    private static final String STEERING_VOICE_ACTION =
        "ecarx.intent.action.ECARX_KEY_RVOICEASSIST_EVENT";
    private TextView status;
    private BroadcastReceiver steeringReceiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);
        status = new TextView(this);
        status.setTextSize(22f);
        status.setText("Park the car before probing. Speak from the driver's seat during each capture.\n"
            + "Files: " + path());
        root.addView(status);
        root.addView(button("Record microphone matrix", view -> startAudioProbe()));
        root.addView(button("Stop recording", view -> stopService(
            new Intent(this, AudioProbeService.class).setAction(AudioProbeService.ACTION_STOP))));
        root.addView(button("90s Siri / phone contention test", view -> startContentionProbe()));
        root.addView(button("Probe HVAC binder permissions", view -> {
            BinderProbe.run(this);
            status.setText("Binder probe written to probe.log");
        }));
        root.addView(button("Mark Siri / call event", view -> {
            ProbeLog.write(this, "MANUAL MARKER: Siri/call test action");
            status.setText("Marker written. Start Siri or a call while capture is active.");
        }));
        setContentView(root);

        steeringReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                StringBuilder details = new StringBuilder("Steering voice broadcast received");
                if (intent.getExtras() != null) {
                    for (String key : intent.getExtras().keySet()) {
                        details.append(" ").append(key).append("=")
                            .append(String.valueOf(intent.getExtras().get(key)));
                    }
                }
                ProbeLog.write(context, details.toString());
                status.setText(details.toString());
            }
        };
        IntentFilter steeringFilter = new IntentFilter(STEERING_VOICE_ACTION);
        steeringFilter.addCategory(Intent.CATEGORY_DEFAULT);
        registerReceiver(steeringReceiver, steeringFilter);
    }

    @Override protected void onDestroy() {
        if (steeringReceiver != null) unregisterReceiver(steeringReceiver);
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        ProbeLog.write(this, "key action=" + event.getAction() + " code=" + event.getKeyCode()
            + " scan=" + event.getScanCode() + " device=" + event.getDeviceId());
        status.setText("Key captured: code=" + event.getKeyCode() + " scan=" + event.getScanCode());
        return super.dispatchKeyEvent(event);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_PERMISSION && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startAudioProbe();
        } else if (requestCode == AUDIO_PERMISSION) {
            status.setText("RECORD_AUDIO denied; the microphone probe cannot run.");
        }
    }

    private void startAudioProbe() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE}, AUDIO_PERMISSION);
            return;
        }
        startForegroundService(new Intent(this, AudioProbeService.class));
        status.setText("Recording 10 seconds per built-in microphone. Watch probe.log for routing and RMS.");
    }

    private void startContentionProbe() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_PHONE_STATE}, AUDIO_PERMISSION);
            return;
        }
        Intent intent = new Intent(this, AudioProbeService.class)
            .setAction(AudioProbeService.ACTION_CONTENTION);
        startForegroundService(intent);
        status.setText("90-second capture active. Invoke and release Siri or a phone call now.");
    }

    private Button button(String label, android.view.View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(20f);
        button.setOnClickListener(listener);
        return button;
    }

    private String path() {
        File dir = getExternalFilesDir(null);
        return dir == null ? "external files unavailable" : dir.getAbsolutePath();
    }
}
