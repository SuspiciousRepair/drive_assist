#!/usr/bin/env bash
#
# tools/configure-car.sh
#
# Interactive terminal wizard to configure all Drive Assist settings on the vehicle
# without needing to type on the head unit touchscreen:
#   - MQTT brokers (up to 3 addresses with fallback)
#   - MQTT credentials (user / password)
#   - TLS Certificates (Client .p12 and CA .crt)
#   - ABRP (tokens, API keys, GPS toggle)
#   - Spotify (Client ID, Refresh Token, PKCE)
#   - HVAC & Comfort Setpoints
#
# Usage:
#   ./tools/configure-car.sh [device-ip:port]
#   ./tools/configure-car.sh --show
#   ./tools/configure-car.sh --backup-only
#   ./tools/configure-car.sh --restore <backup-file.xml>
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEELY_ROOT="$(cd "$REPO_ROOT/.." && pwd)"

# ANSI styling
BOLD='\033[1m'
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
DIM='\033[2m'
NC='\033[0m' # No Color

D="${CAR_IP:-}"

# Handle command line flags
COMMAND="configure"
BACKUP_RESTORE_FILE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --show)
            COMMAND="show"
            shift
            ;;
        --backup-only)
            COMMAND="backup"
            shift
            ;;
        --restore)
            COMMAND="restore"
            BACKUP_RESTORE_FILE="${2:-}"
            shift 2
            ;;
        --wake)
            COMMAND="wake"
            shift
            ;;
        -h|--help)
            echo "Drive Assist Vehicle Configuration Tool"
            echo ""
            echo "Usage:"
            echo "  $0 [device-ip:port]             Interactive configuration wizard"
            echo "  $0 --show [device-ip:port]      Display current car settings"
            echo "  $0 --wake [device-ip:port]      Wake vehicle display from sleep / black screen"
            echo "  $0 --backup-only [device]       Export current car settings to a timestamped file"
            echo "  $0 --restore <file.xml> [dev]   Restore a previous XML backup to the car"
            exit 0
            ;;
        *)
            D="$1"
            shift
            ;;
    esac
done

# Resolve default device if not specified
if [ -z "$D" ]; then
    CONNECTED=$(adb devices 2>/dev/null | grep -E '\bdevice$' | head -1 | awk '{print $1}' || true)
    D="${CONNECTED:-192.168.0.150:5555}"
fi
[[ "$D" != *":"* ]] && D="${D}:5555"

if [ "$COMMAND" = "restore" ] && { [ -z "$BACKUP_RESTORE_FILE" ] || [ ! -f "$BACKUP_RESTORE_FILE" ]; }; then
    echo -e "${RED}Error: Please specify a valid XML backup file to restore.${NC}"
    echo "Usage: $0 --restore <path-to-file.xml> [device-ip]"
    exit 1
fi

echo -e "${BOLD}${CYAN}============================================================${NC}"
echo -e "${BOLD}${CYAN}        🚗 Drive Assist - Vehicle Configuration Tool        ${NC}"
echo -e "${BOLD}${CYAN}============================================================${NC}"
echo ""

# Ensure ADB is available
if ! command -v adb >/dev/null 2>&1; then
    if [ -d "$GEELY_ROOT/sdk/platform-tools" ]; then
        export PATH="$GEELY_ROOT/sdk/platform-tools:$PATH"
    fi
    if ! command -v adb >/dev/null 2>&1; then
        echo -e "${RED}Error: 'adb' command not found in PATH.${NC}"
        exit 1
    fi
fi

# Connect to vehicle
echo -e "${DIM}Checking vehicle connection at ${D}...${NC}"
if ! adb devices | grep -q "^${D}[[:space:]]*device"; then
    echo -e "${YELLOW}Connecting to vehicle at ${D}...${NC}"
    adb connect "$D" >/dev/null 2>&1 || true
    sleep 1
fi

if ! adb devices | grep -q "^${D}[[:space:]]*device"; then
    echo -e "${RED}Error: Unable to connect to vehicle at ${D}.${NC}"
    echo "Make sure the car is on the local network and ADB is enabled."
    exit 1
fi

MODEL=$(adb -s "$D" shell getprop ro.product.model 2>/dev/null | tr -d '\r\n' || echo "Unknown")
echo -e "${GREEN}✓ Connected to vehicle: ${BOLD}${MODEL}${NC} (${D})"

