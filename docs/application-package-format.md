# KWebShell application package format

RFC 0030 packages are built from two independently verified inputs:

1. `runtime/application-manifest.json`, which is the closed application identity
   and association contract; and
2. an RFC 0001 signed runtime release pack, which is the only accepted runtime
   payload.

The package builder requires one explicit target and one canonical
`signatures/platform.json` record. It never discovers another target, signer,
installer format, or runtime payload. Test signing facts use
`mode: "TEST"`; they cannot be used as a release-signing claim.

The source manifest can be checked without a CEF runtime:

```shell
./gradlew --no-daemon :kweb-runtime-pack:verifyApplicationManifest
```

The package CLI accepts these explicit commands:

```text
application-package-build <application-manifest> <runtime-catalog> <target>
  <product-version> <runtime-release> <trusted-public-key>
  <package-private-key> <platform-signature> <output-package>

application-package-verify <application-manifest> <runtime-catalog> <target>
  <product-version> <package> <trusted-public-key>
```

The target provider emits deterministic registration metadata inside the package:

| Target | Package suffix | Generated records |
| --- | --- | --- |
| macOS | `.zip` | `platform/macos/Info.plist` |
| Windows | `.msix` | `platform/windows/AppxManifest.xml` |
| Linux | `.deb` | desktop entry, MIME XML, and AppStream metainfo |

The suffix is not a fallback selector. It is checked against the target manifest
and the package is rejected when the declared provider facts do not match. The
independent verifier checks the exact entry set, fixed timestamps and modes,
UTF-8 lexical order, CRC/size, strict record encoding, target identity,
capability/SBOM equality, nested runtime signature, and immutable
`isPackaged: true` state.

Electron builder/forge metadata is mapped by
`KWebElectronPackagingMapper`. Supported identity, target, protocol, and file
association declarations become a report; unsupported hooks are blocking and
are never executed.
