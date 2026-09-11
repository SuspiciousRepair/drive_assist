# Status Bar Icon Integration (`flag_status_icon_*`)

Technical specification for the native status bar icon injection mechanism on the Geely IHU629G. Explains notification extras contracts, layout ranking, collision resolution, and reserved slot allocation.

---

## 1. Subsystem Architecture

The factory status bar on the IHU629G provides a proprietary notification-driven API allowing background services to render dynamic bitmap icons directly inside the native status bar panel (positioned to the left of the system clock).

```text
┌──────────────────────────────────────────────┐
│ Drive Assist Background Services             │
│ (OutTempService, SocIconService, WifiService)│
└──────────────────────┬───────────────────────┘
                       │ Notification + flag_status_icon_* Extras
                       ▼
┌──────────────────────────────────────────────┐
│ com.flyme.auto.systemuiplugin                │
│ (/system/app/GeelyAutoSystemUIPlugin.apk)    │
│                                              │
│  ┌───────────────────────┐                   │
│  │   StatusIconManager   │ Parses extras into StatusIconInfo
│  └───────────┬───────────┘                   │
│              │                               │
│              ▼                               │
│  ┌───────────────────────┐                   │
│  │StatusIconPositionMgr  │ Evaluates allowToInsert(spaceX, rank)
│  └───────────┬───────────┘                   │
│              │                               │
│              ▼                               │
│  Native Status Bar Rendering Canvas          │
└──────────────────────────────────────────────┘
```

---

## 2. Notification Extras Contract

An ongoing notification posted to `NotificationManager` must populate the following bundle extras:

```java
Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID);
Bundle ex = new Bundle();

ex.putBoolean("flag_status_icon_notification", true);
ex.putInt("flag_status_icon_id", iconId);                 // Stable unique identifier
ex.putString("flag_status_icon_describe", label);         // Accessibility description
ex.putParcelable("flag_status_icon_icon", iconBitmap);     // Normal state bitmap
ex.putParcelable("flag_status_icon_pressed_icon", iconBitmap); // Pressed state bitmap
ex.putBoolean("flag_status_icon_hide", false);
ex.putInt("flag_status_icon_rank", rankPosition);         // Left-to-right sorting rank
ex.putInt("flag_status_icon_space_x", 1);                 // Slot width in cells
ex.putBoolean("flag_status_icon_is_pick_on", false);
ex.putInt("flag_status_icon_specific_width", 0);          // 0 = automatic cell sizing

builder.setExtras(ex);
```

### Invariants & Updating

* **Persistent Identity (`id`)**: Posting a new notification with an identical `flag_status_icon_id` updates the existing icon in-place without triggering layout repositioning.
* **Layout Positioning (`rank`)**: Determines horizontal placement from left to right. Lower rank numbers appear further to the left.

---

## 3. Positioning Logic & Collision Resolution

The OEM plugin strictly gates new icon registration through `StatusIconPositionManager.allowToInsert(spaceX, rank)`:

1. **Identifier Independence**: `StatusIconManager.crateAppStatusIconInfo()` performs no validation or filtering on `flag_status_icon_id`; any integer value is accepted.
2. **Rank Collision Dropping**: If a notification requests a `rank` that is already registered or reserved by another component, `allowToInsert()` returns `false`. The notification is accepted by Android but the icon is silently omitted from the status bar with no visible error.
3. **Reserved System Slots**: `StatusIconManager.initialize()` pre-allocates fixed rank assignments (`mIdRankMap`) for factory functions:

| Function ID | Reserved Rank | System Component |
|---|---|---|
| `7`, `102` | `0` | CarPlay |
| `19`, `18`, `103` | `1` | Android Auto |
| `100` | `2` | WLAN Workshop Mode |
| `101`, `3` | `3` | Trailer Mode / FlymeLink |
| `6` | `4` | System Reserved |
| `8`, `22` | `5` | Bluetooth |
| `20` | `6` | System Reserved |
| `16` | `7` | Master Volume |
| `21` | `8` | System Clock |
| `9` | `11` | Notifications Indicator |
| `23` | `20` | Wi-Fi Hotspot |
| `14` | `21` | System Reserved |
| `2` | `24` | High-Voltage Charging |
| `5` | `25` | Security Authority |
| `4` | `26` | USB Connection |

---

## 4. Drive Assist Slot Allocations

To prevent collisions with factory status icons, Drive Assist services bind to verified unreserved rank slots:

| Service | Status Icon ID | Configured Rank | Displayed Information |
|---|---|---|---|
| **`OutTempService`** | `15` | `22` | Outside ambient temperature (°C) |
| **`SocIconService`** | `17` | `23` | Battery State of Charge (%) |
| **`WifiIconService`** | `98` | `31` | Wi-Fi connection / IP state |

### Expansion Allocations

When registering additional status icons, select unoccupied ranks outside the OEM reserved ranges. As of current firmware, ranks **27, 28, 29, and 30** are unassigned.

---

## 5. UI Integration & Display Settings

The status bar icons operate in harmony with Drive Assist's comprehensive telemetry, drive modes, and visual customization dashboard:

![Drive Assist Telemetry & Settings View](screenshots/telemetry-settings.png)
*Figure 1: Drive Assist Telemetry & Settings View running live on the Geely IHU629G (1920x1080 display). Demonstrates the integrated status bar presentation, side menu dock, visual appearance controls (`VISUAL: Barra de menu, Aparência`), drive mode selection (`CARRO: Eco, Comfort, Sport`), regenerative braking intensity toggles (`Fraca, Média, Forte`), Turbo boost configuration (15s duration slider), and external service dashboards (`INTEGRAÇÕES: MQTT, OBD2/ABRP, Spotify`).*