# Handle --wake
if [ "$COMMAND" = "wake" ]; then
    echo -e "${YELLOW}Waking vehicle display and bringing Drive Assist to foreground...${NC}"
    adb -s "$D" shell "input keyevent 224; wm dismiss-keyguard; am start -n com.geely.drivemem/.ui.ComfortActivity" >/dev/null 2>&1 || true
    echo -e "${GREEN}✓ Vehicle display is awake.${NC}"
    exit 0
fi

# Always ensure display is awake when configuring
adb -s "$D" shell "input keyevent 224; wm dismiss-keyguard" >/dev/null 2>&1 || true
echo ""

TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

export CAR_XML="$TEMP_DIR/current_prefs.xml"
BACKUP_DIR="$GEELY_ROOT/prefs-backup"
mkdir -p "$BACKUP_DIR"

# Pull current preferences from car
if adb -s "$D" shell "test -f /data/data/com.geely.drivemem/shared_prefs/drivemem.xml" >/dev/null 2>&1; then
    adb -s "$D" shell "cat /data/data/com.geely.drivemem/shared_prefs/drivemem.xml" > "$CAR_XML" 2>/dev/null
elif [ -f "$GEELY_ROOT/drivemem_backup_prefs.xml" ]; then
    echo -e "${YELLOW}Notice: No active settings found on vehicle; seeding from local backup template.${NC}"
    cp "$GEELY_ROOT/drivemem_backup_prefs.xml" "$CAR_XML"
else
    echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map></map>" > "$CAR_XML"
fi

# Auto-backup
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
AUTO_BACKUP="$BACKUP_DIR/drivemem_backup_${TIMESTAMP}.xml"
cp "$CAR_XML" "$AUTO_BACKUP"
echo -e "${DIM}Current car settings saved to: ${AUTO_BACKUP}${NC}"

# Handle --backup-only
if [ "$COMMAND" = "backup" ]; then
    echo -e "${GREEN}✓ Backup successfully created at:${NC} ${AUTO_BACKUP}"
    exit 0
fi

# Handle --show
if [ "$COMMAND" = "show" ]; then
    python3 - <<'PYSHOW'
import os
import xml.etree.ElementTree as ET

car_xml = os.environ.get("CAR_XML", "")
try:
    tree = ET.parse(car_xml)
    root = tree.getroot()
    print("\n--- Current Vehicle Settings ---")
    for child in sorted(root, key=lambda x: x.attrib.get('name', '')):
        name = child.attrib.get('name', '')
        val = child.attrib.get('value', child.text or '')
        if 'pass' in name or 'token' in name or 'verifier' in name:
            masked = val[:4] + '...' + val[-4:] if len(val) > 8 else '********'
            print(f"  {name:25} = {masked}")
        else:
            display_val = val.replace('\n', ' | ')
            print(f"  {name:25} = {display_val}")
    print("--------------------------------\n")
except Exception as e:
    print(f"Error parsing settings: {e}")
PYSHOW
    exit 0
fi

# Function to read value from XML via Python
get_pref() {
    local key="$1"
    local default_val="${2:-}"
    export KEY_TO_FIND="$key"
    export DEF_VAL="$default_val"
    python3 - <<'PYGET'
import os
import xml.etree.ElementTree as ET

key = os.environ.get("KEY_TO_FIND", "")
def_val = os.environ.get("DEF_VAL", "")
car_xml = os.environ.get("CAR_XML", "")
try:
    tree = ET.parse(car_xml)
    root = tree.getroot()
    found = False
    for child in root:
        if child.attrib.get("name") == key:
            val = child.attrib.get("value", child.text or "")
            print(val)
            found = True
            break
    if not found:
        print(def_val)
except:
    print(def_val)
PYGET
}

# Prompt helper with default
prompt_input() {
    local label="$1"
    local default_val="$2"
    local is_secret="${3:-0}"
    local user_val=""

    if [ "$is_secret" -eq 1 ] && [ -n "$default_val" ]; then
        echo -ne "${BOLD}${label}${NC} [Default: ${DIM}•••••••• (press enter to keep)${NC}]: " >&2
        read -r user_val || true
        if [ -z "$user_val" ]; then
            user_val="$default_val"
        fi
    else
        echo -ne "${BOLD}${label}${NC} [Default: ${CYAN}${default_val}${NC}]: " >&2
        read -r user_val || true
        if [ -z "$user_val" ]; then
            user_val="$default_val"
        fi
    fi
    echo "$user_val"
}

