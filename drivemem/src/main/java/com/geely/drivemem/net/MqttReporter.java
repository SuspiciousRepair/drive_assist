package com.geely.drivemem.net;

import com.geely.drivemem.car.CarAccess;
import com.geely.drivemem.car.CarActor;
import com.geely.drivemem.car.CarDataHub;
import com.geely.drivemem.car.EntityBus;
import com.geely.drivemem.car.Telemetry;
import com.geely.drivemem.controls.AdbGate;
import com.geely.drivemem.hvac.ComfortHub;
import com.geely.drivemem.hvac.ComfortRuler;
import com.geely.drivemem.sensors.GpsReader;
import com.geely.drivemem.state.GateState;
import com.geely.drivemem.state.PanelState;
import com.geely.drivemem.ui.ComfortActivity;
import com.geely.drivemem.util.Beat;
import com.geely.drivemem.util.SpotifyClient;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.Map;

// Publishes telemetry to Home Assistant over MQTT and accepts commands from HA
// (charge current limit, charging on/off, ambient light). HA decides; this
// class only acts. Auto-discovery creates the entities by itself.
//
// ARCHITECTURE — actor with a mailbox (GenServer model)
//
// Every MQTT operation runs on a single HandlerThread ("mqtt-actor") which is
// the EXCLUSIVE OWNER of the client and of all mutable state. Callers never
// block and never touch the state: they only post a message to the queue (cast).
//
// Reason: four distinct bugs in this codebase had the same root cause — a
// blocking operation on the wrong thread.
//   1. onDestroy calling disconnect() on the main thread -> ServiceRecord stuck
//      in "Destroy", watchdog powerless, only `am force-stop` fixed it.
//   2. synchronous publish() in the telemetry loop -> with a slow broker the
//      cycle grew from 30s to 45s.
//   3. Thread.sleep(2500) inside a Paho callback -> it held up every command.
//   4. "Slow" colour change: the VHAL applies it in 12ms, it was the echo that
//      stalled for 5s.
//
// The first round of fixes scattered `new Thread(...)` over those spots. That
// unblocked things, but created a race: `client`, `discoverySent`,
// `publishFails` were now touched by 4 threads with no synchronization — a
// worse bug, because it is probabilistic. The actor solves both at once: nobody
// blocks AND the state has a single owner.
//
// Uses MqttAsyncClient (same jar): publish hands the message off and returns,
// without holding even the actor's mailbox.
// TODO: Make base topic configurable, deduce the device id from car's VIN (partial VIN for safety and privacy?).
public class MqttReporter {
    static final String TAG = "DriveMem";
    static final String DEV_ID = "drivemem_" + getClientSuffix();
    static final String BASE_TOPIC = "drivemem/" + getClientSuffix();

    public static String getClientSuffix() {
        String vin = getSystemProperty("sys.ecarx.vin", null);
        if (vin != null && vin.length() >= 6) {
            return vin.substring(vin.length() - 6);
        }
        return "ihu";
    }

    public static String getClientId() {
        return "driveassist-" + getClientSuffix();
    }

    public static String getBaseTopic() {
        return BASE_TOPIC;
    }

    public static String getDevId() {
        return DEV_ID;
    }

    public static String getDeviceName() {
        String suffix = getClientSuffix();
        if (!"ihu".equals(suffix)) {
            return "Geely EX2 (" + suffix + ")";
        }
        return "Geely EX2";
    }

