# RFC 0013: Capability-based files, directories, and workspace access

- Status: Proposed
- Priority: P0
- Owners: new `kweb-service-files` KMP service
- Depends on: RFC 0002, RFC 0003, RFC 0004
- Electron migration surface: Node `fs`, `path`, renderer `webUtils.getPathForFile`
- Target mapping: `REWRITE`

## Objective

Generalize dialog-selected handles into capability-based file and directory
access for migrated applications. Preserve common KMP semantics and prevent a
renderer path string from becoming ambient filesystem authority.

## Common KMP contract

Publish opaque file/directory/workspace handles with explicit read, write,
create, enumerate, watch, move, copy, metadata, and close grants. Every operation
has byte/item/depth limits, cancellation, conflict policy, and normalized
relative names. Absolute paths remain trusted Kotlin-only values.

Handles are unforgeable, owner-scoped, non-serializable by default, and mode
checked. Persistent grants require an explicit application policy and OS
bookmark/token where available; restart restoration can fail without selecting a
different directory.

## Platform provider contract

Use exact JDK filesystem semantics plus Windows handle/reparse checks, macOS
security-scoped bookmarks and vnode rules, and Linux Portal document grants/open
file descriptors where sandboxed access requires them. Symlinks/reparse points
are rejected unless an operation explicitly opts into a bounded verified target.

## Acceptance

1. Common tests cover capability derivation, least privilege, relative-name
   validation, stale/forged handles, quotas, watch overflow, and close races.
2. Native filesystem tests cover case sensitivity, Unicode normalization,
   symlink/reparse attacks, atomic replace, locks, sparse/large files, and
   permission changes on all targets.
3. Binary streaming uses RFC 0004 and proves bounded memory for multi-gigabyte
   fixtures.
4. Exact-origin tests prove handle isolation across Page, frame, origin, Profile,
   and navigation.
5. Migration fixtures replace representative async Node `fs` workflows; sync
   APIs and `pathForFile` remain blocked.
6. Packaging retains no test workspace and logs no private absolute path.

## Non-goals

No virtual Node filesystem, arbitrary absolute path API, shell glob language,
transparent symlink traversal, or synchronous renderer I/O.