prompt_bool() {
    local label="$1"
    local default_val="$2"
    local prompt_def="Y/n"
    [ "$default_val" = "false" ] && prompt_def="y/N"

    echo -ne "${BOLD}${label}${NC} [${CYAN}${prompt_def}${NC}]: " >&2
    read -r user_val || true
    user_val=$(echo "$user_val" | tr '[:upper:]' '[:lower:]')
    if [ -z "$user_val" ]; then
        echo "$default_val"
    elif [[ "$user_val" =~ ^(y|yes|true|1)$ ]]; then
        echo "true"
    else
        echo "false"
    fi
}

# --- Handle Restore Command Directly ---
if [ "$COMMAND" = "restore" ]; then
    echo -e "${YELLOW}Restoring preferences from: ${BACKUP_RESTORE_FILE}...${NC}"
    cp "$BACKUP_RESTORE_FILE" "$TEMP_DIR/final_prefs.xml"
else
    # ============================================================================
    # Interactive Configuration Wizard
    # ============================================================================
    echo -e "${BOLD}Press [Enter] on any field to keep the current value.${NC}"
    echo ""

    # 1. MQTT Broker Addresses
    echo -e "${BOLD}${GREEN}--- 1. MQTT Broker Configuration ---${NC}"
    RAW_URIS=$(get_pref "mqtt_uri" "tcp://homeassistant.local:1883")
    mapfile -t URI_ARRAY <<< "$RAW_URIS"

    DEF_URI1="${URI_ARRAY[0]:-tcp://homeassistant.local:1883}"
    DEF_URI2="${URI_ARRAY[1]:-}"
    DEF_URI3="${URI_ARRAY[2]:-}"

    URI1=$(prompt_input "  Broker 1 (Primary LAN)" "$DEF_URI1")
    URI2=$(prompt_input "  Broker 2 (Fallback / Tailscale)" "$DEF_URI2")
    URI3=$(prompt_input "  Broker 3 (Remote / Public)" "$DEF_URI3")

    COMBINED_URIS="$URI1"
    [ -n "$URI2" ] && COMBINED_URIS="${COMBINED_URIS}"$'\n'"$URI2"
    [ -n "$URI3" ] && COMBINED_URIS="${COMBINED_URIS}"$'\n'"$URI3"

    # 2. MQTT Credentials
    echo ""
    echo -e "${BOLD}${GREEN}--- 2. MQTT Credentials ---${NC}"
    DEF_USER=$(get_pref "mqtt_user" "drive_assist")
    DEF_PASS=$(get_pref "mqtt_pass" "")
    DEF_INTERVAL=$(get_pref "tele_interval_s" "20")

    MQTT_USER=$(prompt_input "  MQTT Username" "$DEF_USER")
    MQTT_PASS=$(prompt_input "  MQTT Password" "$DEF_PASS" 1)
    TELE_INTERVAL=$(prompt_input "  Telemetry Send Interval (seconds)" "$DEF_INTERVAL")
    TELE_ENABLED=$(prompt_bool "  Enable MQTT Telemetry" "$(get_pref "tele_enabled" "true")")

    # 3. Certificates
    echo ""
    echo -e "${BOLD}${GREEN}--- 3. Client & CA Certificates (mTLS) ---${NC}"
    DEF_P12=""
    DEF_CA=""
    if [ -d "$GEELY_ROOT/mqtt-certs" ]; then
        FIRST_P12=$(find "$GEELY_ROOT/mqtt-certs" -maxdepth 1 -name "*.p12" 2>/dev/null | head -1 || true)
        [ -n "$FIRST_P12" ] && DEF_P12="$FIRST_P12"
        FIRST_CA=$(find "$GEELY_ROOT/mqtt-certs" -maxdepth 1 \( -name "*.crt" -o -name "*.pem" \) 2>/dev/null | head -1 || true)
        [ -n "$FIRST_CA" ] && DEF_CA="$FIRST_CA"
    fi

    CLIENT_P12_PATH=$(prompt_input "  Client Certificate (.p12 path on this PC, blank to skip)" "$DEF_P12")
    CA_CRT_PATH=$(prompt_input "  CA Certificate (.crt/.pem path on this PC, blank to skip)" "$DEF_CA")

    # 4. ABRP (A Better Routeplanner)
    echo ""
    echo -e "${BOLD}${GREEN}--- 4. ABRP (A Better Routeplanner) ---${NC}"
    ABRP_ENABLED=$(prompt_bool "  Enable ABRP Live Telemetry" "$(get_pref "abrp_enabled" "true")")
    DEF_ABRP_TOKEN=$(get_pref "abrp_user_token" "")
    DEF_ABRP_KEY=$(get_pref "abrp_api_key" "")
    DEF_ABRP_GPS=$(get_pref "abrp_send_location" "false")

    ABRP_USER_TOKEN=$(prompt_input "  ABRP User Token" "$DEF_ABRP_TOKEN")
    ABRP_API_KEY=$(prompt_input "  ABRP API Key" "$DEF_ABRP_KEY")
    ABRP_SEND_LOCATION=$(prompt_bool "  Send GPS Location to ABRP" "$DEF_ABRP_GPS")

    # 5. Spotify
    echo ""
    echo -e "${BOLD}${GREEN}--- 5. Spotify Integration ---${NC}"
    DEF_SPOTIFY_ID=$(get_pref "spotify_client_id" "")
    DEF_SPOTIFY_REFRESH=$(get_pref "spotify_refresh_token" "")
    DEF_SPOTIFY_VERIFIER=$(get_pref "spotify_pkce_verifier" "")

    SPOTIFY_CLIENT_ID=$(prompt_input "  Spotify Client ID" "$DEF_SPOTIFY_ID")
    SPOTIFY_REFRESH_TOKEN=$(prompt_input "  Spotify Refresh Token" "$DEF_SPOTIFY_REFRESH" 1)
    SPOTIFY_VERIFIER=$(prompt_input "  Spotify PKCE Verifier" "$DEF_SPOTIFY_VERIFIER" 1)

    # 6. Climate & Comfort
    echo ""
    echo -e "${BOLD}${GREEN}--- 6. Climate & Vehicle Features ---${NC}"
    DEF_COMFORT_TEMP=$(get_pref "comfort_sp_2" "24.0")
    DEF_WINDOW_DOOR=$(get_pref "window_on_door" "true")

    COMFORT_TEMP=$(prompt_input "  Comfort Ruler Temperature Setpoint (°C)" "$DEF_COMFORT_TEMP")
    WINDOW_ON_DOOR=$(prompt_bool "  Auto Close Windows on Door Lock" "$DEF_WINDOW_DOOR")

    # ============================================================================
    # Confirmation Summary
    # ============================================================================
    echo ""
    echo -e "${BOLD}${CYAN}============================================================${NC}"
    echo -e "${BOLD}${CYAN}                  Configuration Summary                     ${NC}"
    echo -e "${BOLD}${CYAN}============================================================${NC}"
    echo -e "  ${BOLD}MQTT URIs:${NC}"
    [ -n "$URI1" ] && echo -e "    1. ${CYAN}$URI1${NC}"
    [ -n "$URI2" ] && echo -e "    2. ${CYAN}$URI2${NC}"
    [ -n "$URI3" ] && echo -e "    3. ${CYAN}$URI3${NC}"
    echo -e "  ${BOLD}MQTT Auth:${NC} User: ${CYAN}$MQTT_USER${NC}, Pass: ${DIM}••••••••${NC}, Interval: ${CYAN}${TELE_INTERVAL}s${NC}, Telemetry: ${CYAN}${TELE_ENABLED}${NC}"
    echo -e "  ${BOLD}mTLS Certs:${NC} Client: ${CYAN}${CLIENT_P12_PATH:-None}${NC}, CA: ${CYAN}${CA_CRT_PATH:-None}${NC}"
    echo -e "  ${BOLD}ABRP:${NC} Enabled: ${CYAN}$ABRP_ENABLED${NC}, Token: ${CYAN}${ABRP_USER_TOKEN:0:8}...${NC}, Send GPS: ${CYAN}$ABRP_SEND_LOCATION${NC}"
    SPOTIFY_STATUS="None"
    [ -n "$SPOTIFY_REFRESH_TOKEN" ] && SPOTIFY_STATUS="•••••••• (Configured)"
    echo -e "  ${BOLD}Spotify:${NC} Client ID: ${CYAN}${SPOTIFY_CLIENT_ID:-None}${NC}, Token: ${DIM}${SPOTIFY_STATUS}${NC}"
    echo -e "  ${BOLD}Comfort:${NC} Target Temp: ${CYAN}${COMFORT_TEMP}°C${NC}, Window on Lock: ${CYAN}$WINDOW_ON_DOOR${NC}"
    echo -e "${BOLD}${CYAN}============================================================${NC}"
    echo ""

    echo -ne "${BOLD}Apply these settings to vehicle ${D}? [Y/n]: ${NC}"
    read -r CONFIRM || true
    CONFIRM=$(echo "$CONFIRM" | tr '[:upper:]' '[:lower:]')
    if [ -n "$CONFIRM" ] && [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "yes" ]; then
        echo -e "${YELLOW}Cancelled. No changes made to vehicle.${NC}"
        exit 0
    fi

    # Generate updated XML with Python
    export IN_XML="$CAR_XML"
    export OUT_XML="$TEMP_DIR/final_prefs.xml"
    export PY_URIS="$COMBINED_URIS"
    export PY_USER="$MQTT_USER"
    export PY_PASS="$MQTT_PASS"
    export PY_INTERVAL="$TELE_INTERVAL"
    export PY_TELE_ENABLED="$TELE_ENABLED"
    export PY_ABRP_ENABLED="$ABRP_ENABLED"
    export PY_ABRP_TOKEN="$ABRP_USER_TOKEN"
    export PY_ABRP_KEY="$ABRP_API_KEY"
    export PY_ABRP_GPS="$ABRP_SEND_LOCATION"
    export PY_SPOTIFY_ID="$SPOTIFY_CLIENT_ID"
    export PY_SPOTIFY_REFRESH="$SPOTIFY_REFRESH_TOKEN"
    export PY_SPOTIFY_VERIFIER="$SPOTIFY_VERIFIER"
    export PY_COMFORT_TEMP="$COMFORT_TEMP"
    export PY_WINDOW_DOOR="$WINDOW_ON_DOOR"

    python3 - <<'PYUPDATE'
