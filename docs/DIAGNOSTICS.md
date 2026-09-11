# Diagnostic Logging & Telemetry Architecture

Architecture specification for structured diagnostic event logging, local storage rotation, and secure remote offloading on the Geely IHU629G.

---

## 1. System Overview & Purpose

Drive Assist operates in constrained automotive environments where interactive terminal debugging is not continuously available. The diagnostic logging subsystem provides structured, persistent recording of vehicle bus interactions, actor queue operations, connectivity state transitions, and unhandled exceptions.

```text
┌────────────────────────────────────────────────────────┐
│ Application Subsystems                                 │
│                                                        │
│  CarActor          MqttReporter      WatchdogServices  │
│  (Queue/Writes)    (Broker State)    (Process Beats)   │
└────────────┬─────────────┬─────────────────┬───────────┘
             │             │                 │
             └─────────────┼─────────────────┘
                           ▼
                 ┌──────────────────┐
                 │  DriveLog.event  │
                 └─────────┬────────┘
                           │
             ┌─────────────┴─────────────┐
             ▼                           ▼
      Android Logcat          Circular Log Buffer
      (Streamed via adb)      (/files/logs/diag.log)
                                         │
                                         ▼ HTTP POST
                              ┌────────────────────┐
                              │ Configured Endpoint│
                              │ (Home Assistant)   │
                              └────────────────────┘
```

---

## 2. Event Schema & Captured Subsystems

Log entries capture critical operational events without duplicating verbose Android framework logcat output:

1. **`CarActor` Cast Transactions**: Entity identifier, requested target value, applied status, rejection reason (if rejected), and queue latency in milliseconds.
2. **Missing Property Reads**: VHAL property reads returning `null` or unexpected empty parcels.
3. **MQTT State Transitions**: Broker connection, disconnection, TLS handshake failures, and reconnect backoff intervals.
4. **Command Lock Toggles**: Driver state changes for the remote command acceptance lock.
5. **Watchdog Restarts**: Component heartbeat expirations and automated service restarts.
6. **Uncaught Exceptions**: Full stack traces for unhandled runtime exceptions.

### Log Entry Format

Entries use ISO 8601 timestamps, uppercase classification tags, and key-value attributes:

```text
2026-09-02T18:08:39.476-03:00 CAST ambient_brightness req=0 applied=true value=0.0 latency_ms=12
2026-09-02T18:08:39.470-03:00 MQTT_CMD light state=OFF
2026-09-02T18:03:10.002-03:00 WATCHDOG restart TelemetryService reason=stale_beat
```

---

## 3. Storage & Buffer Rotation

Log files are stored in app-specific external storage:

```text
/sdcard/Android/data/com.geely.drivemem/files/logs/
```

* **Permission Independence**: Access requires no runtime storage permissions (`READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`) on Android 9 (API 28).
* **Ring Buffer Rotation**: The active log file (`diag.log`) is capped at **512 KB**. When the threshold is reached, it rolls over to `diag.log.1` and `diag.log.2`, maintaining a maximum of 3 generations (~1.5 MB total footprint).

---

## 4. Extraction & Remote Offloading

To operate on head units lacking standard Android share sheets, cloud accounts, or email clients, diagnostic logs are exported directly via HTTP.

### HTTP POST Dispatch

1. **Configuration**: A user-specified endpoint is defined via `diag_upload_url` in application settings.
2. **Manual Transmission**: A "Send Diagnostic Log" action in Settings triggers an asynchronous background `HttpURLConnection` POST payload containing the raw log bytes.
3. **Self-Hosted Integration**: By default, the endpoint is configured to target a private Home Assistant Webhook:
   ```text
   https://<ha-server-address>/api/webhook/<secret-webhook-id>
   ```
   An automation in Home Assistant writes the received payload directly to storage without external cloud intermediaries.

### Security & Privacy Guarantees

* **Zero Third-Party SDKs**: No proprietary analytics, crash tracking, or telemetry SDKs are embedded.
* **No Centralized Servers**: Telemetry and logs are transmitted exclusively to infrastructure owned and configured by the vehicle operator.
