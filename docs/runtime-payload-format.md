# KWebShell runtime payload format

## Status and scope

Schema version 1 is the deterministic unsigned content boundary for one
KWebShell desktop target. It is an internal build artifact. It is not a release
artifact, has no authenticity claim, and must not be published or selected by
an updater. Objective 7.2 wraps these exact bytes in the authenticated
[`runtime-release-format.md`](runtime-release-format.md) boundary.

The builder accepts exactly these absolute, normalized inputs:

- the extracted CEF directory whose name exactly matches the selected artifact
  in `runtime/cef-runtime.json`;
- the configured native CMake `Release` directory;
- the configured native CMake `contract` directory; and
- one `.zip` output path whose parent exists.

The CEF root must contain non-empty regular `LICENSE.txt` and `CREDITS.html`
files. Inputs are never inferred from the system, another target, or a cache.
The builder writes a sibling temporary file, independently verifies it, and
then uses one atomic replace operation. An unsupported atomic move is a typed
failure; there is no non-atomic publication path.

## Archive tree

The ZIP contains exactly one canonical `manifest.json` and these payload roots:

```text
licenses/
  CEF-CREDITS.html
  CEF-LICENSE.txt
native/
  ... exact target binding closure ...
runtime/
  ... configured native Release runtime ...
```

On macOS, `runtime/` contains only `KWebShell.app` and its descendants. The
standalone helper applications produced beside the main bundle are duplicate
build outputs and are not packaged; the helper copies inside
`KWebShell.app/Contents/Frameworks` are packaged. Windows and Linux package the
complete flat `Release` tree below `runtime/`.

`native/` contains no tests, import libraries, object files, or static
libraries. Its exact closure is:

| Target OS | Entries |
| --- | --- |
| Windows | `kwebshell_engine.dll`, `kwebshell_jni.dll` |
| macOS | `libkwebshell_engine.1.0.0.dylib`, `libkwebshell_engine.1.dylib -> libkwebshell_engine.1.0.0.dylib`, `libkwebshell_engine.dylib -> libkwebshell_engine.1.dylib`, `libkwebshell_jni.dylib` |
| Linux | `libkwebshell_engine.so.1.0.0`, `libkwebshell_engine.so.1 -> libkwebshell_engine.so.1.0.0`, `libkwebshell_engine.so -> libkwebshell_engine.so.1`, `libkwebshell_jni.so` |

The architecture is identified by `manifest.json`; library names are shared by
x64 and arm64 for each operating system.

## Manifest

`manifest.json` is UTF-8 JSON produced with declaration-order fields, all
default values, omitted nulls, pretty printing, and exactly one trailing LF.
The verifier decodes it with unknown fields disabled and requires byte-for-byte
equality with a canonical re-encoding.

It records:

- `schemaVersion`, `product`, `productVersion`, and `target`;
- the pinned `cefVersion` and `chromiumVersion`;
- the source catalog artifact file name, byte size, checksum algorithm, and
  checksum;
- `treeSha256`; and
- every payload entry except `manifest.json`, in lexical unsigned UTF-8 order.

Each entry records `path`, `type`, normalized four-digit octal `mode`, byte
`size`, lowercase `sha256`, and `linkTarget` only for a symbolic link.
Directory size is zero and its digest is the SHA-256 of empty bytes. A symbolic
link's size and digest cover the UTF-8 bytes of its relative link target, not
the linked file.

`treeSha256` is calculated as follows:

1. Initialize SHA-256 with UTF-8 bytes for
   `KWebShell runtime payload tree v1`, followed by one NUL byte.
2. Visit manifest entries in their declared lexical order.
3. For each entry, feed these UTF-8 fields in order, placing one NUL byte after
   every field: path, lowercase type (`directory`, `file`, or `symlink`), mode,
   base-10 size, lowercase SHA-256, and link target or the empty string.
4. Encode the final digest as 64 lowercase hexadecimal characters.

Payload paths cannot be absolute, contain `.` or `..` segments, use backslash
or drive/ADS syntax, contain control characters, or leave the three declared
roots. Symbolic-link targets must be relative and must resolve lexically inside
their own `runtime/` or `native/` root. Walks never follow symbolic links.

## Canonical ZIP encoding

- Entry names are unique UTF-8 and appear in lexical unsigned UTF-8 order in
  both local headers and the central directory.
- Every entry has DOS local timestamp `2000-01-01 00:00:00`; input mtimes and
  the builder timezone are ignored.
- Directories are stored with mode `0755`; executable files use `0755`;
  regular files use `0644`; symbolic links use `0777`. Unix file-type bits are
  present in central-directory external attributes.
- Directories use `STORED`. All files, symbolic links, and `manifest.json` use
  raw DEFLATE at level 9.
- The UTF-8 name flag is the only general-purpose flag. Data descriptors,
  encryption, comments, extra fields, preambles, trailing data, multiple
  disks, and ZIP64 are forbidden.

The independent verifier streams every entry, recomputes CRC-32 and SHA-256,
and compares type, mode, size, digest, and link target with `manifest.json`. It
also validates the EOCD extent and central-directory offset directly before
opening the ZIP.

## Build and verification

The host task builds the real native output first, assembles the payload,
verifies the temporary archive before publication, and then reopens the
published archive in a separate verification task:

```shell
./gradlew :kweb-runtime-pack:verifyHostRuntimePayload \
  -PcefRoot=/absolute/path/to/cef_binary_151.3.16+gbe1e15d+chromium-151.0.7922.109_macosarm64_minimal
```

The output is written below `kweb-runtime-pack/build/runtime-payload/`. The root
`check` task depends on this real host verification. Standalone
`:kweb-runtime-pack:check` runs format and corruption tests without requiring a
CEF extraction, which keeps source-build prerequisite verification independent
from native runtime production.