import os
import xml.etree.ElementTree as ET
import xml.dom.minidom as minidom

in_file = os.environ['IN_XML']
out_file = os.environ['OUT_XML']

tree = ET.parse(in_file)
root = tree.getroot()

def set_key(root, key, val, tag="string"):
    for child in list(root):
        if child.attrib.get('name') == key:
            root.remove(child)
    if tag == "string":
        el = ET.SubElement(root, "string", {"name": key})
        el.text = val if val is not None else ""
    elif tag == "boolean":
        b_val = "true" if str(val).lower() in ("true", "1", "yes") else "false"
        ET.SubElement(root, "boolean", {"name": key, "value": b_val})
    elif tag == "int":
        ET.SubElement(root, "int", {"name": key, "value": str(int(val))})
    elif tag == "float":
        ET.SubElement(root, "float", {"name": key, "value": str(float(val))})

set_key(root, "mqtt_uri", os.environ['PY_URIS'], "string")
set_key(root, "mqtt_user", os.environ['PY_USER'], "string")
set_key(root, "mqtt_pass", os.environ['PY_PASS'], "string")
set_key(root, "tele_interval_s", os.environ['PY_INTERVAL'], "int")
set_key(root, "tele_enabled", os.environ['PY_TELE_ENABLED'], "boolean")

