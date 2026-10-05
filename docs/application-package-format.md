# KWebShell application package format

RFC 0030 packages are built from two independently verified inputs:

1. `runtime/application-manifest.json`, which is the closed application identity
   and association contract. It declares `KWebShell` as the application
   launcher and `KWebShellCef` as the CEF browser subprocess; and
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
application-package-build <application-manifest> <application-asset-root> <runtime-catalog> <target>
  <product-version> <runtime-release> <trusted-public-key>
  <package-private-key> <platform-signature> <output-package>

application-package-verify <application-manifest> <application-asset-root> <runtime-catalog> <target>
  <product-version> <package> <trusted-public-key>
```

The JVM package assembler intentionally does not create or verify a Windows
`.msix`. On Windows it can emit only an internal signed metadata `.zip`; the
public package verifier rejects that archive for a Windows target. CI combines
those verified records with the app-image, then uses Windows SDK `MakeAppx`
and `SignTool`, installs the signed package, launches its application identity,
checks normal shutdown, and uninstalls it. This prevents an ordinary ZIP from
being reported as an installable MSIX.

The target provider emits deterministic registration metadata inside the package:

| Target | Package suffix | Generated records |
| --- | --- | --- |
| macOS | `.zip` | `platform/macos/Info.plist` |
| Windows x64 | `.msix` | Windows SDK package built from the JVM launcher, pinned Temurin JRE, CEF/native payload, associations and visual assets; App SDK notification runtime remains RFC 0016 scope |
| Windows ARM64 | not emitted | No matching Temurin 25 Windows ARM64 JRE is available; x64/emulated fallback is prohibited |
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

The Windows provider must pass Windows SDK `MakeAppx` validation,
`SignTool` signature verification, altered-package rejection, clean install,
observed identity inspection, normal launcher/CEF start and close, and
uninstall on the existing GitHub-hosted `windows-2022` runner. It does not restore or declare a Windows App SDK
dependency and does not claim notification activation; those runtime guarantees
belong to RFC 0016/0006.

The selected Windows entry-point contract is a JVM/Compose launcher named
`KWebShell.exe`. The package carries the pinned Temurin JRE `25.0.4.1+1` at
`runtime/`, with the jpackage classpath in `app/`. The
CEF native host is a separate `KWebShellCef.exe` browser subprocess and is never
the MSIX application entry point. Temurin module license material under
`runtime/legal/` is retained with the runtime; the Windows verifier requires
`java.base/LICENSE` and `java.base/ASSEMBLY_EXCEPTION`, records their SHA-256
values, and confirms the installed package preserves those bytes. Missing
launcher classes, JRE files or license material, CEF subprocess files, or the
exact launcher/subprocess identity fail packaging.

Windows visual assets include 44 by 44, 150 by 150 and 310 by 310 square PNGs,
the 310 by 150 wide PNG, and the 50 by 50 store PNG. All five kinds are required
in the canonical application manifest. The wide tile is declared alongside the
large square tile in AppX `DefaultTile`; dimensions and PNG decoding are checked
before SDK packaging, and the hosted report retains each asset's SHA-256.
The Windows `runtimeCheck` invocation uses `--no-configuration-cache` because
its jpackage app-image tasks capture state that Gradle cannot serialize.
