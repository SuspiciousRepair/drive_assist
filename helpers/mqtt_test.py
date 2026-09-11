#!/usr/bin/env python3
# Espelha o que o Drive Assist (MqttReporter) faz: discovery + state, no Mosquitto do HA.
# Valida a cadeia do Mac enquanto o carro esta bloqueado por AP isolation.
# Uso: python3 mqtt_test.py <host> <user> <pass>
import socket, sys, struct, json, time

HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
USER = sys.argv[2] if len(sys.argv) > 2 else ""
PASS = sys.argv[3] if len(sys.argv) > 3 else ""
PORT = 1883
DEV_ID = "drivemem_geely"
STATE_TOPIC = "drivemem/geely/state"

# mesmos campos do Telemetry.java, com os valores REAIS capturados no carro
FIELDS = [
    # key, name, unit, dev_class, comp, exemplo
    ("battery",  "Bateria",    "%",   "battery",  "sensor",        94.3),
    ("odometer", "Odometro",   "km",  "distance", "sensor",        2969.8),
    ("range",    "Autonomia",  "km",  "distance", "sensor",        337),
    ("speed",    "Velocidade", "km/h", None,      "sensor",        0),
    ("gear_label","Marcha",    None,  None,       "sensor",        "P"),
    ("ac_on",    "AC ligado",  None,  None,       "binary_sensor", 1),
    ("trip_km",  "Trip total", "km",  "distance", "sensor",        1968.8),
]

def enc_str(s):
    b = s.encode()
    return struct.pack(">H", len(b)) + b

def remaining(n):
    out = b""
    while True:
        d = n % 128; n //= 128
        if n: d |= 0x80
        out += bytes([d])
        if not n: break
    return out

def mqtt_connect(sock):
    cid = enc_str("drivemem-mactest")
    flags = 0x02  # clean session
    payload = cid
    if USER:
        flags |= 0x80
        payload += enc_str(USER)
    if PASS:
        flags |= 0x40
        payload += enc_str(PASS)
    var = enc_str("MQTT") + bytes([0x04, flags]) + struct.pack(">H", 60)
    pkt = b"\x10" + remaining(len(var + payload)) + var + payload
    sock.send(pkt)
    r = sock.recv(4)
    rc = r[3] if len(r) > 3 else 255
    print(f"CONNACK rc={rc} ({'OK' if rc==0 else 'ERRO'})")
    return rc == 0

def mqtt_pub(sock, topic, payload, retain=False):
    t = enc_str(topic)
    hdr = 0x30 | (0x01 if retain else 0)
    body = t + payload.encode()
    pkt = bytes([hdr]) + remaining(len(body)) + body
    sock.send(pkt)

def main():
    s = socket.create_connection((HOST, PORT), timeout=6)
    if not mqtt_connect(s):
        print("Falha auth — confira user/senha"); return
    # discovery
    for key, name, unit, dev_cla, comp, _ in FIELDS:
        cfg = {
            "name": name, "uniq_id": f"{DEV_ID}_{key}",
            "stat_t": STATE_TOPIC, "val_tpl": "{{ value_json.%s }}" % key,
            "dev": {"ids": [DEV_ID], "name": "Geely EX2", "mf": "Geely", "mdl": "IHU629G"},
        }
        if unit: cfg["unit_of_meas"] = unit
        if dev_cla: cfg["dev_cla"] = dev_cla
        if comp == "binary_sensor": cfg["pl_on"] = "1"; cfg["pl_off"] = "0"
        topic = f"homeassistant/{comp}/{DEV_ID}/{key}/config"
        mqtt_pub(s, topic, json.dumps(cfg), retain=True)
        print("discovery ->", topic)
    # state
    state = {k: v for (k, _, _, _, _, v) in FIELDS}
    mqtt_pub(s, STATE_TOPIC, json.dumps(state))
    print("state ->", STATE_TOPIC, json.dumps(state))
    time.sleep(1)
    s.close()
    print("\nOK. Veja em HA -> Configuracoes -> Dispositivos -> 'Drive Assist (Geely)'")

main()