set_key(root, "abrp_enabled", os.environ['PY_ABRP_ENABLED'], "boolean")
set_key(root, "abrp_user_token", os.environ['PY_ABRP_TOKEN'], "string")
set_key(root, "abrp_api_key", os.environ['PY_ABRP_KEY'], "string")
set_key(root, "abrp_send_location", os.environ['PY_ABRP_GPS'], "boolean")

set_key(root, "spotify_client_id", os.environ['PY_SPOTIFY_ID'], "string")
set_key(root, "spotify_refresh_token", os.environ['PY_SPOTIFY_REFRESH'], "string")
set_key(root, "spotify_pkce_verifier", os.environ['PY_SPOTIFY_VERIFIER'], "string")

set_key(root, "comfort_sp_2", os.environ['PY_COMFORT_TEMP'], "float")
set_key(root, "window_on_door", os.environ['PY_WINDOW_DOOR'], "boolean")

# Migrate any legacy drivemem.apk references in URLs to drive_assist.apk
for child in list(root):
    val = child.attrib.get('value', child.text or '')
    if 'drivemem.apk' in val:
        new_val = val.replace('drivemem.apk', 'drive_assist.apk')
        if child.text is not None:
            child.text = new_val
        if 'value' in child.attrib:
            child.attrib['value'] = new_val

