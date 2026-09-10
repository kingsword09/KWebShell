# RFC 0033: macOS Touch Bar and Share menu capabilities

- Status: Proposed
- Priority: P2
- Owners: macOS-specific KMP service keys
- Depends on: RFC 0017, RFC 0027, RFC 0030
- Electron migration surface: `TouchBar`, `ShareMenu`
- Target mapping: macOS-only `ADAPTER` or `REWRITE`

## Objective

Provide honest macOS-only contracts for Touch Bar controls on supported systems
and the native sharing service. Do not force these semantics into fake
cross-platform APIs.

## Common KMP contract

The module may be KMP, but public keys explicitly advertise `macosArm64`/
supported macOS targets. Touch Bar models are immutable bounded command trees
with buttons, labels, groups, sliders, scrubbers, and escape item only when each
is fully implemented. Share requests accept typed text/URI and RFC 0013/0027
resources.

## Platform provider contract

Use NSTouchBar and NSSharingServicePicker on AppKit with caller-owned window/view
anchors. Runtime hardware/service availability is a capability fact. Windows and
Linux have no provider key and never report a no-op implementation.

## Acceptance

1. macOS native tests render/update/invoke/close each advertised Touch Bar item
   on a supported test host or Apple's approved automation fixture.
2. Share service selection receives controlled text, URL, file handle, and image
   payloads with cancellation and owner-close behavior.
3. Commands route through RFC 0017 IDs and cannot serialize callbacks.
4. Exact-origin renderer access requires declared methods, gesture, and resource
   handles.
5. Migration fixture classifies every used TouchBar/ShareMenu type; Windows/Linux
   builds report an explicit platform migration blocker.
6. Packaging verifies required entitlements and leaves no retained AppKit object.

## Non-goals

No emulated Windows/Linux Touch Bar, arbitrary NSSharingService invocation, or
promise for hardware Apple has removed from current products.
