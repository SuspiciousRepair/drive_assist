# Integrations as data (a settings DSL) — idea, not started

Status: **idea only**. Noted 2026-11-14 while the Config screen got its
Integrations page (one sidebar item, one tile per integration,
`ui/Integrations.java`). Nothing below is built.

## The idea

Today an integration is Java: a `Telemetry*Section` that builds its own cards,
plus one line in `Integrations.ALL`. The idea is to describe an integration's
settings as **data** — a small file (YAML or JSON) — and have one generic
renderer turn it into the same cards. Then:

- a new integration's settings page needs no Java;
- an integration could be **imported** — from a site, a QR code, a file on USB;
- every integration page looks the same, because one renderer draws them all.

## Why it came up

Secrets and per-user ids must not be in the build. Spotify is the example: the
app should ship with no client id or secret, and the owner types theirs on the
config page. A declared field (`type: secret`) says that once, for every
integration, instead of each section hand-rolling its own text box and save.

## Sketch of a definition

```yaml
id: spotify
name: Spotify
description: What is playing, on the home screen.
icon: spotify            # a built-in drawable name, or an inline SVG path
color: "#1DB954"
status: spotify.connected   # a status the app knows how to compute
cards:
  - title: Account
    fields:
      - key: spotify_client_id
        type: text
        label: Client ID
      - key: spotify_client_secret
        type: secret       # masked, never logged, never in a backup in clear
        label: Client secret
    actions:
      - label: Connect
        run: spotify.connect    # a built-in action; files cannot add code
  - title: Home screen
    fields:
      - key: spotify_large_card
        type: toggle
        label: Large card
```

Card vocabulary to start with: `toggle`, `text`, `secret`, `number`
(min/max/unit), `choice`, `hint`, `status` (a live line), `action` (a button
that calls a built-in).

## The hard line: data, not code

A definition can only **arrange** what the app already knows how to do. It
cannot add behaviour. `run:` and `status:` name built-ins; an unknown name
draws a disabled control, not a crash.

That matters most for anything imported. A downloaded definition that could
send car data to any URL is a way to leak location and charging habits. If a
generic "send these hub keys to this URL" integration is ever added, the
import screen must show, before saving, exactly which keys go where, and the
owner must confirm.

## Open questions

- YAML or JSON on the car? Android parses JSON with no library (`org.json`).
  YAML needs one (SnakeYAML, ~300 KB). Could author in YAML and ship JSON.
- Where `secret` values live (plain SharedPreferences today), and whether the
  password-locked backup (`.dabackup`) carries them.
- How the existing Java sections (MQTT, OBD2, ABRP, Spotify) move over: one at
  a time, Spotify first, since it is the smallest.
- Signing or a trust list for definitions imported from a site.