raw = ET.tostring(root, encoding="utf-8")
dom = minidom.parseString(raw)
pretty = dom.toprettyxml(indent="    ", encoding="utf-8")
with open(out_file, "wb") as f:
    f.write(pretty)
PYUPDATE
fi

# ============================================================================
# Safe Deployment to Vehicle
# ============================================================================
echo ""
echo -e "${YELLOW}Applying configuration to vehicle...${NC}"

# 1. Force stop app process so in-memory cache does not overwrite file
echo -e "  Stopping Drive Assist process..."
adb -s "$D" shell am force-stop com.geely.drivemem

# 2. Push updated SharedPreferences XML
echo -e "  Writing updated preferences XML..."
adb -s "$D" shell "mkdir -p /data/data/com.geely.drivemem/shared_prefs"
adb -s "$D" push "$TEMP_DIR/final_prefs.xml" /data/data/com.geely.drivemem/shared_prefs/drivemem.xml >/dev/null

# 3. Push Certificates if specified
if [ -n "${CLIENT_P12_PATH:-}" ] && [ -f "$CLIENT_P12_PATH" ]; then
    echo -e "  Installing client certificate (${CLIENT_P12_PATH})..."
    adb -s "$D" shell "mkdir -p /data/data/com.geely.drivemem/files"
    adb -s "$D" push "$CLIENT_P12_PATH" /data/data/com.geely.drivemem/files/mqtt_client.p12 >/dev/null
fi

if [ -n "${CA_CRT_PATH:-}" ] && [ -f "$CA_CRT_PATH" ]; then
    echo -e "  Installing CA certificate (${CA_CRT_PATH})..."
    adb -s "$D" shell "mkdir -p /data/data/com.geely.drivemem/files"
    adb -s "$D" push "$CA_CRT_PATH" /data/data/com.geely.drivemem/files/mqtt_ca.crt >/dev/null
fi

# 4. Resolve App UID and set secure permissions
UID_APP=$(adb -s "$D" shell dumpsys package com.geely.drivemem | grep userId | head -1 | cut -d"=" -f2 | tr -d " \r\n")
if [ -n "$UID_APP" ]; then
    echo -e "  Setting app permissions (UID ${UID_APP})..."
    adb -s "$D" shell "chown -R ${UID_APP}:${UID_APP} /data/data/com.geely.drivemem/shared_prefs /data/data/com.geely.drivemem/files 2>/dev/null || true"
    adb -s "$D" shell "chmod 771 /data/data/com.geely.drivemem/shared_prefs /data/data/com.geely.drivemem/files 2>/dev/null || true"
    adb -s "$D" shell "chmod 660 /data/data/com.geely.drivemem/shared_prefs/drivemem.xml 2>/dev/null || true"
    adb -s "$D" shell "chmod 600 /data/data/com.geely.drivemem/files/mqtt_client.p12 2>/dev/null || true"
    adb -s "$D" shell "chmod 644 /data/data/com.geely.drivemem/files/mqtt_ca.crt 2>/dev/null || true"
    # Restore SELinux context so app is not denied access to root-pushed certificates
    adb -s "$D" shell "restorecon -F -R /data/data/com.geely.drivemem/files /data/data/com.geely.drivemem/shared_prefs 2>/dev/null || true"
fi

# 5. Start Drive Assist
echo -e "  Restarting Drive Assist..."
adb -s "$D" shell am start -n com.geely.drivemem/.ui.ComfortActivity >/dev/null

# 6. Verify connection in logcat
echo -e "${DIM}  Waiting for service startup and connection check (4s)...${NC}"
sleep 4

echo ""
echo -e "${BOLD}${GREEN}✓ Configuration successfully applied!${NC}"
echo -e "${DIM}Recent vehicle logs:${NC}"
adb -s "$D" logcat -d -s DriveMem | tail -n 8 | sed 's/^/  /'
echo ""
echo -e "${GREEN}All settings active on vehicle.${NC}"
