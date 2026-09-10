# RFC 0014: Typed system clipboard

- Status: Proposed
- Priority: P0
- Owners: new `kweb-service-clipboard` KMP service
- Depends on: RFC 0003, RFC 0004, RFC 0027
- Electron migration surface: `clipboard`, `ClipboardItem`
- Target mapping: `ADAPTER`

## Objective

Provide typed text, HTML, RTF, image, URI-list, and explicitly registered custom
clipboard formats with permission, gesture, size, and ownership controls.

## Common KMP contract

Define clipboard selection (`SYSTEM`, Linux `PRIMARY` when advertised), format
descriptors, bounded lazy payload streams, sequence/change events, and atomic
multi-format writes. Reads and writes are `suspend`; availability observation is
an ordered `Flow`. Raw platform format names require an application-side
allowlist and are not renderer-visible by default.

## Platform provider contract

Use Win32 delayed-render clipboard ownership, AppKit pasteboards, and
X11/Wayland Portal/desktop clipboard contracts explicitly. A missing Wayland
facility must not fall back to an unrelated X11 clipboard.

## Acceptance

1. Real inter-process fixtures round-trip every advertised format, Unicode,
   empty values, ownership loss, concurrent readers, and application exit.
2. HTML/RTF and custom formats are sanitized/allowlisted according to policy;
   oversized payloads fail before allocation.
3. Image payloads use RFC 0027 with deterministic encoding and alpha/color-space
   evidence.
4. Renderer reads require the declared gesture/consent; child/cross-origin Pages
   cannot observe clipboard changes.
5. Migration fixtures preserve declared async clipboard methods and classify
   Electron renderer-direct clipboard use as an adapter rewrite.
6. Linux PRIMARY is a separate platform capability and never reported on
   Windows/macOS.

## Non-goals

No clipboard history manager, keylogging-like polling, unsanitized arbitrary
native formats, or implicit web Clipboard API permission bypass.
