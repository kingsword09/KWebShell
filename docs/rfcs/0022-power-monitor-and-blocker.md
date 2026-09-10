# RFC 0022: Power monitor and bounded sleep blockers

- Status: Proposed
- Priority: P1
- Owners: new `kweb-service-power` KMP service
- Depends on: RFC 0002, RFC 0004
- Electron migration surface: `powerMonitor`, `powerSaveBlocker`
- Target mapping: `ADAPTER`

## Objective

Expose suspend/resume, lock/unlock, power-source/thermal state where available,
and explicitly owned display/system sleep blockers.

## Common KMP contract

Define ordered power events, capability facts, and closeable blocker leases with
reason, type, acquisition time, and maximum duration. Leases auto-expire and are
released on owner close. Unsupported event kinds are absent, not synthesized.

## Platform provider contract

Use Windows power/session notifications and execution-state/power requests,
macOS NSWorkspace/IOKit/process assertions, and Linux login1/UPower inhibitor
contracts. Missing D-Bus services fail rather than creating a busy loop.

## Acceptance

1. Native tests drive suspend/resume or approved platform simulation, session
   lock/unlock, AC/battery changes, and thermal state where advertised.
2. Real OS inspection proves blocker acquisition, renewal, expiry, explicit
   close, process crash cleanup, and no leaked assertion.
3. Multiple leases compose deterministically without one owner releasing
   another's blocker.
4. Renderer lease creation is application-declared, time-bounded, and separately
   permissioned; event observation cannot create a lease.
5. Migration fixtures map Electron blocker IDs to scoped handles and reject
   indefinite/unowned use.
6. Evidence includes native assertion IDs only in trusted logs and zero live
   blockers after tests.

## Non-goals

No power-plan mutation, shutdown prevention without deadline, battery estimate
fabrication, or alternate polling backend.
