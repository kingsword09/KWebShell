package io.github.kingsword09.kwebshell.service.tray.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The JDK 25 FFM binding over the versioned tray C ABI. Layouts stay internal:
 * Kotlin owns validation, policy, and lifecycle, and never sees a MemorySegment.
 */
public final class TrayFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;

    public static final int STRUCT_ICON_VARIANT = 2;
    public static final int STRUCT_ITEM_SPEC = 3;
    public static final int STRUCT_MENU_ITEM = 4;
    public static final int STRUCT_MENU_TREE = 5;
    public static final int STRUCT_CAPABILITIES = 6;
    public static final int STRUCT_BOUNDS = 7;
    public static final int STRUCT_EVENT = 8;

    public static final int STATUS_OK = 0;
    public static final int STATUS_NO_EVENT = 16;
    public static final int STATUS_BOUNDS_UNAVAILABLE = 14;

    public static final int EVENT_ACTIVATED = 1;
    public static final int EVENT_MENU_COMMAND = 2;
    public static final int EVENT_BALLOON = 3;
    public static final int EVENT_REMOVED = 4;
    public static final int EVENT_FAILED = 5;

    public static final int ACTIVATION_PRIMARY = 1;
    public static final int ACTIVATION_SECONDARY = 2;
    public static final int ACTIVATION_DOUBLE = 4;

    public static final int MENU_COMMAND = 1;
    public static final int MENU_CHECKBOX = 2;
    public static final int MENU_RADIO = 3;
    public static final int MENU_SEPARATOR = 4;
    public static final int MENU_SUBMENU = 5;
    public static final int MENU_FLAG_ENABLED = 1;
    public static final int MENU_FLAG_VISIBLE = 1 << 1;
    public static final int MENU_FLAG_CHECKED = 1 << 2;

    public static final int CAP_MENU = 1;
    public static final int CAP_TOOLTIP = 1 << 1;
    public static final int CAP_TEMPLATE_ICON = 1 << 2;
    public static final int CAP_ICON_VARIANTS = 1 << 3;
    public static final int CAP_BALLOON = 1 << 4;
    public static final int CAP_BOUNDS = 1 << 5;
    public static final int CAP_EXPLORER_RESTART_RECOVERY = 1 << 6;
    public static final int CAP_WATCHER_RECONNECT = 1 << 7;

    public static final int REMOVAL_CLOSED = 1;
    public static final int REMOVAL_HOST_LOST = 2;
    public static final int REMOVAL_PLATFORM_REMOVED = 3;
    public static final int REMOVAL_OWNER_CLOSED = 4;

    private static final int MAX_ID = 128;
    private static final int MAX_RESOURCE = 128;
    private static final int MAX_TOOLTIP = 64;
    private static final int MAX_VARIANTS = 8;

    private static final GroupLayout STRING_LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("data"),
        JAVA_LONG.withName("size")
    );
    private static final GroupLayout CONFIGURATION_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("application_id"),
        STRING_LAYOUT.withName("package_identity"),
        JAVA_LONG.withName("reserved")
    );
    private static final GroupLayout ICON_VARIANT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("scale"),
        JAVA_INT.withName("template_icon"),
        STRING_LAYOUT.withName("resource_id"),
        STRING_LAYOUT.withName("sha256"),
        STRING_LAYOUT.withName("pixels"),
        JAVA_INT.withName("width"),
        JAVA_INT.withName("height")
    );
    private static final GroupLayout ITEM_SPEC_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("activation_bits"),
        JAVA_INT.withName("icon_variant_count"),
        STRING_LAYOUT.withName("id"),
        STRING_LAYOUT.withName("tooltip"),
        sequenceLayout(MAX_VARIANTS, ICON_VARIANT_LAYOUT).withName("icon_variants")
    );
    private static final GroupLayout MENU_ITEM_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("kind"),
        JAVA_INT.withName("flags"),
        JAVA_INT.withName("submenu_count"),
        JAVA_INT.withName("reserved"),
        STRING_LAYOUT.withName("id"),
        STRING_LAYOUT.withName("label")
    );
    private static final GroupLayout MENU_TREE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_LONG.withName("version"),
        JAVA_INT.withName("item_count"),
        JAVA_INT.withName("reserved"),
        STRING_LAYOUT.withName("menu_id"),
        ADDRESS.withName("items")
    );
    private static final GroupLayout CAPABILITIES_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("flags"),
        JAVA_INT.withName("activation_bits"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("provider_id")
    );
    private static final GroupLayout BOUNDS_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("x"),
        JAVA_INT.withName("y"),
        JAVA_INT.withName("width"),
        JAVA_INT.withName("height")
    );
    private static final GroupLayout EVENT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("kind"),
        JAVA_INT.withName("removal_reason"),
        JAVA_INT.withName("activation"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("sequence"),
        JAVA_LONG.withName("tree_version"),
        sequenceLayout(MAX_ID + 1, JAVA_BYTE).withName("item_id"),
        sequenceLayout(MAX_ID + 1, JAVA_BYTE).withName("menu_id"),
        sequenceLayout(MAX_ID + 1, JAVA_BYTE).withName("command_id"),
        sequenceLayout(96, JAVA_BYTE).withName("code"),
        MemoryLayout.paddingLayout(5)
    );

    private static final ConcurrentHashMap<Path, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    /** One icon variant as the ABI publishes it. */
    public record FlatVariant(
        int scale,
        boolean template,
        String resourceId,
        String sha256,
        byte[] pixels,
        int width,
        int height
    ) {
        public FlatVariant {
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(pixels, "pixels");
        }
    }

    /** One flat item declaration. */
    public record FlatItem(String id, String tooltip, int activationBits, List<FlatVariant> variants) {
        public FlatItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(tooltip, "tooltip");
            Objects.requireNonNull(variants, "variants");
        }
    }

    /** One flat preorder menu node. */
    public record FlatMenuItem(String id, String label, int kind, int flags, int childCount) {
        public FlatMenuItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    /** One immutable menu tree snapshot. */
    public record FlatTree(String menuId, long version, List<FlatMenuItem> items) {
        public FlatTree {
            Objects.requireNonNull(menuId, "menuId");
            Objects.requireNonNull(items, "items");
        }
    }

    public record Capabilities(long flags, long activationBits, String providerId) {}

    public record Bounds(int x, int y, int width, int height) {}

    public record Event(
        int kind,
        int removalReason,
        int activation,
        long sequence,
        long treeVersion,
        String itemId,
        String menuId,
        String commandId,
        String code
    ) {}

    /** One typed native failure; Kotlin maps the status to a published code. */
    public static final class TrayNativeException extends RuntimeException {
        public final int status;

        TrayNativeException(String operation, int status, String statusName) {
            super("The tray " + operation + " call failed with " + statusName + " (" + status + ").");
            this.status = status;
        }
    }

    private final MethodHandle capabilities;
    private final MethodHandle setItem;
    private final MethodHandle closeItem;
    private final MethodHandle setMenu;
    private final MethodHandle bounds;
    private final MethodHandle pollEvent;
    private final MethodHandle closeHandle;
    private final MethodHandle statusName;
    private final String providerId;
    private boolean closed;

    private TrayFfm(
        MethodHandle capabilities,
        MethodHandle setItem,
        MethodHandle closeItem,
        MethodHandle setMenu,
        MethodHandle bounds,
        MethodHandle pollEvent,
        MethodHandle closeHandle,
        MethodHandle statusName,
        String providerId
    ) {
        this.capabilities = capabilities;
        this.setItem = setItem;
        this.closeItem = closeItem;
        this.setMenu = setMenu;
        this.bounds = bounds;
        this.pollEvent = pollEvent;
        this.closeHandle = closeHandle;
        this.statusName = statusName;
        this.providerId = providerId;
    }

    public static TrayFfm open(Path requestedPath, String applicationId, String packageIdentity) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(packageIdentity, "packageIdentity");
        if (!requestedPath.isAbsolute() || !requestedPath.normalize().equals(requestedPath) ||
            !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException("The tray native library must be an absolute regular file.");
        }
        try {
            final SymbolLookup lookup = LIBRARIES.computeIfAbsent(
                requestedPath.toRealPath(), path -> SymbolLookup.libraryLookup(path, Arena.global())
            );
            final MethodHandle abi = downcall(lookup, "kweb_tray_abi_version", FunctionDescriptor.of(JAVA_INT));
            if ((int) abi.invokeExact() != ABI_VERSION) {
                throw new IllegalStateException("KWebTrays ABI version mismatch.");
            }
            final MethodHandle structSize = downcall(
                lookup, "kweb_tray_struct_size", FunctionDescriptor.of(JAVA_INT, JAVA_INT)
            );
            requireStructSize(structSize, STRUCT_ICON_VARIANT, ICON_VARIANT_LAYOUT);
            requireStructSize(structSize, STRUCT_ITEM_SPEC, ITEM_SPEC_LAYOUT);
            requireStructSize(structSize, STRUCT_MENU_ITEM, MENU_ITEM_LAYOUT);
            requireStructSize(structSize, STRUCT_MENU_TREE, MENU_TREE_LAYOUT);
            requireStructSize(structSize, STRUCT_CAPABILITIES, CAPABILITIES_LAYOUT);
            requireStructSize(structSize, STRUCT_BOUNDS, BOUNDS_LAYOUT);
            requireStructSize(structSize, STRUCT_EVENT, EVENT_LAYOUT);
            final MethodHandle statusName = downcall(
                lookup, "kweb_tray_status_name", FunctionDescriptor.of(ADDRESS, JAVA_INT)
            );
            final MethodHandle openHandle = downcall(
                lookup, "kweb_tray_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            final String providerId = readCString((MemorySegment) downcall(
                lookup, "kweb_tray_provider_id", FunctionDescriptor.of(ADDRESS)
            ).invokeExact());
            try (Arena arena = Arena.ofConfined()) {
                final MemorySegment configuration = arena.allocate(CONFIGURATION_LAYOUT);
                configuration.set(JAVA_INT, CONFIGURATION_LAYOUT.byteOffset(groupElement("struct_size")),
                    (int) CONFIGURATION_LAYOUT.byteSize());
                configuration.set(JAVA_INT, CONFIGURATION_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
                writeString(arena, configuration, CONFIGURATION_LAYOUT, "application_id", applicationId);
                writeString(arena, configuration, CONFIGURATION_LAYOUT, "package_identity", packageIdentity);
                final MemorySegment handle = arena.allocate(JAVA_LONG);
                final int status = (int) openHandle.invokeExact(configuration, handle);
                if (status != STATUS_OK) {
                    throw new IllegalStateException(
                        "The tray provider could not open: "
                            + readCString((MemorySegment) statusName.invokeExact(status)) + "."
                    );
                }
            }
            return new TrayFfm(
                downcall(lookup, "kweb_tray_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                downcall(lookup, "kweb_tray_set_item", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                downcall(lookup, "kweb_tray_close_item",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, STRING_LAYOUT)),
                downcall(lookup, "kweb_tray_set_menu",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, STRING_LAYOUT, ADDRESS)),
                downcall(lookup, "kweb_tray_bounds",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, STRING_LAYOUT, ADDRESS)),
                downcall(lookup, "kweb_tray_poll_event", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                downcall(lookup, "kweb_tray_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                statusName,
                providerId
            );
        } catch (Throwable error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("KWebTrays native ABI could not be bound.", error);
        }
    }

    public String providerId() {
        return providerId;
    }

    public String statusName(int status) {
        try {
            return readCString((MemorySegment) statusName.invokeExact(status));
        } catch (Throwable error) {
            return "unknown";
        }
    }

    public Capabilities capabilities() {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(CAPABILITIES_LAYOUT);
            result.set(JAVA_INT, CAPABILITIES_LAYOUT.byteOffset(groupElement("struct_size")),
                (int) CAPABILITIES_LAYOUT.byteSize());
            result.set(JAVA_INT, CAPABILITIES_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
            final int status = (int) capabilities.invokeExact(1L, result);
            if (status != STATUS_OK) throw nativeFailure("capabilities", status);
            return new Capabilities(
                result.get(JAVA_INT, CAPABILITIES_LAYOUT.byteOffset(groupElement("flags"))) & 0xffffffffL,
                result.get(JAVA_INT, CAPABILITIES_LAYOUT.byteOffset(groupElement("activation_bits"))) & 0xffffffffL,
                readFixedCString(result, CAPABILITIES_LAYOUT.byteOffset(groupElement("provider_id")), MAX_ID)
            );
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The tray capabilities call failed.", error);
        }
    }

    /** Returns true when the item was created and false when it was updated. */
    public boolean setItem(FlatItem item) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment spec = arena.allocate(ITEM_SPEC_LAYOUT);
            spec.set(JAVA_INT, ITEM_SPEC_LAYOUT.byteOffset(groupElement("struct_size")),
                (int) ITEM_SPEC_LAYOUT.byteSize());
            spec.set(JAVA_INT, ITEM_SPEC_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
            spec.set(JAVA_INT, ITEM_SPEC_LAYOUT.byteOffset(groupElement("activation_bits")), item.activationBits());
            spec.set(JAVA_INT, ITEM_SPEC_LAYOUT.byteOffset(groupElement("icon_variant_count")), item.variants().size());
            writeString(arena, spec, ITEM_SPEC_LAYOUT, "id", item.id());
            writeString(arena, spec, ITEM_SPEC_LAYOUT, "tooltip", item.tooltip());
            final long variantStride = ICON_VARIANT_LAYOUT.byteSize();
            final long variantsOffset = ITEM_SPEC_LAYOUT.byteOffset(groupElement("icon_variants"));
            for (int index = 0; index < item.variants().size(); index += 1) {
                final FlatVariant variant = item.variants().get(index);
                final MemorySegment target = spec.asSlice(variantsOffset + index * variantStride, variantStride);
                target.set(JAVA_INT, ICON_VARIANT_LAYOUT.byteOffset(groupElement("struct_size")),
                    (int) ICON_VARIANT_LAYOUT.byteSize());
                target.set(JAVA_INT, ICON_VARIANT_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
                target.set(JAVA_INT, ICON_VARIANT_LAYOUT.byteOffset(groupElement("scale")), variant.scale());
                target.set(JAVA_INT, ICON_VARIANT_LAYOUT.byteOffset(groupElement("template_icon")),
                    variant.template() ? 1 : 0);
                writeString(arena, target, ICON_VARIANT_LAYOUT, "resource_id", variant.resourceId());
                writeString(arena, target, ICON_VARIANT_LAYOUT, "sha256", variant.sha256());
                final MemorySegment pixels = arena.allocate(variant.pixels().length);
                MemorySegment.copy(variant.pixels(), 0, pixels, JAVA_BYTE, 0, variant.pixels().length);
                target.set(ADDRESS,
                    ICON_VARIANT_LAYOUT.byteOffset(groupElement("pixels"), groupElement("data")), pixels);
                target.set(JAVA_LONG, ICON_VARIANT_LAYOUT.byteOffset(groupElement("pixels"), groupElement("size")),
                    variant.pixels().length);
                target.set(JAVA_INT, ICON_VARIANT_LAYOUT.byteOffset(groupElement("width")), variant.width());
                target.set(JAVA_INT, ICON_VARIANT_LAYOUT.byteOffset(groupElement("height")), variant.height());
            }
            final MemorySegment created = arena.allocate(JAVA_INT);
            final int status = (int) setItem.invokeExact(1L, spec, created);
            if (status != STATUS_OK) throw nativeFailure("set-item", status);
            return created.get(JAVA_INT, 0) == 1;
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The tray set-item call failed.", error);
        }
    }

    public void closeItem(String itemId) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment id = writeBareString(arena, itemId);
            final int status = (int) closeItem.invokeExact(1L, id);
            if (status != STATUS_OK) throw nativeFailure("close-item", status);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The tray close-item call failed.", error);
        }
    }

    public void setMenu(String itemId, FlatTree tree) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment id = writeBareString(arena, itemId);
            final MemorySegment nativeTree = tree == null ? MemorySegment.NULL : writeTree(arena, tree);
            final int status = (int) setMenu.invokeExact(1L, id, nativeTree);
            if (status != STATUS_OK) throw nativeFailure("set-menu", status);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The tray set-menu call failed.", error);
        }
    }

    /** Returns null when the platform publishes no item bounds. */
    public Bounds bounds(String itemId) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment id = writeBareString(arena, itemId);
            final MemorySegment result = arena.allocate(BOUNDS_LAYOUT);
            result.set(JAVA_INT, BOUNDS_LAYOUT.byteOffset(groupElement("struct_size")), (int) BOUNDS_LAYOUT.byteSize());
            result.set(JAVA_INT, BOUNDS_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
            final int status = (int) bounds.invokeExact(1L, id, result);
            if (status == STATUS_BOUNDS_UNAVAILABLE) return null;
            if (status != STATUS_OK) throw nativeFailure("bounds", status);
            return new Bounds(
                result.get(JAVA_INT, BOUNDS_LAYOUT.byteOffset(groupElement("x"))),
                result.get(JAVA_INT, BOUNDS_LAYOUT.byteOffset(groupElement("y"))),
                result.get(JAVA_INT, BOUNDS_LAYOUT.byteOffset(groupElement("width"))),
                result.get(JAVA_INT, BOUNDS_LAYOUT.byteOffset(groupElement("height")))
            );
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The tray bounds call failed.", error);
        }
    }

    /** Returns the next ordered event, or null when the queue is empty. */
    public Event pollEvent() {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment event = arena.allocate(EVENT_LAYOUT);
            event.set(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("struct_size")), (int) EVENT_LAYOUT.byteSize());
            event.set(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
            final int status = (int) pollEvent.invokeExact(1L, event);
            if (status == STATUS_NO_EVENT) return null;
            if (status != STATUS_OK) throw nativeFailure("poll-event", status);
            return new Event(
                event.get(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("kind"))),
                event.get(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("removal_reason"))),
                event.get(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("activation"))),
                event.get(JAVA_LONG, EVENT_LAYOUT.byteOffset(groupElement("sequence"))),
                event.get(JAVA_LONG, EVENT_LAYOUT.byteOffset(groupElement("tree_version"))),
                readFixedCString(event, EVENT_LAYOUT.byteOffset(groupElement("item_id")), MAX_ID + 1),
                readFixedCString(event, EVENT_LAYOUT.byteOffset(groupElement("menu_id")), MAX_ID + 1),
                readFixedCString(event, EVENT_LAYOUT.byteOffset(groupElement("command_id")), MAX_ID + 1),
                readFixedCString(event, EVENT_LAYOUT.byteOffset(groupElement("code")), 96)
            );
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The tray event poll failed.", error);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            closeHandle.invokeExact(1L);
        } catch (Throwable ignored) {
            // A second close is a typed OWNER_CLOSED status, not a second native call.
        }
    }

    private static MemorySegment writeTree(Arena arena, FlatTree tree) {
        final MemorySegment items = arena.allocate(MENU_ITEM_LAYOUT, tree.items().size());
        for (int index = 0; index < tree.items().size(); index += 1) {
            final FlatMenuItem item = tree.items().get(index);
            final MemorySegment target = items.asSlice(index * MENU_ITEM_LAYOUT.byteSize(), MENU_ITEM_LAYOUT.byteSize());
            target.set(JAVA_INT, MENU_ITEM_LAYOUT.byteOffset(groupElement("struct_size")),
                (int) MENU_ITEM_LAYOUT.byteSize());
            target.set(JAVA_INT, MENU_ITEM_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
            target.set(JAVA_INT, MENU_ITEM_LAYOUT.byteOffset(groupElement("kind")), item.kind());
            target.set(JAVA_INT, MENU_ITEM_LAYOUT.byteOffset(groupElement("flags")), item.flags());
            target.set(JAVA_INT, MENU_ITEM_LAYOUT.byteOffset(groupElement("submenu_count")), item.childCount());
            writeString(arena, target, MENU_ITEM_LAYOUT, "id", item.id());
            writeString(arena, target, MENU_ITEM_LAYOUT, "label", item.label());
        }
        final MemorySegment treeSegment = arena.allocate(MENU_TREE_LAYOUT);
        treeSegment.set(JAVA_INT, MENU_TREE_LAYOUT.byteOffset(groupElement("struct_size")),
            (int) MENU_TREE_LAYOUT.byteSize());
        treeSegment.set(JAVA_INT, MENU_TREE_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
        treeSegment.set(JAVA_LONG, MENU_TREE_LAYOUT.byteOffset(groupElement("version")), tree.version());
        treeSegment.set(JAVA_INT, MENU_TREE_LAYOUT.byteOffset(groupElement("item_count")), tree.items().size());
        writeString(arena, treeSegment, MENU_TREE_LAYOUT, "menu_id", tree.menuId());
        treeSegment.set(ADDRESS, MENU_TREE_LAYOUT.byteOffset(groupElement("items")), items);
        return treeSegment;
    }

    private static void writeString(Arena arena, MemorySegment target, GroupLayout layout, String field, String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            target.set(ADDRESS, layout.byteOffset(groupElement(field), groupElement("data")), MemorySegment.NULL);
            target.set(JAVA_LONG, layout.byteOffset(groupElement(field), groupElement("size")), 0L);
            return;
        }
        final MemorySegment segment = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, segment, JAVA_BYTE, 0, bytes.length);
        target.set(ADDRESS, layout.byteOffset(groupElement(field), groupElement("data")), segment);
        target.set(JAVA_LONG, layout.byteOffset(groupElement(field), groupElement("size")), bytes.length);
    }

    private static MemorySegment writeBareString(Arena arena, String value) {
        final MemorySegment target = arena.allocate(STRING_LAYOUT);
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) {
            target.set(ADDRESS, STRING_LAYOUT.byteOffset(groupElement("data")), MemorySegment.NULL);
            target.set(JAVA_LONG, STRING_LAYOUT.byteOffset(groupElement("size")), 0L);
            return target;
        }
        final MemorySegment segment = arena.allocate(bytes.length);
        MemorySegment.copy(bytes, 0, segment, JAVA_BYTE, 0, bytes.length);
        target.set(ADDRESS, STRING_LAYOUT.byteOffset(groupElement("data")), segment);
        target.set(JAVA_LONG, STRING_LAYOUT.byteOffset(groupElement("size")), bytes.length);
        return target;
    }

    private static String readFixedCString(MemorySegment segment, long offset, int capacity) {
        return segment.asSlice(offset, capacity).getString(0);
    }

    private static void requireStructSize(MethodHandle structSize, int which, GroupLayout layout) throws Throwable {
        final int actual = (int) structSize.invokeExact(which);
        if (actual != layout.byteSize()) {
            throw new IllegalStateException(
                "The tray provider struct " + which + " is " + actual + " bytes, expected " + layout.byteSize() + "."
            );
        }
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return lookup.find(name)
            .map(symbol -> java.lang.foreign.Linker.nativeLinker().downcallHandle(symbol, descriptor))
            .orElseThrow(() -> new IllegalStateException("The tray provider does not export " + name + "."));
    }

    private static String readCString(MemorySegment address) {
        if (address == null || address.equals(MemorySegment.NULL)) return "";
        return address.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private TrayNativeException nativeFailure(String operation, int status) {
        return new TrayNativeException(operation, status, statusName(status));
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("The tray provider handle is closed.");
    }
}
