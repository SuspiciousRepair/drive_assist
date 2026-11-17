# Private web statistics sharing

**Status: exploratory — design direction recorded; implementation is not yet
scheduled.**

## Goal

Let a car owner open a QR code on the head unit and review selected vehicle
statistics in a browser, without the hosting service being able to read the
statistics themselves. This is deliberately a small encrypted sharing flow,
not a conventional account-based companion service.

## Core design

1. The car serializes the selected statistics and generates a fresh random
   symmetric encryption key.
2. The car encrypts the payload locally (an authenticated-encryption scheme,
   such as AES-GCM) and uploads only ciphertext to an opaque, random blob ID
   on the host.
3. The head unit presents a QR code containing a review URL whose path names
   that blob and whose fragment holds the decryption key:

   ```text
   https://review.example/r/<opaque-blob-id>#<decryption-key>
   ```

4. The browser loads a static review page, requests the ciphertext by blob ID,
   and decrypts it locally with the Web Crypto API before rendering it.

The URL fragment (everything after `#`) is not sent in an HTTP request. The
host receives the opaque blob ID but not the decryption key. A key must never
be put in a query parameter (`?key=...`), request body, server-side log, or
analytics event.

## What each party can see

| Party | Data it has |
| --- | --- |
| Car | Plain statistics and the encryption key while creating the share |
| Host | Ciphertext, blob ID, and operational metadata |
| Browser opened with the complete QR URL | Key, ciphertext, and plaintext after local decryption |

The host will still learn unavoidable metadata, including upload/download
times, approximate payload sizes, IP addresses (unless separately protected),
and blob IDs. The goal is confidentiality of the statistics, not metadata
elimination.

## One-time link behavior

The service can provide a one-time fetch: atomically redeem the blob ID when
the browser asks for its ciphertext, return it once, then delete it or mark it
unavailable. Also give every share a short expiry.

This makes the *hosted ciphertext* one-time. It cannot prevent a recipient
from saving the URL, ciphertext, rendered values, or screenshots. Anyone who
photographs the QR code before redemption can open it, so the QR is a bearer
secret and should be shown only while the owner intends to share it.

## Browser persistence

After first opening, the site may generate a browser-local, non-extractable
Web Crypto key and use it to wrap the share's data key in IndexedDB. That
browser can then reopen the saved review without scanning the QR again.

This is convenience, not recovery: clearing browser data, changing browser
profiles, or changing devices loses access. The original QR URL or a separate
owner-controlled recovery mechanism is needed to regain it.

## Trust boundary and limitation

This design protects data from an honest-but-curious storage host: the host
normally has ciphertext only and does not receive the fragment key.

It does **not** fully protect against the operator of the web application
later serving modified JavaScript that reads the fragment or plaintext and
exfiltrates it. A stronger claim of zero knowledge therefore requires a
trusted client, for example a native app, a separately installed/audited
viewer, or carefully version-pinned static assets with an update trust model.
The initial web version should accurately be described as client-side
encrypted sharing, not absolute zero knowledge against a malicious future web
operator.

## Minimal implementation shape

- **Car:** serialize only the intended review data; generate a fresh key per
  share; encrypt and authenticate before upload; render QR only after upload
  succeeds.
- **Host:** opaque upload, one-time/expiring ciphertext retrieval, deletion,
  and no payload inspection or analytics that captures URLs.
- **Static viewer:** read `window.location.hash` locally, decode the key,
  fetch ciphertext, verify/decrypt via Web Crypto, then render. Clear the
  fragment from browser history after loading without ever transmitting it.

## Decisions to make before implementation

1. Which statistics are safe and useful to share (trip summaries, energy,
   charging, location history, raw telemetry, etc.).
2. Retention and expiry defaults, plus whether a car can explicitly revoke an
   unredeemed share.
3. Whether direct local transfer (Wi-Fi/Bluetooth) is needed as a no-host
   alternative.
4. The required threat model: privacy from normal infrastructure operators,
   or privacy even if the web-client operator becomes malicious.
