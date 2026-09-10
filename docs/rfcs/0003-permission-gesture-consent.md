# RFC 0003: Native-service permission, user-gesture, consent, and audit policy

- Status: Proposed
- Priority: P0
- Owners: `kweb-services-core`, `kweb-desktop`, service policy
- Depends on: RFC 0001, RFC 0002
- Electron migration surface: privileged preload methods and session permission handlers
- Target mapping: `ADAPTER` or `REWRITE` per operation

## Objective

Publish one policy engine for every privileged browser and native-service
operation. It must distinguish application trust, exact renderer grants, current
native-verified user gestures, OS consent, persistent user decisions, and
revocation.

## Common KMP contract

Define typed capability subjects, operation grants, gesture tokens with
single-use expiry, consent requests/results, persistence scope, revocation
events, and redacted audit records. A decision is `ALLOW`, `DENY`, or
`PROMPT_REQUIRED`; there is no implicit allow or fallback prompt.

Gesture tokens MUST be minted from the browser/native input event path, bound to
Engine/Profile/Page/main-frame/origin, consumed once, and rejected after
navigation or timeout. Services declare whether Kotlin-host calls bypass only
renderer grants; they never bypass OS permission.

## Platform provider contract

Providers map to Windows privacy/capability status, macOS TCC and application
entitlements, and Linux Portal permission stores where those facilities exist.
The provider reports `not-configured`, `denied`, `restricted`, and
`temporarily-unavailable` separately.

## Acceptance

1. Common tests exhaust grant precedence, token replay, expiry, navigation,
   revocation, concurrent prompts, and owner close.
2. Native tests exercise one real consent-requiring facility per platform and
   retain the OS status without automating around a system denial.
3. CEF tests prove synthetic DOM events cannot mint a gesture, child frames
   cannot reuse one, and a cross-origin commit invalidates it.
4. Audit records are ordered, bounded, redacted, and disabled for payload data.
5. Migration generation requires every privileged method to declare grant,
   gesture, and consent policy before packaging.

## Non-goals

No global “trusted renderer” switch, wildcard origin, hidden consent window,
programmatic bypass of OS prompts, or Electron `session.setPermissionCheckHandler`
callback compatibility is published.
