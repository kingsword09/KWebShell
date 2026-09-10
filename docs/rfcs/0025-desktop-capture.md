# RFC 0025: Desktop capture source selection and media grants

- Status: Proposed
- Priority: P1
- Owners: browser media policy plus new capture service where OS selection is required
- Depends on: RFC 0003, RFC 0004, RFC 0020, RFC 0027
- Electron migration surface: `desktopCapturer`, display-media request handler
- Target mapping: `REWRITE`

## Objective

Support user-mediated screen/window capture through Chromium media streams and
native source selection while preventing silent desktop enumeration or capture.

## Contract

Define capture request, source kind, bounded thumbnail/icon references, audio
mode, permission state, user selection result, and revocable grant bound to one
Page/origin/media request. Source IDs are ephemeral and never general window
handles.

## Platform provider contract

Use Windows Graphics Capture/system picker, macOS ScreenCaptureKit and TCC, and
Linux ScreenCast Portal/PipeWire. Chromium consumes the selected source through
its real media pipeline. If the declared system picker/provider is unavailable,
the request fails; no screenshot polling fallback exists.

## Acceptance

1. User selects one display and one window through the real system UI; only that
   source reaches `getDisplayMedia`.
2. Denial, cancellation, source closure, permission revocation, monitor removal,
   and Page/navigation close terminate the stream deterministically.
3. Audio modes report real support and never substitute microphone/system audio.
4. Enumeration/thumbnail APIs require separate consent and enforce size/count/
   refresh limits.
5. Exact-origin tests prove synthetic gestures and child frames cannot obtain or
   reuse a grant.
6. Migration fixture replaces `desktopCapturer.getSources` with a selection-
   first flow and documents renderer changes.

## Evidence

Retain consent/result metadata and controlled test-pattern frame hashes, never
unrelated desktop imagery or audio.

## Non-goals

No silent desktop enumeration, synthetic consent, persistent source identifier,
screenshot-polling fallback, audio-source substitution, or background grant
reuse.