    private static String getDeviceJson() {
        return "\"dev\":{\"ids\":[\"" + DEV_ID + "\"],\"name\":\"" + getDeviceName() + "\",\"mf\":\"Geely\",\"mdl\":\"IHU629G\"}";
    }

    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = sp.getMethod("get", String.class, String.class);
            String val = (String) get.invoke(null, key, def);
            return (val != null && !val.isEmpty()) ? val : def;
        } catch (Throwable t) {
            return def;
        }
    }

    static final String STATE_TOPIC = BASE_TOPIC + "/state";
    static final String CHARGE_CMD_TOPIC = BASE_TOPIC + "/charge_limit/set";
    static final String CHARGE_LIMIT_STATE_TOPIC = BASE_TOPIC + "/charge_limit/state";
    static final String CHARGE_SW_CMD_TOPIC   = BASE_TOPIC + "/charging/set";
    static final String CHARGE_SW_STATE_TOPIC = BASE_TOPIC + "/charging/state";
    static final String AVAIL_TOPIC = BASE_TOPIC + "/available";
    static final String LIGHT_CMD_TOPIC = BASE_TOPIC + "/light/set";
    static final String LIGHT_STATE_TOPIC = BASE_TOPIC + "/light/state";
    static final String TRACKER_ATTR_TOPIC = BASE_TOPIC + "/tracker/attributes";
    // context panel that HA pushes (e.g. club parking spots, ferry queue).
    // DISPLAY ONLY — it does not control the car — so it stays OUTSIDE the
    // command lock.
    static final String PANEL_TOPIC = BASE_TOPIC + "/panel";
    // HA -> app, retained: "is the gate worth offering right now?"
    static final String GATE_AVAIL_TOPIC = BASE_TOPIC + "/gate/available";
    // app -> HA, NEVER retained. `/toggle` and not `/set` on purpose: in this
    // repo `/set` means HA writing to the car, and this goes the other way.
    // One topic, not one each for open/close: the gate motor has a single
    // toggle line (see GateState's header), and so does the HA automation on
    // the other end — same trigger, same cover.open_cover action, every time.
    static final String GATE_TOGGLE_TOPIC = BASE_TOPIC + "/gate/toggle";
    // What the gate is doing, pushed by HA from the cover entity. Rides with
    // availability, OUTSIDE the commands lock: it only draws a label.
    static final String GATE_STATE_TOPIC = BASE_TOPIC + "/gate/state";
    // Parking Mode (controllable): on/off switch + duration select
    static final String PARK_CMD_TOPIC         = BASE_TOPIC + "/park/set";
    static final String PARK_STATE_TOPIC       = BASE_TOPIC + "/park/state";
    static final String PARK_TIMER_CMD_TOPIC   = BASE_TOPIC + "/park_timer/set";
    static final String PARK_TIMER_STATE_TOPIC = BASE_TOPIC + "/park_timer/state";
    // auto-update: HA presses the button -> Drive Assist downloads the apk from /local
    // and installs it (root)
    static final String UPDATE_CMD_TOPIC   = BASE_TOPIC + "/update/set";
    static final String UPDATE_STATE_TOPIC = BASE_TOPIC + "/update/state";
    // WHICH BUILD IS ACTUALLY IN THE CAR. Updater cannot report its own success —
    // a successful install kills the process that would send the message — so the
    // only evidence used to be the car coming back `online`, which cannot tell a
    // new build from the old one restarting. This can. RETAINED, so the question
    // is answerable while the car is asleep instead of only on a telemetry tick.
    static final String VERSION_TOPIC = BASE_TOPIC + "/version";
    // REVERSE CONTROL: the broker presses things inside the app. Rides the same
    // commandsEnabled lock as charge/light/park — one switch, already on the
    // Config screen. Payloads: cool | warm | cool:3 | warm:2 | screen
    static final String ACTION_CMD_TOPIC = BASE_TOPIC + "/action/set";
    // What the ruler is doing, published on EVERY move whatever caused it — a
    // remote tap, a finger on the glass, a relax, a commit. Without this the
    // reverse control is open-loop and untestable from outside the car.
    static final String COMFORT_STATE_TOPIC = BASE_TOPIC + "/comfort/state";
    // adb, opened from Home Assistant for a bounded window and closed again by
    // modehelper. Only honoured on the home network — see AdbGate.isHome for why
    // that guard is worth having here and nowhere else.
    // The speed-limit experiment, streamed out so it can be watched from Home
    // Assistant while the car is nowhere near a cable. modehelper writes the file
    // into Drive Assist's OWN external files dir, so reading it needs no permission and
    // no second copy — the two apps already share that directory for the clips.
    static final String LIMITS_TOPIC = BASE_TOPIC + "/limits";
    static final String ADB_CMD_TOPIC   = BASE_TOPIC + "/adb/set";
    static final String ADB_STATE_TOPIC = BASE_TOPIC + "/adb/state";

    // --- mailbox messages --------------------------------------------------
    // Periodic state COALESCES (only the last one counts — it is retained, the
    // new one replaces the old). A command NEVER coalesces: an OFF followed by
    // an ON must not collapse into just one of them.
    private static final int MSG_STATE      = 1;  // coalesce
    private static final int MSG_LOCATION   = 2;  // coalesce
    private static final int MSG_CHARGE_SYNC= 3;  // coalesce
    // MSG_ECHO/doPub were dead (nothing ever sent them) AND a trap: doPub
    // publishes retained=true, so reusing it for the gate would leave a retained
    // "open" that re-fires the gate every time HA restarts and resubscribes.
    // Deleted; the gate has its own message and publishes non-retained at QoS 1.
    private static final int MSG_GATE_AVAIL  = 4;
    private static final int MSG_GATE_TOGGLE = 14;
    private static final int MSG_CMD_LIMIT  = 5;  // no
    private static final int MSG_CMD_CHARGE = 6;  // no
    private static final int MSG_CMD_LIGHT  = 7;  // no
    private static final int MSG_CLOSE      = 8;
    private static final int MSG_CMD_PARK       = 9;   // no
    private static final int MSG_CMD_PARK_TIMER = 10;  // no
    private static final int MSG_PARK_SYNC      = 11;  // coalesce (re-reads state)
    private static final int MSG_CMD_UPDATE     = 12;  // no
    private static final int MSG_CMD_ACTION     = 15;  // no
    private static final int MSG_CMD_ADB        = 16;  // no
    private static final int MSG_GATE_STATE     = 17;  // coalesce
    // 18/19/20 (MSG_MEDIA_*) freed — the Music card talks to Spotify's Web
    // API directly now, not through HA/MQTT. See SpotifyClient.

    private final String uri, user, pass;
    // Alternative addresses, in order of preference. Paho walks the list and
    // walks it again on every auto-reconnect, so the car uses the local broker
    // at home and falls back to the remote ones when away — with no
    // intervention and no config change.
    private final String[] uriList;
    private final Context ctx;         // the Beat, and now every car read (via CarActor)
    private final HandlerThread thread;
    private final Handler h;

    // ---- from here down: ONLY the actor thread touches this ---------------
    private MqttAsyncClient client;
    private boolean discoverySent = false;
    private boolean trackerDiscovered = false;
    private int publishFails = 0;
    private int lastBright = 0;
    private String lastChargingState = null;
    private int parkDurCode = 0x13;   // duration chosen in the select (default Ilimitado)
    // Last address that connected. Away from home (phone Wi-Fi) the LAN is
    // unreachable: trying the remote one that already worked first avoids
    // burning the LAN timeout on every cold reconnect.
    private String lastGoodTarget = null;
    // elapsedRealtime of when the client was noticed disconnected (0 =
    // connected). Gives Paho's auto-reconnect a window to come back on its own
    // before we recreate the client from scratch.
    private long disconnectedSince = 0;
    private static final long RECONNECT_GRACE_MS = 60 * 1000L;
    // Has THIS client ever completed a connection?
    //
    // The grace window above is a promise that somebody else is retrying, and
    // that promise is only true once Paho has connected at least once: its
    // automatic reconnect arms on the first successful connect and does nothing
    // for a client whose very first attempt failed. connectTo assigns `client`
    // before connecting, so a failed attempt leaves a non-null client that is
    // not connected and not reconnecting — and ensureConnected would then wait a
    // full minute for a retry that was never going to happen, every cycle.
    //
    // Twice in one evening that turned a broker restart into an outage lasting
    // until somebody opened the app: once when mosquitto rejected auth while it
    // was still rewriting its password file, and once when a bad address was
    // saved. A broker that is merely restarting must not cost more than the
    // seconds it takes to come back.
    private boolean everConnected = false;

    public MqttReporter(String uri, String user, String pass, Context ctx) {
        this(uri, null, user, pass, ctx);
    }

    /**
     * @param extras alternative addresses, one per line (or separated by
     *               comma/space). Tried in order, after the primary one.
     */
    public MqttReporter(String uri, String extras, String user, String pass, Context ctx) {
        this.uri = uri; this.user = user; this.pass = pass;
        this.uriList = buildUriList(uri, extras);
        this.ctx = (ctx != null) ? ctx.getApplicationContext() : null;
        thread = new HandlerThread("mqtt-actor");
        thread.start();
        h = new Handler(thread.getLooper(), this::handle);
        // The UI's only way in, and it ONLY POSTS. The UI thread never touches
        // `client`, never blocks, and pub() still runs on the actor thread — the
        // same rule the Paho callbacks follow (bugs 3 and 4 in the header).
        gateSender = () -> h.obtainMessage(MSG_GATE_TOGGLE).sendToTarget();
        GateState.setSender(gateSender);
        // Republish the instant the CAR changes charging/park state, not
        // just on the next tick — otherwise a change the car made on its
        // own (native menu, cable unplugged) reads stale in HA for up to a
        // full publish interval. Listeners fire on CarActor's own thread;
        // bounced onto `h` here, same rule pub()/doPublishState() already
        // depend on.
        chargingListener = (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK) h.post(() -> doPublishChargingState(true));
        };
        parkListener = (key, reading) -> {
            if (reading.status == CarActor.Reading.Status.OK) h.post(this::doPublishParkState);
        };
        EntityBus.subscribe("car.is_charging", chargingListener);
        EntityBus.subscribe("car.park_mode", parkListener);
    }

    private final GateState.Sender gateSender;
    private final EntityBus.Listener chargingListener;
    private final EntityBus.Listener parkListener;

    // Builds the server list in the order it was typed. Accepts newline, comma
    // or space as a separator in BOTH arguments — the UI uses a single
    // multiline field (one address per line), and `extras` is still accepted for
    // compatibility with the old config. Being tolerant avoids a silently broken
    // configuration; duplicates are removed so failover does not try the same
    // address twice.
    private static String[] buildUriList(String primary, String extras) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String src : new String[]{primary, extras}) {
            if (src == null) continue;
            for (String u : src.split("[,\\s]+")) {
                String t = u.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out.toArray(new String[0]);
    }

    // =======================================================================
    // Public API — all casts: post and return immediately. Nothing blocks.
    // =======================================================================

    public void publish(Map<String, Object> data) {
        if (data == null) return;
        h.removeMessages(MSG_STATE);                 // coalesce
        h.obtainMessage(MSG_STATE, data).sendToTarget();
    }

    public void publishLocation(double[] loc) {
        if (loc == null) return;
        h.removeMessages(MSG_LOCATION);              // coalesce
        h.obtainMessage(MSG_LOCATION, loc).sendToTarget();
    }

    // The only place that wants an answer back (the config screen, "Testar"
    // button). It is NOT a synchronous call: it posts to the mailbox and the
    // result comes back through the callback, run on the actor thread — the
    // caller does not wait.
    public interface Result { void onResult(boolean ok, String detail); }

    public void testConnection(final Map<String, Object> sample, final Result cb) {
        h.post(() -> {
            boolean ok = ensureConnected(true);
            String detail;
            if (!ok) detail = "Falha ao conectar ao broker";
            else if (sample == null || sample.isEmpty()) detail = "Conectou, mas sem dados do carro";
            else { doPublishState(sample); detail = "OK — publicado"; }
            cb.onResult(ok, detail);
        });
    }

    public void forceDiscovery() { forceDiscovery(null); }

    public void forceDiscovery(final Result cb) {
        h.post(() -> {
            boolean ok = ensureConnected(true);
            String detail;
            if (!ok) {
                detail = "Falha ao conectar ao broker";
            } else {
                discoverySent = false;
                trackerDiscovered = false;
                sendDiscovery();
                detail = "OK — descoberta MQTT enviada";
            }
            if (cb != null) cb.onResult(ok, detail);
        });
    }

    public void publishChargingState() { publishChargingState(false); }

    public void publishChargingState(boolean force) {
        h.removeMessages(MSG_CHARGE_SYNC);           // coalesce
        h.obtainMessage(MSG_CHARGE_SYNC, force ? 1 : 0, 0).sendToTarget();
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public String getConnectedBroker() {
        if (client != null && client.isConnected()) {
            return lastGoodTarget != null ? lastGoodTarget : client.getServerURI();
        }
        return null;
    }

    // Closes without blocking the caller (typically onDestroy, on the main
    // thread). The CLOSE is posted and quitSafely lets the queue drain — the
    // actor thread may take a few seconds to die, but decoupled from the
    // ServiceRecord, which is what jammed the watchdog in the past.
    public void close() {
        h.removeCallbacksAndMessages(null);          // drops anything pending
        h.sendEmptyMessage(MSG_CLOSE);
        thread.quitSafely();
    }

    // =======================================================================
    // From here down everything runs ON THE ACTOR THREAD.
    // =======================================================================

    @SuppressWarnings("unchecked")
    private boolean handle(Message m) {
        try {
            switch (m.what) {
                case MSG_STATE:       doPublishState((Map<String, Object>) m.obj); break;
                case MSG_LOCATION:    doPublishLocation((double[]) m.obj); break;
                case MSG_CHARGE_SYNC: doPublishChargingState(m.arg1 == 1); break;
                case MSG_GATE_AVAIL: {
                    boolean nowOnline = "online".equalsIgnoreCase((String) m.obj);
                    boolean isReplay = m.arg1 == 1;   // MQTT-spec guaranteed: true
                    // only for the reply to OUR subscribe, false for any message
                    // delivered while already subscribed, live push or not.
                    //
                    // A replayed "online" is dropped, not just delayed — it is
                    // the broker's stored value, not HA deciding anything just
                    // now, so it could be stale from before we lost the link.
                    // Staying hidden until a live "online" genuinely arrives
                    // is the safe failure mode.
                    if (!(nowOnline && isReplay)) GateState.setAvailable(nowOnline);
                    break;
                }
                case MSG_GATE_TOGGLE: doGateToggle(); break;
                case MSG_GATE_STATE:  GateState.setState((String) m.obj); break;
                case MSG_CMD_LIMIT:   doCmdLimit((String) m.obj); break;
                case MSG_CMD_CHARGE:  doCmdCharge((String) m.obj); break;
                case MSG_CMD_LIGHT:   doCmdLight((String) m.obj); break;
                case MSG_CMD_PARK:       doCmdPark((String) m.obj); break;
                case MSG_CMD_PARK_TIMER: doCmdParkTimer((String) m.obj); break;
                case MSG_PARK_SYNC:      doPublishParkState(); break;
                case MSG_CMD_UPDATE:     doCmdUpdate((String) m.obj); break;
                case MSG_CMD_ACTION:     doCmdAction((String) m.obj); break;
                case MSG_CMD_ADB:        doCmdAdb((String) m.obj); break;
                case MSG_CLOSE:       doClose(); break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "actor msg=" + m.what + " error: " + t);
        }
        return true;
    }

    private boolean ensureConnected() { return ensureConnected(false); }

    // force=true: used by the "Testar" button — it wants an answer now, so it
    // recreates the client on the spot instead of waiting for auto-reconnect.
    //
    // force=false (periodic telemetry): if the client exists but dropped, Paho
    // is ALREADY reconnecting on its own TO THE SAME address. Steamrolling that
    // with connect() — which closes this client and starts over from the LAN —
    // was exactly what caused the offline/online storm: every lost publish tore
    // down the good connection. Now we give it a window (RECONNECT_GRACE_MS) for
    // the auto-reconnect to come back; we only recreate if it is truly stuck.
    private boolean ensureConnected(boolean force) {
        if (client != null && client.isConnected()) {
            disconnectedSince = 0;
            return true;
        }
        // The grace window is only honoured when there is genuinely something to
        // wait for. everConnected false means Paho's auto-reconnect never armed,
        // so waiting is just staying offline on purpose — retry now instead. The
        // telemetry loop already paces this: it calls once per tick, so "retry
        // now" is one attempt every interval, not a spin.
        if (client != null && !force && everConnected) {
            long now = SystemClock.elapsedRealtime();
            if (disconnectedSince == 0) disconnectedSince = now;
            if (now - disconnectedSince < RECONNECT_GRACE_MS) {
                // let auto-reconnect do its work; skip this publish cycle
                return false;
            }
            Log.w(TAG, "MQTT stuck for " + (now - disconnectedSince) + "ms — recreating client");
        } else if (client != null && !force) {
            Log.i(TAG, "MQTT never connected on this client — retrying the address list now");
        }
        if (client != null) {
            dropClient();
            discoverySent = false; trackerDiscovered = false;
            everConnected = false;
        }
        disconnectedSince = 0; publishFails = 0;
        return connect();
    }

    // Tries the addresses IN ORDER, each one with its own client.
    //
    // setServerURIs cannot be used here: Paho validates the SSLSocketFactory
    // against the client's PRIMARY URI and rejects with error 32105 if they do
    // not match. Since the list mixes tcp:// (LAN, no TLS) and wss:// (remote,
    // with our own CA and cert), each attempt needs the right factory — and
    // therefore its own MqttAsyncClient.
    //
    // Practical effect: at home the first address connects immediately; away, it
    // fails fast (connectionTimeout 10s) and the remote one takes over.
    private boolean connect() {
        // The last address that worked goes first: away from home this skips the
        // unreachable LAN and goes straight to the remote, shortening the
        // offline window.
        if (lastGoodTarget != null && connectTo(lastGoodTarget)) return true;
        for (String target : uriList) {
            if (target.equals(lastGoodTarget)) continue;   // already tried above
            if (connectTo(target)) return true;
        }
        return false;
    }

    private boolean connectTo(String target) {
        try {
            dropClient();
            // STABLE id (not a timestamp): resumes the session and avoids
            // leaving junk behind on the broker
            client = new MqttAsyncClient(target, "driveassist-" + getClientSuffix(), new MemoryPersistence());
            // New instance: whatever the previous one managed says nothing about
            // this one, and claiming otherwise would hand out a grace window for
            // a reconnect that is not running.
            everConnected = false;
            // Paho reconnects by itself but does NOT re-subscribe: without this
            // the commands from HA stop arriving after any network drop.
            client.setCallback(new org.eclipse.paho.client.mqttv3.MqttCallbackExtended() {
                @Override public void connectComplete(boolean reconnect, String serverURI) {
                    if (reconnect) {
                        Log.i(TAG, "MQTT reconnected — resubscribing and marking online");
                        // back to the actor thread: never touch the state here.
                        // The broker fired the "offline" LWT when the connection
                        // dropped; auto-reconnect does NOT republish "online" by
                        // itself, so HA would stay unavailable forever without
                        // this pub.
                        h.post(() -> {
                            disconnectedSince = 0; publishFails = 0;
                            discoverySent = false; trackerDiscovered = false;
                            pub(AVAIL_TOPIC, "online", true);
                            GateState.setConnected(true);
                            subscribeCommands();
                            subscribePanel();
                        });
                    }
                }
                @Override public void connectionLost(Throwable cause) {
                    Log.w(TAG, "MQTT dropped: " + cause);
                    // Both must go false: a stale "available" from before the
                    // link dropped must not keep the card showing when connection
                    // is restored but the fresh availability has not arrived yet.
                    GateState.setConnected(false);
                    GateState.setAvailable(false);
                }
                @Override public void messageArrived(String t, MqttMessage msg) {}
                @Override public void deliveryComplete(org.eclipse.paho.client.mqttv3.IMqttDeliveryToken t) {}
            });
            MqttConnectOptions o = new MqttConnectOptions();
            o.setAutomaticReconnect(true);
            o.setCleanSession(true);
            // The LAN answers in <1s or does not answer at all; a short timeout
            // so failover to the remote is fast when the car is away from home.
            o.setConnectionTimeout(target.startsWith("tcp://") ? 4 : 10);
            o.setKeepAliveInterval(30);
            o.setMaxInflight(20);
            if (user != null && !user.isEmpty()) { o.setUserName(user); o.setPassword(pass.toCharArray()); }
            // LWT: if the car sleeps/drops, the broker publishes "offline" and
            // HA marks the entities unavailable instead of showing stale data
            o.setWill(AVAIL_TOPIC, "offline".getBytes("UTF-8"), 1, true);

            if (MqttTls.isTls(target)) {
                javax.net.ssl.SSLSocketFactory sf = MqttTls.build(ctx);
                if (sf == null) { Log.w(TAG, "MQTT: " + target + " requires TLS but the certificates are missing"); return false; }
                o.setSocketFactory(sf);
            }

            // the only place that waits: with no connection there is nothing to
            // do anyway
            client.connect(o).waitForCompletion(15000);
            Log.i(TAG, "MQTT connected: " + target);
            lastGoodTarget = target;
            disconnectedSince = 0;
            publishFails = 0;
            // From here Paho's auto-reconnect is armed, so the grace window in
            // ensureConnected means something again.
            everConnected = true;
            GateState.setConnected(true);
            pub(AVAIL_TOPIC, "online", true);
            // Right after "online", because the two together are the OTA's only
            // report: coming back says the car rebooted, this says WHICH build
            // came back. Published on connect rather than on the telemetry tick —
            // it cannot change while this process lives.
            pub(VERSION_TOPIC, versionJson(), true);
            // Every ruler move gets published, whoever caused it. Registered here
            // rather than at construction so it is only live while connected.
            ComfortHub.removeListener(comfortListener);
            ComfortHub.addListener(comfortListener);
            pubComfort();
            subscribeCommands();
            subscribePanel();
            return true;
        } catch (Throwable t) {
            // root cause spelled out: with TLS the top-level error ("Connection
            // closed by peer") says nothing useful
            Throwable root = t; while (root.getCause() != null) root = root.getCause();
            Log.w(TAG, "MQTT " + target + " failed: " + t + " | cause: " + root);
            // A FAILED ATTEMPT MUST NOT LEAVE ITS CLIENT BEHIND. The options
            // already had automaticReconnect set, so an abandoned instance keeps
            // retrying its own target for the life of the process — while
            // holding the same client id as the one that eventually works.
            dropClient();
            return false;
        }
    }

    // Tear the client down PROPERLY, and it has to be in this order: callback
    // first, then disconnect, then close. Paho's close() refuses on a client
    // that is connected or connecting. Without this order, leaked client instances
    // would keep retrying with the same client id, causing the broker to kick them
    // off, which triggers a cascade of LWT messages and commands being dropped.
    private void dropClient() {
        org.eclipse.paho.client.mqttv3.MqttAsyncClient c = client;
        client = null;
        if (c == null) return;
        try { c.setCallback(null); } catch (Throwable ignored) {}
        try { c.disconnectForcibly(250, 250); } catch (Throwable ignored) {}
        try { c.close(); } catch (Throwable ignored) {}
    }

    // Command lock: when switched off, the app does NOT subscribe to any command
    // topic — the car goes deaf to MQTT and only sends telemetry. It is a real
    // lock (it does not ignore a received message: it never even receives it),
    // so that a misconfigured HA or an old retained message on the broker cannot
    // write to the car.
    private volatile boolean commandsEnabled = true;

    // Fires on the main thread (ComfortRuler posts there); safe because pub() is
    // asynchronous — Paho does its own IO.
    private final Runnable comfortListener = this::pubComfort;

    public void setCommandsEnabled(boolean on) {
        commandsEnabled = on;
        h.post(() -> {
            if (client == null || !client.isConnected()) return;
            if (on) { subscribeCommands(); }
            else {
                for (String t : new String[]{CHARGE_CMD_TOPIC, CHARGE_SW_CMD_TOPIC, LIGHT_CMD_TOPIC,
                                             PARK_CMD_TOPIC, PARK_TIMER_CMD_TOPIC, UPDATE_CMD_TOPIC,
                                             ACTION_CMD_TOPIC, ADB_CMD_TOPIC}) {
                    try { client.unsubscribe(t); } catch (Throwable ignored) {}
                }
                Log.i(TAG, "commands OFF — unsubscribed from every topic");
            }
        });
    }

    // Paho's callbacks ONLY post to the mailbox: they return in microseconds and
    // never hold up Paho's network thread (bugs 3 and 4).
    private void subscribeCommands() {
        if (!commandsEnabled) { Log.i(TAG, "commands off — not subscribing"); return; }
        try {
            client.subscribe(CHARGE_CMD_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_CMD_LIMIT, new String(msg.getPayload()).trim()).sendToTarget());
            Log.i(TAG, "subscribed to " + CHARGE_CMD_TOPIC);

            client.subscribe(CHARGE_SW_CMD_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_CMD_CHARGE, new String(msg.getPayload()).trim()).sendToTarget());
            Log.i(TAG, "subscribed to " + CHARGE_SW_CMD_TOPIC);

            client.subscribe(LIGHT_CMD_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_CMD_LIGHT, new String(msg.getPayload())).sendToTarget());
            Log.i(TAG, "subscribed to " + LIGHT_CMD_TOPIC);

            client.subscribe(PARK_CMD_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_CMD_PARK, new String(msg.getPayload()).trim()).sendToTarget());
            client.subscribe(PARK_TIMER_CMD_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_CMD_PARK_TIMER, new String(msg.getPayload()).trim()).sendToTarget());
            Log.i(TAG, "subscribed to " + PARK_CMD_TOPIC + " e " + PARK_TIMER_CMD_TOPIC);

            client.subscribe(UPDATE_CMD_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_CMD_UPDATE, new String(msg.getPayload()).trim()).sendToTarget());
            Log.i(TAG, "subscribed to " + UPDATE_CMD_TOPIC);

            client.subscribe(ACTION_CMD_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_CMD_ACTION, new String(msg.getPayload()).trim()).sendToTarget());
            Log.i(TAG, "subscribed to " + ACTION_CMD_TOPIC);

            client.subscribe(ADB_CMD_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_CMD_ADB, new String(msg.getPayload()).trim()).sendToTarget());
            Log.i(TAG, "subscribed to " + ADB_CMD_TOPIC);
            // Report the truth as soon as we are listening, so a retained ON
            // left over from a previous session cannot make Home Assistant show
            // a window that modehelper has long since closed.
            publishAdbState();
        } catch (Throwable t) { Log.w(TAG, "subscribe failed: " + t); }
    }

    // HA's context panel. Outside the command lock: it is display, not control.
    // QoS 0 and retained on the broker => on connect it already gets the current
    // card.
    private void subscribePanel() {
        try {
            client.subscribe(PANEL_TOPIC, 0, (IMqttMessageListener) (topic, msg) ->
                PanelState.set(new String(msg.getPayload())));
            Log.i(TAG, "subscribed to " + PANEL_TOPIC);

            // Pushes a fresh fix right away so HA can re-decide our zone the
            // instant the link is back, instead of waiting up to a full
            // telemetry interval for the next scheduled one.
            if (ctx != null) publishLocation(GpsReader.read(ctx));
            // The gate rides with the panel, OUTSIDE commandsEnabled. That lock
            // exists to stop HA writing to the CAR; the gate is the driver asking
            // the world to open something. Putting it behind the lock would make
            // the button vanish with no explanation whenever telemetry commands
            // are off.
            client.subscribe(GATE_AVAIL_TOPIC, 1, (IMqttMessageListener) (topic, msg) ->
                h.obtainMessage(MSG_GATE_AVAIL, msg.isRetained() ? 1 : 0, 0,
                        new String(msg.getPayload()).trim()).sendToTarget());
            Log.i(TAG, "subscribed to " + GATE_AVAIL_TOPIC);

            client.subscribe(GATE_STATE_TOPIC, 1, (IMqttMessageListener) (topic, msg) -> {
                h.removeMessages(MSG_GATE_STATE);   // only the latest matters
                h.obtainMessage(MSG_GATE_STATE, new String(msg.getPayload()).trim()).sendToTarget();
            });
            Log.i(TAG, "subscribed to " + GATE_STATE_TOPIC);
        } catch (Throwable t) { Log.w(TAG, "subscribe panel failed: " + t); }
    }

    // --- commands coming from HA -------------------------------------------

    // Second layer: even after the unsubscribe, a message can still be in flight
    // (a retained one delivered earlier). Every handler refuses when the lock is
    // off — defence in depth, not pointless redundancy.
    private boolean cmdBlocked(String what) {
        if (commandsEnabled) return false;
        Log.w(TAG, "command " + what + " REFUSED (commands off)");
        return true;
    }

    // Every car write from here now goes through CarActor — the one shared
    // connection Turbo and the Config picker also use — instead of calling
    // CarDataHub directly on this actor's own `car`. A rejection is always
    // logged, since it's the one place a clamp or closed-value-set actually
    // bites. CarActor's callback runs on ITS OWN thread, not this one, so a
    // caller that needs to react (e.g. publish state) passes `cb` and gets
    // it invoked back on `h` — the same "only the actor thread touches
    // this" rule pub() already depends on, not a new one.
    private void applyLogged(String label, String key, Object value) {
        applyLogged(label, key, value, null);
    }

    private void applyLogged(String label, String key, Object value, CarActor.ResultCallback cb) {
        if (ctx == null) { Log.w(TAG, "CMD " + label + ": no Context for CarActor"); return; }
        CarActor.get(ctx).cast(key, value, r -> {
            if (!r.applied) Log.w(TAG, "CMD " + label + " rejected: " + r.error);
            if (cb != null) h.post(() -> cb.onResult(r));
        });
    }

    // Command handlers that need "the current value" synchronously read directly
    // from CarActor's in-memory cache without blocking.
    // Handles aliases ("park_mode", "ambient_brightness", "ambient_color", "charging")
    // as well as canonical CarActor keys ("car.park_mode", "telemetry.ambient_brightness", etc.).
    private Integer readCached(String key) {
        if (ctx == null) return null;
        String actorKey;
        switch (key) {
            case "park_mode": actorKey = "car.park_mode"; break;
            case "ambient_brightness": actorKey = "telemetry.ambient_brightness"; break;
            case "ambient_color": actorKey = "telemetry.ambient_color"; break;
            case "charging":
            case "is_charging": actorKey = "car.is_charging"; break;
            default: actorKey = key; break;
        }
        CarActor.Reading r = CarActor.get(ctx).get(actorKey);
        if (r.status != CarActor.Reading.Status.OK && "car.is_charging".equals(actorKey)) {
            r = CarActor.get(ctx).get("telemetry.is_charging");
        }
        if (r.status == CarActor.Reading.Status.OK && r.value instanceof Number) {
            return ((Number) r.value).intValue();
        }
        return null;
    }

    private void doCmdLimit(String body) {
        if (cmdBlocked("limit")) return;
        Log.i(TAG, "CMD charge limit from HA: " + body);
        if (ctx == null) return;
        try {
            int amps = Math.round(Float.parseFloat(body));
            applyLogged("charge limit", "charge_current_limit", amps, r -> {
                if (r.applied) pub(CHARGE_LIMIT_STATE_TOPIC, String.valueOf(r.value), true);
            });
        } catch (NumberFormatException e) {
            Log.w(TAG, "CMD invalid limit: " + body);
        }
    }

    private void doCmdCharge(String body) {
        if (cmdBlocked("charge")) return;
        Log.i(TAG, "CMD charging on/off from HA: " + body);
        if (ctx == null) return;
        boolean on = body.equalsIgnoreCase("ON");
        applyLogged("charging", "charging", on ? CarAccess.CHARGE_ON : CarAccess.CHARGE_OFF);
        // Read it back after the car settles. postDelayed instead of sleep: the
        // mailbox keeps serving everything else in the meantime.
        h.removeMessages(MSG_CHARGE_SYNC);
        h.sendMessageDelayed(h.obtainMessage(MSG_CHARGE_SYNC, 1, 0), 2500);
    }

    // Parking Mode: ON arms it with the chosen duration; OFF writes 0.
    private void doCmdPark(String body) {
        if (cmdBlocked("park")) return;
        if (ctx == null) return;
        boolean on = body.equalsIgnoreCase("ON");
        applyLogged("parking mode", "park_mode", on ? (CarAccess.PARK_ON_BASE | parkDurCode) : 0);
        Log.i(TAG, "CMD parking mode: " + body);
        h.removeMessages(MSG_PARK_SYNC);
        h.sendMessageDelayed(h.obtainMessage(MSG_PARK_SYNC), 1500);
    }

    // Picking a duration in the select already arms the mode with it.
    private void doCmdParkTimer(String body) {
        if (cmdBlocked("park")) return;
        if (ctx == null) return;
        parkDurCode = Telemetry.parkTimerCode(body);
        applyLogged("parking timer", "park_mode", CarAccess.PARK_ON_BASE | parkDurCode);
        Log.i(TAG, "CMD parking timer: " + body + " (0x" + Integer.toHexString(parkDurCode) + ")");
        h.removeMessages(MSG_PARK_SYNC);
        h.sendMessageDelayed(h.obtainMessage(MSG_PARK_SYNC), 1500);
    }

    // publishes the switch state (ON/OFF) and the select state (duration) on
    // their own topics
    private void doPublishParkState() {
        if (ctx == null) return;
        Integer pm = readCached("park_mode");
        if (pm == null) return;
        boolean on = pm != 0;
        if (on) parkDurCode = pm & 0xFF;   // sync with the real duration
        pub(PARK_STATE_TOPIC, on ? "ON" : "OFF", true);
        pub(PARK_TIMER_STATE_TOPIC, Telemetry.parkTimerLabel(CarAccess.PARK_ON_BASE | parkDurCode), true);
    }

    // auto-update triggered by HA: downloads the apk (payload = URL, or
    // empty/"go" uses the default /local) and installs it via root. Behind the
    // command lock.
    // adb over MQTT. The window is the point: `on` opens it for
    // AdbGate.DEFAULT_MINUTES and modehelper closes it again on its own, so
    // forgetting to send `off` is not a way to leave a root shell listening.
    // Payloads: on | ON | on:30 | off
    private void doCmdAdb(String body) {
        if (cmdBlocked("adb")) return;
        if (ctx == null) { Log.w(TAG, "adb: no Context"); return; }
        String b = (body == null) ? "" : body.trim().toLowerCase(java.util.Locale.US);
        if (b.isEmpty()) return;

        int minutes = AdbGate.DEFAULT_MINUTES;
        int c = b.indexOf(':');
        if (c > 0) {
            try { minutes = Integer.parseInt(b.substring(c + 1).trim()); }
            catch (Throwable ignored) {}
            b = b.substring(0, c).trim();
        }
        boolean on = "on".equals(b) || "1".equals(b) || "true".equals(b);

        // Refused away from home, and only for opening: closing is always
        // allowed, from anywhere, because the safe direction never needs a
        // guard. This is the one check in the chain that knows the request came
        // from the broker rather than from someone sitting in the car.
        if (on && !AdbGate.isHome(ctx)) {
            Log.w(TAG, "CMD adb ON refused: the car is not on the home network");
            pub(ADB_STATE_TOPIC, "OFF", true);
            return;
        }

        Log.i(TAG, "CMD adb " + (on ? "ON for " + minutes + " min" : "OFF"));
        AdbGate.request(ctx, on, minutes);
        // The helper flips it asynchronously; re-read rather than assume, and
        // give it a moment to land.
        h.postDelayed(this::publishAdbState, 800);
    }

    // Always the setting's own value, never what was asked for — a request that
    // modehelper refused (or that never reached it) must not show as ON in Home
    // Assistant. Retained, so the state survives the car sleeping.
    private void publishAdbState() {
        if (ctx == null) return;
        pub(ADB_STATE_TOPIC, AdbGate.isEnabled(ctx) ? "ON" : "OFF", true);
    }

    private void doCmdUpdate(String body) {
        if (cmdBlocked("update")) return;
        if (ctx == null) { Log.w(TAG, "update: no Context"); return; }
        Log.i(TAG, "CMD update from HA: " + body);

        if (body != null && body.toLowerCase(java.util.Locale.US).contains("force")) {
            pub(UPDATE_STATE_TOPIC, "iniciando (forçado)", false);
            Updater.update(ctx, body, s -> pub(UPDATE_STATE_TOPIC, s, false));
            return;
        }

        pub(UPDATE_STATE_TOPIC, "verificando", false);
        Updater.check(ctx, body, new Updater.CheckCallback() {
            @Override
            public void onUpdateAvailable(Updater.UpdateInfo info) {
                pub(UPDATE_STATE_TOPIC, "disponivel: " + info.versionName, false);
                Intent it = new Intent(Updater.ACTION_UPDATE_AVAILABLE);
                it.putExtra("versionName", info.versionName);
                it.putExtra("versionCode", info.versionCode);
                it.putExtra("changelog", info.changelog);
                it.putExtra("url", info.apkUrl);
                ctx.sendBroadcast(it);
            }

            @Override
            public void onAlreadyUpToDate(String currentVer) {
                pub(UPDATE_STATE_TOPIC, "já atualizado (" + currentVer + ")", false);
            }

            @Override
            public void onError(String error) {
                pub(UPDATE_STATE_TOPIC, "erro: " + error, false);
            }
        });
    }

    // REVERSE CONTROL. Payloads: cool | warm | cool:3 | warm:2 | screen
    //
    // A remote tap is the same event as a finger on the glass — deliberately, so
    // it goes through ComfortRuler.tap() and lands in comfort.log with the same
    // quiet-before-the-complaint that every other reading carries. It is NOT a
    // back door that writes HVAC directly; that would produce a car state the
    // ruler does not know it is in.
    //
    // The count is clamped: a malformed retained payload must not be able to run
    // the ruler to its endpoint.
    private void doCmdAction(String body) {
        String b = (body == null) ? "" : body.trim().toLowerCase(java.util.Locale.US);
        if (b.isEmpty()) return;
        int n = 1;
        int c = b.indexOf(':');
        if (c > 0) {
            try { n = Integer.parseInt(b.substring(c + 1).trim()); } catch (Throwable ignored) {}
            n = Math.max(1, Math.min(10, n));
            b = b.substring(0, c).trim();
        }
        Log.i(TAG, "action: " + b + " x" + n);
        if ("cool".equals(b) || "warm".equals(b)) {
            // get(), not peek(): a remote tap is a legitimate reason to bring the
            // ruler up when nobody has opened the screen this trip.
            ComfortRuler r = ComfortHub.get(ctx);
            int dir = "cool".equals(b) ? +1 : -1;
            for (int i = 0; i < n; i++) r.tap(dir);
            return;   // the listener publishes the new state when the tap lands
        }
        if ("screen".equals(b)) {
            try {
                android.content.Intent i = new android.content.Intent(ctx, ComfortActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Throwable t) { Log.w(TAG, "action screen: " + t); }
            return;
        }
        Log.w(TAG, "action: unknown '" + b + "'");
    }

    // peek(), not get(): publishing state must never be the reason a ruler and a
    // CarAccess come into existence.
    private void pubComfort() {
        ComfortRuler r = ComfortHub.peek();
        if (r == null) return;
        pub(COMFORT_STATE_TOPIC, "{\"pointer\":" + r.pointer()
            + ",\"cool_max\":" + r.coolMax()
            + ",\"heat_max\":" + r.heatMax()
            + ",\"status\":\"" + r.status().replace("\"", "'") + "\"}", true);
    }

    private void doCmdLight(String body) {
        if (cmdBlocked("light")) return;
        Log.i(TAG, "CMD light from HA: " + body);
        if (ctx == null) return;
        try {
            if (body.contains("\"OFF\"")) {
                // turning it off = brightness 0 (the car has no separate on/off)
                if (lastBright <= 0) {
                    Integer cur = readCached("ambient_brightness");
                    lastBright = (cur != null && cur > 0) ? cur : 14;
                }
                applyLogged("light off", "ambient_brightness", 0);
                pub(LIGHT_STATE_TOPIC, "{\"state\":\"OFF\"}", true);
                return;
            }
            // BRIGHTNESS FIRST, THEN COLOUR, and the order is not cosmetic.
            // Brightness 0 is this car's only "off", so an ON command can arrive
            // with the strip dark — and a colour sent to a lamp that is off is one
            // the ECU has every reason to drop. Written the other way round, an HA
            // scene that turns the light on AND sets a colour would come up at the
            // colour it had last time.
            // brightness: HA sends 0..255, the car uses 0..20
            int ha = jsonInt(body, "\"brightness\"");
            if (ha >= 0) {
                int lvl = Math.max(1, Math.round(ha * CarAccess.AMBIENT_BRIGHT_MAX / 255f));
                applyBrightness(lvl);
                lastBright = lvl;
            } else {
                // ON arrived with no brightness. Whether to act depends on the
                // CAR's actual current brightness, not on lastBright alone: the
                // OFF branch above deliberately leaves lastBright holding the
                // value to restore next time, so it is > 0 right after almost
                // every OFF — checking lastBright<=0 here used to skip this
                // restore on every ON after the first, leaving the car dark
                // while HA believed it had turned the light on.
                Integer cur = readCached("ambient_brightness");
                if (cur == null || cur <= 0) {
                    int lvl = (lastBright > 0) ? lastBright : 14;
                    applyBrightness(lvl);
                    lastBright = lvl;
                }
            }
            // a colour-only command with the light already on does not rewrite
            // the brightness

            int r = jsonInt(body, "\"r\""), g = jsonInt(body, "\"g\""), b = jsonInt(body, "\"b\"");
            if (r >= 0 && g >= 0 && b >= 0) applyColor((r << 16) | (g << 8) | b);
            pub(LIGHT_STATE_TOPIC, lightStateJson(), true);
        } catch (Throwable t) { Log.w(TAG, "CMD invalid light: " + t); }
    }

    // Pushes the new value into CarActor's cache the instant the write is
    // confirmed applied, instead of waiting for the next telemetry.ambient_*
    // poll (up to 4s) to notice the car agrees with what we just told it.
    // The car already changed the moment the write landed; only the app's own
    // idea of the car's state was lagging.
    private void applyBrightness(int lvl) {
        applyLogged("light brightness", "ambient_brightness", lvl, r -> {
            if (r.applied && ctx != null) CarActor.get(ctx).inject("telemetry.ambient_brightness", lvl);
        });
    }

    private void applyColor(int rgb) {
        applyLogged("light colour", "ambient_color", rgb, r -> {
            if (r.applied && ctx != null) CarActor.get(ctx).inject("telemetry.ambient_color", rgb);
        });
    }

    // --- publishing ---------------------------------------------------------

    private void doPublishState(Map<String, Object> data) {
        // With the actor, the telemetry loop never stalls again — so the loop's
        // beat would stay green even with MQTT dead. This second beat measures
        // what actually matters: the actor is managing to publish.
        if (!ensureConnected()) return;
        if (ctx != null) Beat.mark(ctx, Beat.MQTT);
        if (!discoverySent) sendDiscovery();
        StringBuilder j = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!first) j.append(","); first = false;
            j.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof String) j.append("\"").append(esc((String) v)).append("\"");
            else j.append(v);
        }
        j.append("}");
        pub(STATE_TOPIC, j.toString(), false);
        drainLimits();
        doPublishChargingState(false);   // keeps HA's switch in sync
        doPublishParkState();            // Parking Mode switch + select
        // modehelper closes the adb window on its own schedule and has no way to
        // tell HA about it, so the telemetry tick is what makes the switch fall
        // back to OFF instead of lying until somebody touches it.
        publishAdbState();
    }

    private void sendTrackerDiscovery() {
        String cfg = "{"
            + "\"name\":\"Localização\",\"uniq_id\":\"" + DEV_ID + "_tracker\","
            + "\"json_attr_t\":\"" + TRACKER_ATTR_TOPIC + "\","
            + "\"source_type\":\"gps\","
            + getDeviceJson()
            + "}";
        pub("homeassistant/device_tracker/" + DEV_ID + "/loc/config", cfg, true);
        trackerDiscovered = true;
    }

    private void doPublishLocation(double[] loc) {
        if (!ensureConnected()) return;
        if (!trackerDiscovered) sendTrackerDiscovery();
        String j = "{\"latitude\":" + loc[0] + ",\"longitude\":" + loc[1]
            + ",\"gps_accuracy\":" + loc[5] + ",\"altitude\":" + loc[2]
            + ",\"course\":" + loc[3] + ",\"speed\":" + loc[4] + "}";
        pub(TRACKER_ATTR_TOPIC, j, true);
        Log.i(TAG, "gps published: " + loc[0] + "," + loc[1]);
    }

    // Publishes the REAL state read from the car, not the requested value:
    // asking for ON with the cable unplugged must not paint the HA switch as on.
    // 605028609 covers both "stopped" and "scheduled, waiting for its time" —
    // the property reports energy flow, not intent.
    // Tail limits.log and publish whatever is new.
    //
    // BY OFFSET, not by re-reading: the file only grows, and re-sending it every
    // thirty seconds would republish the whole drive each tick. The offset resets
    // if the file shrinks, which is what a delete looks like.
    //
    // Only on a tick, never on the write: this is an experiment producing a line
    // every few seconds at most, and it does not deserve its own timer or the
    // right to wake the radio.
    private long limitsAt = 0;

    private void drainLimits() {
        if (ctx == null) return;
        try {
            java.io.File f = new java.io.File(
                new java.io.File(ctx.getExternalFilesDir(null), "dashcam"), "limits.log");
            if (!f.exists()) return;
            long len = f.length();
            if (len < limitsAt) limitsAt = 0;         // truncated or replaced
            if (len == limitsAt) return;              // nothing new
            java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r");
            try {
                r.seek(limitsAt);
                String line;
                int sent = 0;
                // A cap, so a backlog cannot turn one tick into a flood on a
                // link that may be a phone tether.
                while ((line = r.readLine()) != null && sent < 40) {
                    if (!line.trim().isEmpty()) { pub(LIMITS_TOPIC, line, false); sent++; }
                }
                limitsAt = r.getFilePointer();
            } finally { r.close(); }
        } catch (Throwable t) { Log.w(TAG, "limits: " + t); }
    }

    // Reads CarActor's cache, not the CHARGE_SWITCH property directly — the
    // switch's read-back can be intermittently wrong while charging. Use
    // telemetry.is_charging instead, which is current-derived and reliable.
    private void doPublishChargingState(boolean force) {
        if (ctx == null) return;
        CarActor.Reading r = CarActor.get(ctx).get("car.is_charging");
        if (r.status != CarActor.Reading.Status.OK) {
            r = CarActor.get(ctx).get("telemetry.is_charging");
        }
        if (r.status != CarActor.Reading.Status.OK) return;
        String s = Integer.valueOf(1).equals(r.value) ? "ON" : "OFF";
        if (!force && s.equals(lastChargingState)) return;   // only on change
        if (!ensureConnected()) return;
        lastChargingState = s;
        pub(CHARGE_SW_STATE_TOPIC, s, true);
    }

    // QoS 1 and NOT retained. A gate press happens once and there is no repeat
    // to cover a loss, so fire-and-forget is not good enough here; and a
    // retained one would re-toggle the gate on every HA restart.
    // Publishes to BOTH /gate/toggle (with "toggle") and /gate/open (with "open")
    // to ensure full compatibility with any Home Assistant automation configuration.
    private void doGateToggle() {
        Log.i(TAG, "gate: user requested gate toggle — verifying connection");
        if (ensureConnected(true)) {
            pub(GATE_TOGGLE_TOPIC, "toggle", false, 1);
            pub(BASE_TOPIC + "/gate/open", "open", false, 1);
            Log.i(TAG, "gate: actuation published to " + GATE_TOGGLE_TOPIC + " and " + (BASE_TOPIC + "/gate/open"));
        } else {
            Log.w(TAG, "gate: cannot actuate gate — failed to connect to broker");
        }
    }

    private void doClose() {
        GateState.setAvailable(false);
        GateState.setConnected(false);
        GateState.clearSender(gateSender);
        EntityBus.unsubscribe("car.is_charging", chargingListener);
        EntityBus.unsubscribe("car.park_mode", parkListener);
        final MqttAsyncClient c = client;
        client = null;
        if (c == null) return;
        // a clean disconnect does NOT fire the LWT: without this publish HA would
        // show the car online forever
        try {
            if (c.isConnected()) {
                MqttMessage off = new MqttMessage("offline".getBytes("UTF-8"));
                off.setQos(1); off.setRetained(true);
                c.publish(AVAIL_TOPIC, off).waitForCompletion(1500);
            }
        } catch (Throwable ignored) {}
        try { c.disconnectForcibly(300, 300); } catch (Throwable ignored) {}
        try { c.close(); } catch (Throwable ignored) {}
        Log.i(TAG, "MQTT closed");
    }

    // asynchronous publish: hands off to Paho and returns, without holding the
    // mailbox
    private void pub(String topic, String payload, boolean retained) {
        pub(topic, payload, retained, 0);
    }

    private void pub(String topic, String payload, boolean retained, int qos) {
        try {
            MqttMessage m = new MqttMessage(payload.getBytes("UTF-8"));
            m.setQos(qos); m.setRetained(retained);
            client.publish(topic, m);
        } catch (Throwable t) {
            publishFails++;
            Log.w(TAG, "pub " + topic + " failed (" + publishFails + "): " + t);
        }
    }

    // --- helpers ------------------------------------------------------------

    private String lightStateJson() {
        try {
            Integer c = readCached("ambient_color");
            Integer br = readCached("ambient_brightness");
            int v = (c == null ? 0xFFFFFF : c) & 0xFFFFFF;
            int lvl = (br == null ? 0 : br);
            int ha = Math.round(lvl * 255f / CarAccess.AMBIENT_BRIGHT_MAX);
            return "{\"state\":\"" + (lvl > 0 ? "ON" : "OFF") + "\",\"color_mode\":\"rgb\","
                + "\"brightness\":" + ha + ",\"color\":{\"r\":" + ((v >> 16) & 0xFF)
                + ",\"g\":" + ((v >> 8) & 0xFF) + ",\"b\":" + (v & 0xFF) + "}}";
        } catch (Throwable t) { return "{\"state\":\"ON\"}"; }
    }

    // escapes quotes/backslashes: a loose String value would break the state JSON
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int jsonInt(String json, String key) {
        int k = json.indexOf(key);
        if (k < 0) return -1;
        int i = json.indexOf(':', k + key.length());
        if (i < 0) return -1;
        int j = i + 1;
        while (j < json.length() && !Character.isDigit(json.charAt(j)) && json.charAt(j) != '-') j++;
        int s = j;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        try { return Integer.parseInt(json.substring(s, j)); } catch (Exception e) { return -1; }
    }

    // --- discovery ----------------------------------------------------------

    private void sendDiscovery() {
        for (Telemetry.Field f : Telemetry.FIELDS) {
            String comp = ("ac_on".equals(f.key) || "charging".equals(f.key))
                    ? "binary_sensor" : "sensor";
            String topic = "homeassistant/" + comp + "/" + DEV_ID + "/" + f.key + "/config";
            StringBuilder j = new StringBuilder("{");
            j.append("\"name\":\"").append(f.name).append("\",");
            j.append("\"uniq_id\":\"").append(DEV_ID).append("_").append(f.key).append("\",");
            j.append("\"stat_t\":\"").append(STATE_TOPIC).append("\",");
            j.append("\"avty_t\":\"").append(AVAIL_TOPIC).append("\",");
            j.append("\"val_tpl\":\"{{ value_json.").append(f.key).append(" }}\",");
            if (f.unit != null) j.append("\"unit_of_meas\":\"").append(f.unit).append("\",");
            if (f.devClass != null) j.append("\"dev_cla\":\"").append(f.devClass).append("\",");
            if ("binary_sensor".equals(comp)) j.append("\"pl_on\":\"1\",\"pl_off\":\"0\",");
            j.append(getDeviceJson());
            j.append("}");
            pub(topic, j.toString(), true);
        }
        sendChargeNumberDiscovery();
        sendChargeSwitchDiscovery();
        sendPlugDiscovery();
        sendLightDiscovery();
        sendParkDiscovery();
        sendUpdateDiscovery();
        sendTrackerDiscovery();
        discoverySent = true;
    }

    // CONTROLLABLE Parking Mode: switch (on/off) + select (duration).
    private void sendParkDiscovery() {
        String dev = getDeviceJson();
        // removes the old entities (they were a read-only binary_sensor + sensor)
        pub("homeassistant/binary_sensor/" + DEV_ID + "/park_mode/config", "", true);
        pub("homeassistant/sensor/" + DEV_ID + "/park_timer/config", "", true);

        String sw = "{"
            + "\"name\":\"Modo Estacionamento\","
            + "\"uniq_id\":\"" + DEV_ID + "_park_sw\","
            + "\"cmd_t\":\"" + PARK_CMD_TOPIC + "\","
            + "\"stat_t\":\"" + PARK_STATE_TOPIC + "\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + "\"ic\":\"mdi:car-clock\"," + dev
            + "}";
        pub("homeassistant/switch/" + DEV_ID + "/park/config", sw, true);
        String sel = "{"
            + "\"name\":\"Timer estacionamento\","
            + "\"uniq_id\":\"" + DEV_ID + "_park_timer_sel\","
            + "\"cmd_t\":\"" + PARK_TIMER_CMD_TOPIC + "\","
            + "\"stat_t\":\"" + PARK_TIMER_STATE_TOPIC + "\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + "\"options\":[\"30 min\",\"1 h\",\"2 h\",\"3 h\",\"4 h\",\"5 h\",\"Ilimitado\"],"
            + "\"ic\":\"mdi:timer-outline\"," + dev
            + "}";
        pub("homeassistant/select/" + DEV_ID + "/park_timer/config", sel, true);
    }

    // versionName is human ("20260808-1421"), versionCode is the monotonic one
    // build.sh injects (minutes since the epoch) and is what actually decides
    // whether an install is a downgrade. Both, because the first is readable and
    // the second is the one that can be COMPARED against what was pushed.
    private String versionJson() {
        String name = "?"; long code = -1;
        try {
            android.content.pm.PackageInfo pi =
                ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            name = pi.versionName;
            code = pi.getLongVersionCode();   // minSdk is 28; no legacy branch needed
        } catch (Throwable t) { Log.w(TAG, "version: " + t); }
        return "{\"name\":\"" + name + "\",\"code\":" + code + "}";
    }

    // "Atualizar Drive Assist" button + update status sensor
    private void sendUpdateDiscovery() {
        String dev = getDeviceJson();
        String btn = "{"
            + "\"name\":\"Atualizar Drive Assist\","
            + "\"uniq_id\":\"" + DEV_ID + "_update\","
            + "\"cmd_t\":\"" + UPDATE_CMD_TOPIC + "\","
            + "\"payload_press\":\"go\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + "\"ic\":\"mdi:download\"," + dev
            + "}";
        pub("homeassistant/button/" + DEV_ID + "/update/config", btn, true);
        String sen = "{"
            + "\"name\":\"Atualização Drive Assist\","
            + "\"uniq_id\":\"" + DEV_ID + "_update_state\","
            + "\"stat_t\":\"" + UPDATE_STATE_TOPIC + "\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + "\"ic\":\"mdi:sync\"," + dev
            + "}";
        pub("homeassistant/sensor/" + DEV_ID + "/update_state/config", sen, true);
        // No avty_t on purpose: the whole value of this sensor is being readable
        // while the car is asleep, which is exactly when HA would grey out an
        // availability-gated entity.
        String ver = "{"
            + "\"name\":\"Versão Drive Assist\","
            + "\"uniq_id\":\"" + DEV_ID + "_version\","
            + "\"stat_t\":\"" + VERSION_TOPIC + "\","
            + "\"val_tpl\":\"{{ value_json.name }}\","
            + "\"json_attr_t\":\"" + VERSION_TOPIC + "\","
            + "\"ic\":\"mdi:tag-outline\"," + dev
            + "}";
        pub("homeassistant/sensor/" + DEV_ID + "/version/config", ver, true);

        // adb, as a switch. `pl_on` carries no window because the default in
        // AdbControl is the one we want; publish "on:30" by hand for a longer
        // one. avty_t on purpose: a switch you can flip at a car that is not
        // listening would report a state that nothing is maintaining.
        String adb = "{"
            + "\"name\":\"adb\","
            + "\"uniq_id\":\"" + DEV_ID + "_adb\","
            + "\"cmd_t\":\"" + ADB_CMD_TOPIC + "\","
            + "\"stat_t\":\"" + ADB_STATE_TOPIC + "\","
            + "\"pl_on\":\"on\",\"pl_off\":\"off\","
            + "\"stat_on\":\"ON\",\"stat_off\":\"OFF\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + "\"ic\":\"mdi:usb-flash-drive\"," + dev
            + "}";
        pub("homeassistant/switch/" + DEV_ID + "/adb/config", adb, true);

        // Reverse control, as two buttons and a readout. avty_t on the buttons:
        // pressing them at a car that is not listening should look disabled, not
        // silently do nothing.
        for (String[] b : new String[][]{
                {"cool", "Mais frio",   "mdi:snowflake"},
                {"warm", "Mais quente", "mdi:fire"}}) {
            String j = "{"
                + "\"name\":\"" + b[1] + "\","
                + "\"uniq_id\":\"" + DEV_ID + "_act_" + b[0] + "\","
                + "\"cmd_t\":\"" + ACTION_CMD_TOPIC + "\","
                + "\"payload_press\":\"" + b[0] + "\","
                + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
                + "\"ic\":\"" + b[2] + "\"," + dev
                + "}";
            pub("homeassistant/button/" + DEV_ID + "/act_" + b[0] + "/config", j, true);
        }
        String cmf = "{"
            + "\"name\":\"Conforto\","
            + "\"uniq_id\":\"" + DEV_ID + "_comfort\","
            + "\"stat_t\":\"" + COMFORT_STATE_TOPIC + "\","
            + "\"val_tpl\":\"{{ value_json.status }}\","
            + "\"json_attr_t\":\"" + COMFORT_STATE_TOPIC + "\","
            + "\"ic\":\"mdi:thermostat\"," + dev
            + "}";
        pub("homeassistant/sensor/" + DEV_ID + "/comfort/config", cmf, true);
    }

    private void sendChargeNumberDiscovery() {
        String j = "{"
            + "\"name\":\"Limite de corrente de carga\","
            + "\"uniq_id\":\"" + DEV_ID + "_charge_limit\","
            + "\"cmd_t\":\"" + CHARGE_CMD_TOPIC + "\","
            + "\"stat_t\":\"" + CHARGE_LIMIT_STATE_TOPIC + "\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + "\"min\":" + CarAccess.CHARGE_MIN_A + ",\"max\":" + CarAccess.CHARGE_MAX_A + ","
            + "\"step\":1,\"unit_of_meas\":\"A\",\"mode\":\"slider\","
            + getDeviceJson()
            + "}";
        pub("homeassistant/number/" + DEV_ID + "/charge_limit/config", j, true);
    }

    private void sendChargeSwitchDiscovery() {
        String j = "{"
            + "\"name\":\"Carregamento\","
            + "\"uniq_id\":\"" + DEV_ID + "_charging\","
            + "\"cmd_t\":\"" + CHARGE_SW_CMD_TOPIC + "\","
            + "\"stat_t\":\"" + CHARGE_SW_STATE_TOPIC + "\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + "\"pl_on\":\"ON\",\"pl_off\":\"OFF\",\"ic\":\"mdi:ev-station\","
            + getDeviceJson()
            + "}";
        pub("homeassistant/switch/" + DEV_ID + "/charging/config", j, true);
    }

    // Read-only, no command topic — unlike the charging switch, there is
    // nothing to write here, just a value already riding along in the same
    // STATE_TOPIC JSON every telemetry tick (Telemetry.java's plug_connected).
    private void sendPlugDiscovery() {
        String j = "{"
            + "\"name\":\"Cabo conectado\","
            + "\"uniq_id\":\"" + DEV_ID + "_plug_connected\","
            + "\"stat_t\":\"" + STATE_TOPIC + "\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + "\"val_tpl\":\"{{ value_json.plug_connected }}\","
            + "\"pl_on\":\"1\",\"pl_off\":\"0\",\"ic\":\"mdi:ev-plug-type2\","
            + getDeviceJson()
            + "}";
        pub("homeassistant/binary_sensor/" + DEV_ID + "/plug_connected/config", j, true);
    }

    private void sendLightDiscovery() {
        String cfg = "{"
            + "\"name\":\"Luz ambiente\",\"uniq_id\":\"" + DEV_ID + "_light\","
            + "\"schema\":\"json\",\"supported_color_modes\":[\"rgb\"],\"brightness\":true,"
            + "\"cmd_t\":\"" + LIGHT_CMD_TOPIC + "\","
            + "\"stat_t\":\"" + LIGHT_STATE_TOPIC + "\","
            + "\"avty_t\":\"" + AVAIL_TOPIC + "\","
            + getDeviceJson()
            + "}";
        pub("homeassistant/light/" + DEV_ID + "/ambient/config", cfg, true);
        try { if (ctx != null) pub(LIGHT_STATE_TOPIC, lightStateJson(), true); }
        catch (Throwable ignored) {}
    }
}
