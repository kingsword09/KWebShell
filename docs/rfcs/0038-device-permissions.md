# RFC 0038: Media, USB, HID, serial, and Bluetooth device permissions

- Status: Proposed
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium permission/device adapters
- Depends on: RFC 0003, RFC 0004, RFC 0009
- Electron migration surface: session permission/device handlers, device selection events
- Target mapping: `REWRITE`

## Objective

Connect Chromium's real web-platform device APIs to typed application policy,
native user consent, device selection, revocation, and Profile persistence.

## Contract

Separate capabilities cover camera, microphone, geolocation, notifications,
USB, HID, serial, Bluetooth, MIDI, and display media. Requests include secure
origin, top-level origin, frame, requested constraints/filters, device summaries,
gesture, OS permission, and one-shot decision. Stable device identifiers are
Profile-salted and never general hardware IDs.

## Acceptance

1. Representative real or hardware-in-loop devices complete grant/use/revoke/
   reconnect on Windows, macOS, and Linux for every advertised API.
2. OS denial/restriction, no device, chooser cancel, unplug, permission reset,
   navigation, and Profile close have distinct results.
3. Cross-origin iframes, insecure origins, synthetic gestures, broadened filters,
   and stale decisions are rejected.
4. Persisted decisions are exact origin+device+capability scoped and revocable.
5. Migration fixture replaces individual Electron session handlers with Kotlin
   policy; a generic “allow all permissions” handler remains blocked.
6. Packaging verifies privacy descriptions, entitlements, udev/portal/runtime
   requirements without auto-installing system policy.

## Evidence

Retain anonymized device class IDs, permission transitions, controlled media
hashes, and disconnect cleanup; never raw media or globally identifying serials.

## Non-goals

No generic allow-all handler, stable global device fingerprint, raw media
retention, synthetic gesture, OS-policy installation, device emulation fallback,
or grant reuse across origin/Profile/capability.
