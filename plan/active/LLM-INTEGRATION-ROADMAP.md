# LLM integration (BYOK assistant and AI tasks) — idea, not started

Status: **idea / exploration only**. Noted 2026-11-23. Nothing below is built
or committed to a release.

Inspired by Home Assistant's LLM integration, which splits cleanly into two
patterns:

1. **Tool-calling assistant (HA Assist):** natural-language requests that read
   live/historical state and call safe, narrow built-in tools.
2. **Background AI tasks (HA `ai_task`):** event-driven summaries,
   classifications, and structured extraction without needing a chat UI on the
   car screen.

Drive Assist already has the pieces this builds on: a single cached state bus
(`CarDataHub` / `EntityBus`), rich SQLite history (`CarDb`), clear control
boundaries (`docs/WELCOME.md`), a local phone web UI (`PhoneServer`), and a
voice roadmap (`VOICE-ASSISTANT-ROADMAP.md`).

---

## 1. Interactive tool-calling (Assist-style)

The user asks a question or states an intent; the model calls narrow,
predefined tools instead of writing raw code or raw SQL.

- **Fuzzy and multi-step cabin comfort:** Handle composite intent like *"it's
  humid and fogging up"* or *"cool the cabin down fast"* by coordinating
  `ComfortHub`, `FrontDefrost`, recirculation, `Purge` (halfway windows),
  `AmbientLight`, and night dimming (`ExtraDark`).
- **Natural-language Q&A over `CarDb`:** Safe, parameterized read tools over
  `daily_stat`, `trip`, `charge_session`, `battery_health_reading`, and
  `telemetry_sample`:
  - *"How much did I spend charging this month vs. last month?"*
  - *"Why did today's drive use more energy than usual?"* (correlates
    `ascent_m`, `climate_kw`, `outside_temp_c`, speed buckets, and drive mode)
  - *"What is my real highway range at 110 km/h on cold days?"*
  - *"How has my battery SoH trended over the last 10,000 km?"*
- **Trip feasibility / range sanity checks:** *"Can I drive 140 km with 600 m
  of climb on my current battery?"* — combines live SoC, battery temperature,
  and historical elevation/speed consumption rather than relying only on the
  cluster estimate.
- **Parking and charging orchestration:**
  - *"I'm popping into the store for 20 minutes, keep the AC on"* -> enables
    `ParkingState` with a 20-minute timer and holds comfort.
  - *"Charge slower so it doesn't trip the garage breaker"* -> lowers AC charge
    current limit (5–32 A) or stops charging.
- **Dashcam and valet queries:**
  - *"Save that clip and tag it 'near miss at roundabout'"* -> triggers
    `DashRecorder.saveCurrentSegmentForEvent()` and stores a searchable note.
  - *"What did the valet do with the car?"* -> summarizes `ValetSession`
    (distance, peak speed, duration, anomalies).
- **Voice/text to structured log entries:** Pairs with `ChargeCostDialog` and
  the planned `car-diary-maintenance-log.md` — *"Log R$42 for that charge"* or
  *"Replaced the cabin air filter today for R$120"* -> extracts structured
  fields and asks for one-tap confirmation before writing.

---

## 2. Background AI tasks (zero-typing, event-driven)

Typing on a head unit is only viable in Park, so background tasks that surface
results as short cards or Home Assistant notifications may deliver more
everyday value than interactive chat:

- **Trip / daily / weekly digest:** A 1–2 sentence plain-language summary on
  the stats screen or phone view comparing efficiency, regen, weather, and HVAC
  load against recent baselines.
