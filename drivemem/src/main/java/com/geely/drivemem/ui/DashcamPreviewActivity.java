package com.geely.drivemem.ui;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.DashcamSettings;
import com.geely.drivemem.util.Style;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Normal-UID preview of the helper's existing recording stream. Owns no EVS camera. */
public final class DashcamPreviewActivity extends LocalizedActivity
        implements TextureView.SurfaceTextureListener {
    private static final String HELPER = "com.geely.modehelper";
    private static final String PREVIEW_ACTION = HELPER + ".DASHCAM_PREVIEW";
    private static final long HEARTBEAT_MS = 3_000;
    private static final long RELEASE_TIMEOUT_MS = 10_000;
    private static final long FRAME_FRESH_MS = 4_500;
    private static final long COMMAND_TIMEOUT_MS = 8_000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Map<SurfaceTexture, TextureLease> textures = new IdentityHashMap<>();
    private TextureView textureView;
    private TextView previewStatus;
    private TextView placeholder;
    private TextView recordingStatus;
    private Button recordButton;
    private PendingIntent owner;
    private PreviewSession current;
    private boolean resumed;
    private boolean destroyed;
    private boolean helperKnown;
    private boolean helperAvailable;
    private boolean recordingKnown;
    private boolean recordingObserved;
    private boolean pollInFlight;
    private int lifecycle;
    private int shownState;
    private long lastFrameAt;
    private long commandAt;
    private Boolean commandTarget;
    private Boolean requestedOn;
    private Boolean helperRunning;
    private Boolean shownStopButton;

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) {
                return;
            }
            if (current != null) {
                sendPreview(current, true);
            }
            updateUi();
            ui.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed || destroyed) {
                return;
            }
            pollRecorder();
            ui.postDelayed(this, HEARTBEAT_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Style.load(this);
        Style.edgeToEdge(this);
        owner = PendingIntent.getActivity(this, 0, new Intent(this, DashcamPreviewActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Style.BG_TOP);
        int pad = dp(24);
        root.setPadding(pad, pad + Style.statusBarHeight(this), pad, pad);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button(getString(R.string.clips_back), this::finish);
        LinearLayout.LayoutParams backSize = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        backSize.rightMargin = dp(24);
        heading.addView(back, backSize);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = Style.label(this, getString(R.string.dash_preview_title));
        title.setTextSize(32);
        titles.addView(title);
        previewStatus = Style.label(this, getString(R.string.dash_preview_waiting));
        previewStatus.setTextColor(Style.TEXT_DIM);
        titles.addView(previewStatus);
        heading.addView(titles, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(heading);

        FrameLayout preview = new FrameLayout(this);
        preview.setBackgroundColor(Color.BLACK);
        textureView = new TextureView(this);
        textureView.setOpaque(false);
        textureView.setSurfaceTextureListener(this);
        preview.addView(textureView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        placeholder = Style.label(this, getString(R.string.dash_preview_waiting));
        placeholder.setTextSize(26);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setPadding(dp(40), dp(24), dp(40), dp(24));
        placeholder.setBackground(Style.card(Style.CARD, this));
        preview.addView(placeholder, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams previewSize = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        previewSize.topMargin = dp(20);
        previewSize.bottomMargin = dp(20);
        root.addView(preview, previewSize);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        recordButton = button(getString(R.string.dash_preview_start), this::toggleRecording);
        recordButton.setEnabled(false);
        LinearLayout.LayoutParams recordSize = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recordSize.rightMargin = dp(20);
        controls.addView(recordButton, recordSize);
        recordingStatus = Style.label(this, getString(R.string.dash_preview_waiting));
        recordingStatus.setTextColor(Style.TEXT_DIM);
        controls.addView(recordingStatus, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(controls);
        TextView instruction = Style.label(this, getString(R.string.dash_preview_background));
        instruction.setTextColor(Style.TEXT_DIM);
        instruction.setPadding(0, dp(16), 0, 0);
        root.addView(instruction);
        setContentView(root);
    }

    private int dp(int value) {
        return Style.dp(this, value);
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(22);
        button.setTypeface(Style.font(this));
        button.setTextColor(Style.TEXT);
        button.setAllCaps(false);
        button.setMinHeight(dp(64));
        button.setPadding(dp(24), dp(12), dp(24), dp(12));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(
            Style.blend(Style.CARD_HI, Style.TEXT, .18f)), Style.card(Style.CARD_HI, this), null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        lifecycle++;
        attachAvailableTexture();
        ui.removeCallbacks(heartbeat);
        ui.removeCallbacks(poll);
        ui.postDelayed(heartbeat, HEARTBEAT_MS);
        ui.post(poll);
        updateUi();
    }

    @Override protected void onPause() {
        resumed = false;
        lifecycle++;
        ui.removeCallbacks(heartbeat);
        ui.removeCallbacks(poll);
        retireCurrent();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        resumed = false;
        ui.removeCallbacks(heartbeat);
        ui.removeCallbacks(poll);
        retireCurrent();
        files.shutdownNow();
        // Do not cancel pending release callbacks: a detached Activity can still
        // own textures until the helper acknowledges, or its lease expires.
        super.onDestroy();
    }

    private void pollRecorder() {
        if (pollInFlight || files.isShutdown()) {
            return;
        }
        pollInFlight = true;
        int request = lifecycle;
        long startedAt = SystemClock.elapsedRealtime();
        files.execute(() -> {
            boolean installed;
            try {
                getPackageManager().getPackageInfo(HELPER, 0);
                installed = true;
            } catch (PackageManager.NameNotFoundException | RuntimeException absent) {
                installed = false;
            }
            Boolean observed = null;
            try {
                observed = Clips.recording(getApplicationContext());
            } catch (RuntimeException unavailable) {
                // An unplugged volume is not evidence that the recorder stopped.
            }
            final boolean hasHelper = installed;
            final Boolean recording = observed;
            ui.post(() -> {
                pollInFlight = false;
                if (destroyed || !resumed || request != lifecycle) {
                    return;
                }
                helperKnown = true;
                helperAvailable = hasHelper;
                recordingKnown = recording != null;
                if (recording != null) {
                    recordingObserved = recording;
                }
                if (commandTarget != null && startedAt >= commandAt
                        && SystemClock.elapsedRealtime() - commandAt >= 1_500
                        && recording != null && commandTarget.equals(recording)) {
                    commandTarget = null;
                }
                if (helperAvailable) {
                    attachAvailableTexture();
                } else {
                    commandTarget = null;
                    requestedOn = null;
                    helperRunning = null;
                    retireCurrent();
                }
                updateUi();
            });
        });
    }

    private void toggleRecording() {
        if (!resumed || !helperAvailable || !recordingKnown
                || (commandTarget != null && SystemClock.elapsedRealtime() - commandAt < 1_500)) {
            return;
        }
        commandTarget = !wantsRecording();
        requestedOn = commandTarget;
        commandAt = SystemClock.elapsedRealtime();
        sendBroadcast(DashcamSettings.configurationIntent(this).putExtra("on", commandTarget ? 1 : 0));
        ui.removeCallbacks(poll);
        ui.postDelayed(poll, 1_500);
        updateUi();
    }

    private boolean wantsRecording() {
        if (helperKnown && !helperAvailable) {
            return false;
        }
        // Requested/running is only a command affordance. It must not turn the
        // frame status or recording evidence into an optimistic success claim.
        if (requestedOn != null) {
            return requestedOn;
        }
        return helperRunning != null ? helperRunning : recordingObserved;
    }

    private void updateUi() {
        if (destroyed || previewStatus == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (commandTarget != null && now - commandAt >= COMMAND_TIMEOUT_MS) {
            commandTarget = null;
        }
        int state = R.string.dash_preview_waiting;
        if (helperKnown && !helperAvailable) {
            state = R.string.dash_preview_unavailable;
        } else if (commandTarget != null) {
            state = commandTarget ? R.string.dash_preview_starting : R.string.dash_preview_stopping;
        } else if (current != null && "error".equals(current.state)) {
            state = R.string.dash_preview_error;
        } else if (current != null && "live".equals(current.state)
                && lastFrameAt >= current.attachedAt && now - lastFrameAt <= FRAME_FRESH_MS) {
            state = R.string.dash_preview_live;
        } else if (current != null && now - (current.lastStatusAt == 0
                ? current.attachedAt : current.lastStatusAt) >= RELEASE_TIMEOUT_MS) {
            state = R.string.dash_preview_error;
        } else if (!wantsRecording() && ((current != null && "stopped".equals(current.state))
                || (recordingKnown && !recordingObserved))) {
            state = R.string.dash_preview_idle;
        }
        if (shownState != state) {
            shownState = state;
            previewStatus.setText(state);
            previewStatus.setTextColor(state == R.string.dash_preview_live ? Style.GOOD : Style.TEXT_DIM);
            placeholder.setText(state);
        }
        placeholder.setVisibility(state == R.string.dash_preview_live ? View.GONE : View.VISIBLE);
        boolean stopButton = wantsRecording();
        if (shownStopButton == null || shownStopButton != stopButton) {
            shownStopButton = stopButton;
            recordButton.setText(stopButton ? R.string.dash_preview_stop : R.string.dash_preview_start);
            int fill = stopButton ? 0xFFA64747 : 0xFF009B46;
            recordButton.setTextColor(Color.WHITE);
            recordButton.setTypeface(Style.font(this), android.graphics.Typeface.BOLD);
            recordButton.setBackground(new RippleDrawable(ColorStateList.valueOf(
                Style.blend(fill, Color.WHITE, .18f)), Style.card(fill, this), null));
        }
        boolean enabled = resumed && helperAvailable && recordingKnown
            && (commandTarget == null || now - commandAt >= 1_500);
        recordButton.setEnabled(enabled);
        recordButton.setAlpha(enabled ? 1 : .5f);
        boolean freshStatus = current != null && current.lastStatusAt > 0
            && now - current.lastStatusAt < RELEASE_TIMEOUT_MS;
        int recordState = commandTarget != null
            ? (commandTarget ? R.string.dash_preview_starting : R.string.dash_preview_stopping)
            : !helperAvailable && helperKnown ? R.string.dash_helper_missing
            : freshStatus && "error".equals(current.state) ? R.string.dash_preview_error
            : freshStatus && "stopped".equals(current.state) ? R.string.dash_stopped
            : !recordingKnown ? R.string.dash_preview_waiting
            : recordingObserved ? R.string.clips_recording : R.string.dash_stopped;
        recordingStatus.setText(recordState);
        recordingStatus.setTextColor(recordState == R.string.clips_recording ? Style.GOOD : Style.TEXT_DIM);
    }

    private void attachAvailableTexture() {
        if (!resumed || destroyed || !helperAvailable || current != null || !textureView.isAvailable()) {
            return;
        }
        SurfaceTexture texture = textureView.getSurfaceTexture();
        if (texture == null) {
            return;
        }
        TextureLease lease = textures.get(texture);
        if (lease == null) {
            lease = new TextureLease(texture);
            textures.put(texture, lease);
        }
        if (lease.destroyed || lease.released) {
            return;
        }
        texture.setDefaultBufferSize(1920, 800);
        lastFrameAt = 0;
        current = new PreviewSession(lease);
        sendPreview(current, true);
    }

    private void sendPreview(PreviewSession session, boolean attach) {
        Intent request = new Intent(PREVIEW_ACTION).setClassName(HELPER, HELPER + ".DashReceiver")
            .putExtra("preview_session", session.id)
            .putExtra("surface", attach ? session.surface : (Surface) null)
            .putExtra("preview_owner", owner)
            .putExtra("preview_status", session.status);
        try {
            sendBroadcast(request);
        } catch (RuntimeException unavailable) {
            if (current == session) {
                session.state = "error";
                updateUi();
            }
        }
    }

    private void retireCurrent() {
        PreviewSession session = current;
        if (session == null) {
            return;
        }
        current = null;
        lastFrameAt = 0;
        session.retiring = true;
        sendPreview(session, false);
        ui.postDelayed(session.releaseTimeout, RELEASE_TIMEOUT_MS);
    }

    private void releaseSession(PreviewSession session) {
        if (session.released) {
            return;
        }
        session.released = true;
        ui.removeCallbacks(session.releaseTimeout);
        session.surface.release();
        session.lease.references--;
        releaseTextureIfUnused(session.lease);
    }

    private void releaseTextureIfUnused(TextureLease lease) {
        if (lease.destroyed && lease.references == 0 && !lease.released) {
            lease.released = true;
            lease.texture.release();
            textures.remove(lease.texture);
        }
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (!textures.containsKey(surface)) {
            textures.put(surface, new TextureLease(surface));
        }
        fitPreview(width, height);
        attachAvailableTexture();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        surface.setDefaultBufferSize(1920, 800);
        fitPreview(width, height);
    }

    private void fitPreview(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float aspect = 1920f / 800f;
        float scaleX = 1;
        float scaleY = 1;
        if (width / (float) height > aspect) {
            scaleX = height * aspect / width;
        } else {
            scaleY = width / aspect / height;
        }
        Matrix transform = new Matrix();
        transform.setScale(scaleX, scaleY, width / 2f, height / 2f);
        textureView.setTransform(transform);
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        TextureLease lease = textures.get(surface);
        if (lease == null) {
            surface.release();
            return false;
        }
        lease.destroyed = true;
        if (current != null && current.lease == lease) {
            retireCurrent();
        }
        releaseTextureIfUnused(lease);
        return false;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        if (resumed && current != null && current.lease.texture == surface) {
            lastFrameAt = SystemClock.elapsedRealtime();
            if (commandTarget == null && "live".equals(current.state)
                    && shownState != R.string.dash_preview_live) {
                updateUi();
            }
        }
    }

    private static final class TextureLease {
        final SurfaceTexture texture;
        int references;
        boolean destroyed;
        boolean released;

        TextureLease(SurfaceTexture texture) {
            this.texture = texture;
        }
    }

    private final class PreviewSession {
        final String id = UUID.randomUUID().toString();
        final TextureLease lease;
        final Surface surface;
        final long attachedAt = SystemClock.elapsedRealtime();
        final ResultReceiver status;
        final Runnable releaseTimeout = () -> releaseSession(this);
        String state = "waiting";
        long lastStatusAt;
        boolean retiring;
        boolean released;

        PreviewSession(TextureLease lease) {
            this.lease = lease;
            lease.references++;
            surface = new Surface(lease.texture);
            ResultReceiver localStatus = new ResultReceiver(ui) {
                @Override protected void onReceiveResult(int resultCode, Bundle result) {
                    if (result == null || !id.equals(result.getString("preview_session"))) {
                        return;
                    }
                    String next = result.getString("state", "error");
                    if ("detached".equals(next)) {
                        if (current == PreviewSession.this) {
                            current = null;
                            lastFrameAt = 0;
                            updateUi();
                        }
                        releaseSession(PreviewSession.this);
                        return;
                    }
                    if (!resumed || current != PreviewSession.this || retiring || released) {
                        return;
                    }
                    state = next;
                    lastStatusAt = SystemClock.elapsedRealtime();
                    if (result.containsKey("recording")) {
                        helperRunning = result.getBoolean("recording");
                        if (commandTarget == null || commandTarget.equals(helperRunning)) {
                            requestedOn = null;
                        }
                    }
                    if ("error".equals(next)) {
                        commandTarget = null;
                        requestedOn = null;
                        if (!result.containsKey("recording")) {
                            helperRunning = recordingKnown && recordingObserved;
                        }
                    }
                    updateUi();
                }
            };
            // Bundle must name Android's base class, not this app's anonymous
            // subclass, which the separate helper APK cannot load.
            Parcel parcel = Parcel.obtain();
            try {
                localStatus.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                status = ResultReceiver.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
        }
    }
}
