# Signed Runtime Release Format

Schema version 1 defines the authenticated release boundary for one target.
It consumes the verified unsigned payload described by
[`runtime-payload-format.md`](runtime-payload-format.md). The release pack is
an artifact that a later update client may transport; this format does not
implement network discovery, version selection, rollback, or installation.

## Pack layout

The pack is a classic ZIP with exactly these entries, in lexical UTF-8 order:

```text
metadata.json
payload.zip
signature.ed25519
```

Every entry is a regular Unix file with mode `0644`, the fixed timestamp
`2000-01-01 00:00:00`, UTF-8 names, no extra fields, no comments, no
encryption, no data descriptor, and the `STORED` method. ZIP64, preambles,
trailing bytes, duplicate names, and bytes between entries are rejected.
`payload.zip` is copied byte-for-byte from the independently verified unsigned
payload; it is not recompressed or rewritten.

The pack is published through a same-filesystem atomic move. An existing output
is left untouched if any input, key, signature, or verification step fails.

## Metadata

`metadata.json` is canonical UTF-8 JSON with one trailing newline. Its fields
are encoded in this order:

```json
{
  "schemaVersion": 1,
  "product": "KWebShell",
  "productVersion": "0.1.0-SNAPSHOT",
  "target": "macos-arm64",
  "cefVersion": "151.3.16+gbe1e15d+chromium-151.0.7922.109",
  "chromiumVersion": "151.0.7922.109",
  "payload": {
    "fileName": "payload.zip",
    "size": 123,
    "sha256": "...",
    "treeSha256": "..."
  },
  "signatureAlgorithm": "Ed25519",
  "keyId": "..."
}
```

The payload size and SHA-256 cover the exact nested ZIP bytes. `treeSha256`
must equal the digest in the nested payload manifest. No build timestamp,
absolute path, host name, or mutable URL is part of the signed statement.

The signature is Ed25519 over these exact bytes:

```text
ASCII("KWebShell signed runtime release v1\0") || metadata.json UTF-8 bytes
```

The raw 64-byte result is stored in `signature.ed25519`. Ed25519 signing is
deterministic, so identical payload bytes, metadata, and key produce identical
packs.

## Keys and trust

The signer requires both key files explicitly:

- private key: PKCS#8 DER containing an Ed25519 private key;
- trusted public key: X.509 `SubjectPublicKeyInfo` DER containing the matching
  Ed25519 public key.

`keyId` is the lower-case hexadecimal SHA-256 of the exact encoded public-key
DER bytes. The signer checks that the private and public keys correspond before
writing anything. The verifier requires a caller-supplied trusted public key,
recomputes its key ID, and rejects a mismatch. It never downloads keys,
accepts a key embedded in the pack, or tries another key.

## Commands

The internal JVM CLI exposes explicit commands:

```text
release-build <catalog> <target> <product-version> <payload.zip> \
  <private-key.pk8> <public-key.der> <output.pack.zip>

release-verify <catalog> <target> <product-version> <pack.zip> \
  <trusted-public-key.der>
```

Gradle tasks forward absolute paths only. Release signing is intentionally not
part of the normal root `check`, because a production private key must never be
present on a general CI runner. JVM tests generate ephemeral Ed25519 keys and
exercise the same signer and verifier on every platform job.

## Failure boundary

Malformed keys, private/public mismatch, unsupported algorithms, non-canonical
metadata, ZIP envelope changes, payload digest changes, target/version/catalog
mismatch, and invalid nested payloads are typed failures. There is no unsigned
release path, automatic key lookup, alternate algorithm, or reduced verification
mode.
