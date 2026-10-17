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

## Contract review record

- Reviewed contract revision: `92d81e63` plus this reviewed contract; the review
  pass is recorded honestly as a same-contributor review, not an independent
  approval.
- Reconnaissance recorded before implementation (real sources, not assumptions).
  The pinned runtime is CEF `151.3.16+gbe1e15d`, Chromium `151.0.7922.109`:
  - `include/cef_permission_handler.h` @ `be1e15d` publishes
    `OnRequestMediaAccessPermission(browser, frame, requesting_origin,
    requested_permissions, callback)`, and `CefMediaAccessCallback` offers only
    `Continue(uint32_t allowed_permissions)` and `Cancel()`. The continuation
    carries **permission bits and nothing else**: there is no argument by which a
    host can tell Chromium *which* display or window the user chose.
  - `libcef/browser/media_access_query.cc` @ `be1e15d` resolves a desktop video
    source from `request_.requested_video_device_ids.front()` through
    `content::DesktopMediaID::Parse(...)`, and otherwise defaults to
    `DesktopMediaID(TYPE_SCREEN, -1)`, which is the entire desktop. Both branches
    are renderer-influenced or whole-desktop: neither is a native selection
    result.
  - The CEF tree wires no picker at all. Code search over
    `chromiumembedded/cef` @ `be1e15d` returns zero results for
    `DesktopMediaPicker`, `desktop_media_picker`, `DesktopMediaList`,
    `ThumbnailCapturer`, `desktop_capture_access_handler`, and
    `kNativePickerSession`, so Chromium's `DesktopMediaPickerFactory`,
    `DesktopMediaList`, and its per-platform pickers are not reachable from CEF.
  - `kweb-cef-native/src` registers no `CefPermissionHandler` and sets no
    `--enable-media-stream` switch, so today the request reaches CEF's own default
    handling. With the Alloy runtime style this product requires for the embedded
    `WebContents`, that default **denies** the request, which is a browser default
    rather than the typed, auditable policy this RFC promises.
  - No CEF distribution is present in this environment (`kweb-cef-native` needs
    `-PcefRoot`), so no real `getDisplayMedia` probe could be executed here. The
    readiness decision therefore rests on the pinned sources above, not on a
    runtime observation.
- Findings:
  - **Blocking (acceptance 1):** "the user selects one display and one window
    through the real system UI; only that source reaches `getDisplayMedia`" cannot
    be satisfied through any published CEF API. The only channel that names a
    source is the renderer's own `deviceId`, and the only fallback is the whole
    desktop, so a host-side picker has no way to hand its result to the media
    request. Delivering this requires a new private adapter in
    `runtime/cef/patches/` alongside `0001-profile-scoped-extension-lifecycle` and
    `0002-profile-scoped-network-policy`, built by
    `runtime/cef/build-custom-runtime.py` on the self-hosted
    `kwebshell-cef-build` runners (`custom-cef-source-build.yml`, 360-minute
    budget). That build cannot be produced or verified from this environment.
  - **Blocking (acceptance 4):** bounded enumeration and thumbnails
    (`desktopCapturer.getSources`) require OS enumeration and thumbnail capture on
    three targets — ScreenCaptureKit/CoreGraphics window lists on macOS, Windows
    Graphics Capture on Windows, and the ScreenCast portal plus PipeWire on Linux —
    with consent, count, size, and refresh limits. That is an independent
    provider surface with its own feasibility work, not a field on this contract.
  - **Accepted boundary:** because CEF's Alloy default denies desktop capture, the
    shell does not currently expose silent capture; the gap is that the denial is
    untyped and unowned rather than that a bypass exists.
  - **Note for RFC 0042:** its acceptance 2 requires this RFC's capture path to
    prove protected-window exclusion, so 0042 cannot be accepted before 0025 has a
    real capture surface.
- Decision: `NOT_READY`.

  RFC 0025 is not implemented in this change. Acceptance 1 needs a custom-runtime
  adapter whose build and three-target evidence cannot be produced here, and
  acceptance 4 is a separate provider objective. Publishing the typed contract,
  grants, and denial policy without a working selection path would expose exactly
  the partial API this repository forbids, so nothing was added to the public
  surface.

  The recommended split, in dependency order, is:

  1. **Capture denial policy (complete, deliverable now):** a typed
     `capture.*` policy in `kweb-desktop` that registers a
     `CefPermissionHandler`, denies `DESKTOP_VIDEO_CAPTURE` and
     `DESKTOP_AUDIO_CAPTURE` with a stable code, leaves camera/microphone to RFC
     0038, and proves through exact-origin, child-frame, synthetic-gesture, and
     real-CEF probes that a page cannot reach desktop media or a renderer-named
     source. This closes the untyped-default gap and needs no patch.
  2. **Custom-runtime selection adapter:** the CEF patch that carries a
     host-side picker result into the media request, plus the three real pickers,
     with its own source-build and hosted evidence.
  3. **Enumeration and thumbnails:** bounded source lists with separate consent
     and refresh limits.

  Acceptance 2, 3, 5, and 6 belong to objective 1 for the denial and termination
  paths and to objective 2 for the granted path; they should be renumbered into
  whichever objective actually implements them rather than carried here as
  unverifiable rows.

  This review does not change the RFC's scope silently: it records that the scope
  as written is a multi-objective delivery and must be split before coding.
