# ADR 0010: Manifest V3 package verification boundary

- Status: Accepted
- Date: 2026-08-13

## Context

Chromium's extension service must own MV3 execution, permissions, Service Worker
lifecycle, content scripts, isolated worlds, and network rules. Before that
runtime can receive an extension, KWebShell needs a deterministic security
boundary for unpacked development directories and CRX3 distribution files.
Accepting a parsed manifest alone would permit unsigned packages, path traversal,
symlink escapes, ambiguous IDs, ZIP bombs, and silently ignored permissions.

## Decision

`kweb-extensions` defines a strict, versioned Manifest V3 package model in common
Kotlin and a JVM verifier for filesystem and cryptographic operations. Unpacked
packages must contain a regular `manifest.json` with a base64 X.509
SubjectPublicKeyInfo `key`; their ID is the first 16 bytes of SHA-256(public-key
DER), encoded with Chromium's `a`-through-`p` alphabet. Path-derived IDs are
deliberately unavailable.

CRX3 verification follows Chromium's container contract: `Cr24`, version 3,
bounded little-endian header length, a protobuf `CrxFileHeader`, signed-data
`crx_id`, and signatures over `CRX3 SignedData\0` + little-endian signed-header
length + signed-header bytes + archive bytes. RSA PKCS#1 v1.5 SHA-256 proofs use
keys of at least 2048 bits; ECDSA proofs use P-256. Every proof must use its
declared key type, have the declared ID, and verify before the archive is
inspected. Header EOCD/Zip64 boundary tokens rejected by Chromium are also
rejected. ZIP entries are bounded, unique, relative, non-encrypted, and free of
Unix symlink attributes; `manifest.json` is parsed directly from the archive
without extracting attacker-controlled files.

Permission review is explicit and immutable. API permission names are checked
against the package-admissible matrix; host access is returned as a separate
decision, including content-script and web-accessible-resource match scopes.
MV3 host patterns placed in `permissions` or `optional_permissions` are rejected
instead of being interpreted as legacy declarations. Universal host wildcards
require explicit broad-host approval, while `<all_urls>` also requires file URL
approval.

Policy-controlled permissions and broad hosts fail immediately. An
`API_PERMISSION` decision means only that the manifest can cross this package
boundary; it is not a claim that a `chrome.*` API is running. This module does
not install packages, start Chromium services, or emulate `chrome.*`.

## Consequences

The verifier is intentionally strict and breaking. A package must be repaired or
its requested capability must be explicitly added to a later versioned matrix;
there is no compatibility parser or fallback to an unsigned/path-based mode.
Cryptographic and archive limits are fixed to prevent unbounded memory and ZIP
bomb behavior. The JVM implementation is isolated behind the package result so
future Kotlin/Native callers can consume the same model after the ABI/runtime
contract is ready.

## Verification

The Objective 5.1 test suite uses generated RSA and EC keys, constructs signed
CRX3 fixtures with a minimal protobuf encoder, mutates every signed region, and
tests unpacked directories containing traversal and symlink attacks. It also
checks exact permission decisions and all typed failure codes on the three CI
desktop targets. No test claims Service Worker, Chromium installation, or
Manifest V3 API execution; those are Objective 5.2 acceptance criteria.
