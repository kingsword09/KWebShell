# RFC 0031: Signed atomic updates and recovery

- Status: Proposed
- Priority: P0
- Owners: new update service, runtime pack, release engineering
- Depends on: RFC 0003, RFC 0030
- Electron migration surface: `autoUpdater`
- Target mapping: `REWRITE`

## Objective

Check, stage, verify, install, restart, and recover KWebShell application updates
using signed release metadata and platform-appropriate installation semantics,
including an explicit Linux contract.

## Common KMP contract

Define update channel/feed identity, semantic version, rollout eligibility,
release notes, payload/delta metadata, progress, staged state, user decision,
install/restart result, and recovery status. The application owns scheduling;
renderer adapters can observe/request only declared actions.

## Platform provider contract

Use the selected Windows package installer/MSIX mechanism, macOS signed bundle/
installer replacement with notarization verification, and one declared Linux
package/AppImage/repository mechanism. Unlike Electron's built-in updater, Linux
cannot be silently delegated to “the package manager”; the application must
select and test its contract.

## Acceptance

1. A signed update server fixture covers no update, full update, delta where
   declared, rollout, downgrade rejection, replay, expiry, bad signature/hash,
   interrupted download, disk full, and offline behavior.
2. Each platform installs N→N+1 in a real package, preserves Profile data,
   relaunches once, and reports the new version.
3. Crash/power loss at every staging/install checkpoint recovers to either the
   verified old or new version, never a mixed payload.
4. Update cannot replace helper/runtime/schema with mismatched identities.
5. Renderer cannot set arbitrary feed URL, disable verification, choose local
   executable payloads, or force silent restart.
6. Migration fixtures map used autoUpdater events/methods and identify semantic
   differences, especially Linux.

## Evidence

Retain signed metadata, hashes, state-machine transcript, installed version,
recovery probes, and package identity; redact private feed credentials.

## Non-goals

No unsigned feed or payload, mutable renderer feed URL, cross-identity update,
silent Linux package-manager assumption, mixed-version recovery, or update
verification bypass.
