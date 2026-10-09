# KWebShell Menus Service

`kweb-service-menus` implements the RFC 0017 typed command-tree contract for
application, window, and context menus. Kotlin owns command handling, window
association, validation, capabilities, and event ordering; each advertised
target contributes one native provider behind a small versioned C ABI and the
JDK 25 FFM binding in `internal/MenuFfm.java`.

The published v1 surface contains:

- one immutable menu tree per owner (`application`, `window`, `page`) with stable
  command identifiers, labels, roles, accelerators, icons, toggle state, and
  atomic versioned replacement;
- ordered, replayable invocation and dismissal events with their tree version
  and owner;
- declared page menus plus anchored popups for host callers and one
  exact-origin renderer operation (`show-declared-popup`) that can only present
  a menu the host already declared;
- declared provider capabilities, including which roles the platform owns
  natively; and
- typed errors for every rejected tree, owner, version, and anchor.

Icons resolve through RFC 0027 package resources only: the host installs a
`KWebMenuIconResolver`, the service copies straight RGBA8 pixels, and renderer
bytes, URLs, and filesystem paths are never menu inputs.

Platform boundaries are explicit. A provider advertises `MNEMONICS` only where
its toolkit renders them, so a tree that declares a mnemonic on macOS fails with
`menus.mnemonic-unsupported` instead of dropping the hint. Popup presentation
owns an immutable snapshot of the tree version that is current when it opens; a
replacement tree applies to the next presentation.

```kotlin
import java.nio.file.Path

val menus = JvmKWebMenus.open(
    applicationId = "io.example.app",
    nativeLibrary = Path.of("/absolute/path/to/libkwebshell_menus"),
    windows = listOf(KWebMenuWindowBinding(KWebMenuWindowId("main-window"), composeWindow)),
)
engine.nativeServices.install(KWebMenus.Key, menus)

menus.setApplicationMenu(
    KWebMenuTree(
        KWebMenuId("app.menu"),
        1,
        listOf(
            KWebMenuItem.Submenu(
                KWebMenuId("file"),
                "File",
                listOf(
                    KWebMenuItem.Command(
                        KWebMenuId("file.open"),
                        "Open",
                        accelerator = KWebMenuAccelerator(listOf(KWebMenuModifier.PRIMARY), KWebMenuKey.O),
                    ),
                ),
            ),
        ),
    ),
)
```

## Verification

```shell
npm ci
./gradlew :kweb-service-menus:check
```

The task runs common contract tests, the real native C ABI tests, strict
generated TypeScript, the packaged provider ZIP check, and the Compose/native
integration fixture that applies a full-featured tree to the real provider and
asserts typed rejection for stale versions, undeclared page menus, unknown
windows, and unsupported mnemonic rendering.