- **Parked-monitoring vision triage (HA LLM Vision pattern):** When
  `ParkedMonitorPolicy` captures a motion event while parked (especially on
  home Wi-Fi), send 1–3 keyframes to a multimodal model to classify what
  triggered it (*"delivery van passed on the left"* vs. *"person stood near
  driver door"*) and forward the summary and thumbnail to Home Assistant or the
  phone UI.
- **Plain-language anomaly explanations:** Explain unexpected
  `charge_stop_event` interruptions, `TireWarning` alerts, or sudden efficiency
  / battery-temperature shifts using the surrounding telemetry samples.
- **Comfort Ruler tuning advisor:** Inspect `comfort_event` / `ComfortLearn`
  overrides (*"you consistently nudge warmer around 18–21 °C outside"*) and
  suggest specific curve adjustments.

---

## 3. Provider layer (BYOK + local)

- **OpenAI-compatible HTTP client:** One provider abstraction with user-supplied
  **Base URL**, **API key**, and **Model** covers OpenAI, Gemini (OpenAI
  compatibility endpoint), OpenRouter, Groq, and **self-hosted Ollama / LM
  Studio on home Wi-Fi** (zero cloud, ideal for nightly digests and parked-clip
  vision).
- **Secret handling:** Follows the `type: secret` direction in
  `INTEGRATIONS-DSL-ROADMAP.md` — masked in UI, never logged, and excluded from
  unencrypted exports.
- **Offline resilience:** The car has intermittent connectivity. Every core
  Drive Assist feature continues to work identically without an LLM configured
  or reachable; background tasks queue or skip cleanly when offline.

---

## 4. Safety, privacy, and tool exposure

Modeled on Home Assistant's per-entity exposure switches and Drive Assist's
existing invariants in `docs/WELCOME.md`:

### Tool permission tiers

| Tier | Scope | Examples | Policy |
| --- | --- | --- | --- |
| **0 — Read-only** | Live telemetry and parameterized `CarDb` queries | SoC, range, trips, charges, SoH, valet summary | Safe in any gear; opt-in by category |
| **1 — Cabin comfort** | Non-hazardous cabin and dashcam actions | Climate ruler, front/rear defrost, recirc, ambient light, dark overlay, save dashcam clip | Safe while driving; user toggle to allow control |
| **2 — Confirm / Park-only** | Exterior movement, charging, timers, DB writes | Window purge, charging start/stop/amps, parking timer, smart gate, charge cost / diary write | Require explicit confirmation (and Park where applicable) |
| **Blocked** | Safety-critical and privileged controls | AEB, AVAS, drive mode / regen changes, ADB, OTA updates, raw SQL | **Never exposed as tools** |

### Privacy boundaries

- Separate opt-in switches per sensitive data class — at minimum **location /
  routes** (matching ABRP's separate location switch) and **camera frames** —
  so users can enable telemetry summaries or climate control without sending
  GPS coordinates or dashcam images to an external provider.
- Show clearly in Config what data a selected feature sends and where it goes,
  consistent with `docs/WELCOME.md`.

---

## 5. Interaction surfaces

1. **Automatic digests & one-tap buttons** on the Stats / Home screens (no
   typing, immediately usable).
2. **Phone Web UI (`PhoneServer`)** for deeper chat/Q&A over stats while parked
   or from a phone on the local network.
3. **Voice** as Phase 5 of `VOICE-ASSISTANT-ROADMAP.md` — either local STT
   feeding the tool-calling loop, or optional push-to-talk audio directly to a
   multimodal audio model.

---

## Open questions

- **First vertical slice:** Start with background stats digests (lowest risk,
  no control surface), phone-UI stats Q&A, or push-to-talk cabin control?
- **Home Assistant overlap:** When Home Assistant is already connected, should
  Drive Assist call its own configured LLM directly, optionally proxy through
  HA's Assist / conversation API, or support both?
- **Token / cost guardrails:** Daily request caps, Wi-Fi-only option for vision
  tasks, and compact JSON snapshots to keep latency and token cost low.
- **Prompt injection / untrusted inputs:** Music metadata (`MusicState`),
  lyrics, or Home Assistant panel strings should be treated as untrusted data
  and never concatenated into system instructions.
