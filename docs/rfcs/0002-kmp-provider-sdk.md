# RFC 0002: KMP native-service provider SDK and typed dependencies

- Status: Implemented
- Priority: P0
- Owners: `kweb-services-core`, desktop provider integration
- Depends on: RFC 0001
- Electron migration surface: native addons and main-process host modules
- Target mapping: `REWRITE`

## Objective

Make future native system capabilities easy to add through KMP without turning
the registry into a service locator. Extend the existing explicit registry with
typed provider factories, owner environments, capability probes, dependency
declarations, and deterministic startup/shutdown ordering.

## Common KMP contract

The SDK MUST define immutable `KWebServiceProviderKey`, target facts, exact
contract ranges, typed dependency edges, scope (`APPLICATION`, `PROFILE`,
`PAGE`, `WINDOW`), and an explicit factory receiving only its declared owner
environment and dependencies. Providers are installed by application code; no
classpath scanning or default provider exists.

Dependency graphs MUST be acyclic. Startup is topological; close is reverse
topological. Failed startup rolls back only resources created by that startup
attempt and returns a sticky typed failure. Capability probing reports facts but
never selects a weaker backend.

## Platform provider contract

Desktop owner environments MAY expose internal JDK 25 FFM access and exact native
window/Profile handles through internal types. Common provider APIs MUST remain
usable by future KMP hosts. Shared FFM/C ABI plumbing is extracted only after two
implemented services demonstrate identical ownership.

## Security and lifecycle invariants

Providers cannot query undeclared services, mutate another scope, start after
owner close, or retain raw owner handles after terminal shutdown. Version and
target mismatch fail before native initialization.

## Acceptance

1. Common tests cover graph validation, version ranges, duplicate keys, startup
   rollback, concurrent close, dependency failure, and exact reverse shutdown.
2. A fixture installs two real existing services through factories without
   changing their public service contracts.
3. Windows, macOS, and Linux prove deterministic provider selection from
   explicit application configuration and identical lifecycle event order.
4. A missing provider, ambiguous provider, unsupported target, and undeclared
   dependency each produce distinct stable errors.
5. Packaged metadata names every provider and contains no reflective
   implementation-class lookup.

## Migration result

Electron native addons and main-process packages migrate by implementing a
dedicated KMP service/provider. They do not become dynamically loaded renderer
plugins.

## Non-goals

No runtime classpath scanning, default provider, Java service locator, platform
type in common code, renderer-loadable native plugin, or fallback provider.
