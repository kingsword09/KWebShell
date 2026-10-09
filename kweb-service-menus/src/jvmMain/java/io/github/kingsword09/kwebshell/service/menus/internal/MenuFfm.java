package io.github.kingsword09.kwebshell.service.menus.internal;

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
 * The JDK 25 FFM binding over the versioned menus C ABI. Layouts stay internal:
 * Kotlin owns validation, policy and lifecycle, and never sees a MemorySegment.
 */
public final class MenuFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;

    public static final int STATUS_NO_EVENT = 19;

    public static final int STRUCT_ITEM = 2;
    public static final int STRUCT_TREE = 3;
    public static final int STRUCT_POPUP_REQUEST = 4;
    public static final int STRUCT_POPUP_RESULT = 5;
    public static final int STRUCT_CAPABILITIES = 6;
    public static final int STRUCT_EVENT = 7;

    public static final int OUTCOME_INVOKED = 1;
    public static final int OUTCOME_DISMISSED = 2;

    public static final int OWNER_APPLICATION = 1;
    public static final int OWNER_WINDOW = 2;
    public static final int OWNER_PAGE = 3;

    public static final int POPUP_SOURCE_HOST = 1;
    public static final int POPUP_SOURCE_RENDERER = 2;
    public static final int POPUP_SOURCE_PAGE_CONTEXT = 3;

    public static final int KIND_COMMAND = 1;
    public static final int KIND_CHECKBOX = 2;
    public static final int KIND_RADIO = 3;
    public static final int KIND_SEPARATOR = 4;
    public static final int KIND_SUBMENU = 5;

    public static final int FLAG_ENABLED = 1 << 0;
    public static final int FLAG_VISIBLE = 1 << 1;
    public static final int FLAG_CHECKED = 1 << 2;
    public static final int FLAG_TEMPLATE_ICON = 1 << 3;

    public static final int SOURCE_APPLICATION_MENU = 1;
    public static final int SOURCE_WINDOW_MENU = 2;
    public static final int SOURCE_POPUP = 3;
    public static final int SOURCE_PAGE_MENU = 4;

    public static final int DISMISS_USER = 1;
    public static final int DISMISS_REPLACED = 2;
    public static final int DISMISS_OWNER_CLOSED = 3;
    public static final int DISMISS_CANCELLED = 4;
    public static final int DISMISS_OWNER_MOVED = 5;
    public static final int DISMISS_NATIVE = 6;

    public static final int EVENT_INVOKED = 1;
    public static final int EVENT_DISMISSED = 2;
    public static final int EVENT_FAILED = 3;

    private static final int MAX_ID = 128;

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
    private static final GroupLayout ITEM_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("kind"),
        JAVA_INT.withName("flags"),
        JAVA_INT.withName("role"),
        JAVA_INT.withName("mnemonic"),
        JAVA_INT.withName("accelerator_modifiers"),
        JAVA_INT.withName("accelerator_key"),
        STRING_LAYOUT.withName("id"),
        STRING_LAYOUT.withName("label"),
        STRING_LAYOUT.withName("icon_id"),
        STRING_LAYOUT.withName("icon_sha256"),
        STRING_LAYOUT.withName("icon_pixels"),
        JAVA_INT.withName("icon_width"),
        JAVA_INT.withName("icon_height"),
        JAVA_INT.withName("submenu_count"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout TREE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_LONG.withName("version"),
        JAVA_INT.withName("item_count"),
        JAVA_INT.withName("reserved"),
        STRING_LAYOUT.withName("menu_id"),
        ADDRESS.withName("items")
    );
    private static final GroupLayout POPUP_REQUEST_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("menu_id"),
        JAVA_INT.withName("owner_kind"),
        JAVA_INT.withName("source"),
        STRING_LAYOUT.withName("owner_id"),
        JAVA_INT.withName("screen_x"),
        JAVA_INT.withName("screen_y"),
        JAVA_LONG.withName("native_window")
    );
    private static final GroupLayout POPUP_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("outcome"),
        JAVA_INT.withName("dismiss_reason"),
        JAVA_LONG.withName("tree_version"),
        sequenceLayout(MAX_ID + 1, JAVA_BYTE).withName("popup_id"),
        sequenceLayout(MAX_ID + 1, JAVA_BYTE).withName("command_id"),
        sequenceLayout(96, JAVA_BYTE).withName("code"),
        MemoryLayout.paddingLayout(6)
    );
    private static final GroupLayout CAPABILITIES_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("flags"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("native_roles")
    );
    private static final GroupLayout EVENT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("kind"),
        JAVA_INT.withName("dismiss_reason"),
        JAVA_INT.withName("owner_kind"),
        JAVA_INT.withName("source"),
        JAVA_LONG.withName("sequence"),
        JAVA_LONG.withName("tree_version"),
        sequenceLayout(MAX_ID + 1, JAVA_BYTE).withName("owner_id"),
        sequenceLayout(MAX_ID + 1, JAVA_BYTE).withName("popup_id"),
        sequenceLayout(MAX_ID + 1, JAVA_BYTE).withName("command_id"),
        sequenceLayout(96, JAVA_BYTE).withName("code"),
        MemoryLayout.paddingLayout(5)
    );

    private static final ConcurrentHashMap<Path, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    /** One flat preorder item as the C ABI publishes it. */
    public record FlatItem(
        String id,
        String label,
        int kind,
        int flags,
        int role,
        int mnemonic,
        int acceleratorModifiers,
        int acceleratorKey,
        String iconId,
        String iconSha256,
        byte[] iconPixels,
        int iconWidth,
        int iconHeight,
        int childCount
    ) {
        public FlatItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            iconId = iconId == null ? "" : iconId;
            iconSha256 = iconSha256 == null ? "" : iconSha256;
        }
    }

    /** One immutable flat tree snapshot. */
    public record FlatTree(String menuId, long version, List<FlatItem> items) {
        public FlatTree {
            Objects.requireNonNull(menuId, "menuId");
            Objects.requireNonNull(items, "items");
        }
    }

    public record Capabilities(long flags, long nativeRoles) {}

    public record PopupResult(
        int outcome,
        int dismissReason,
        long treeVersion,
        String popupId,
        String commandId,
        String code
    ) {}

    public record Event(
        int kind,
        int dismissReason,
        int ownerKind,
        int source,
        long sequence,
        long treeVersion,
        String ownerId,
        String popupId,
        String commandId,
        String code
    ) {}

    private final MethodHandle capabilities;
    private final MethodHandle setApplicationMenu;
    private final MethodHandle setWindowMenu;
    private final MethodHandle declarePageMenu;
    private final MethodHandle clearPageMenu;
    private final MethodHandle showPopup;
    private final MethodHandle pollEvent;
    private final MethodHandle closeHandle;
    private final MethodHandle statusName;
    private final String providerId;
    private boolean closed;

    private MenuFfm(
        MethodHandle capabilities,
        MethodHandle setApplicationMenu,
        MethodHandle setWindowMenu,
        MethodHandle declarePageMenu,
        MethodHandle clearPageMenu,
        MethodHandle showPopup,
        MethodHandle pollEvent,
        MethodHandle closeHandle,
        MethodHandle statusName,
        String providerId
    ) {
        this.capabilities = capabilities;
        this.setApplicationMenu = setApplicationMenu;
        this.setWindowMenu = setWindowMenu;
        this.declarePageMenu = declarePageMenu;
        this.clearPageMenu = clearPageMenu;
        this.showPopup = showPopup;
        this.pollEvent = pollEvent;
        this.closeHandle = closeHandle;
        this.statusName = statusName;
        this.providerId = providerId;
    }

    public static MenuFfm open(Path requestedPath, String applicationId, String packageIdentity) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(packageIdentity, "packageIdentity");
        if (!requestedPath.isAbsolute() || !requestedPath.normalize().equals(requestedPath) ||
            !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException("The menus native library must be an absolute regular file.");
        }
        try {
            final SymbolLookup lookup = LIBRARIES.computeIfAbsent(
                requestedPath.toRealPath(), path -> SymbolLookup.libraryLookup(path, Arena.global())
            );
            final MethodHandle abi = downcall(lookup, "kweb_menus_abi_version", FunctionDescriptor.of(JAVA_INT));
            if ((int) abi.invokeExact() != ABI_VERSION) {
                throw new IllegalStateException("KWebMenus ABI version mismatch.");
            }
            final MethodHandle structSize = downcall(
                lookup, "kweb_menus_struct_size", FunctionDescriptor.of(JAVA_INT, JAVA_INT)
            );
            requireStructSize(structSize, STRUCT_ITEM, ITEM_LAYOUT);
            requireStructSize(structSize, STRUCT_TREE, TREE_LAYOUT);
            requireStructSize(structSize, STRUCT_POPUP_REQUEST, POPUP_REQUEST_LAYOUT);
            requireStructSize(structSize, STRUCT_POPUP_RESULT, POPUP_RESULT_LAYOUT);
            requireStructSize(structSize, STRUCT_CAPABILITIES, CAPABILITIES_LAYOUT);
            requireStructSize(structSize, STRUCT_EVENT, EVENT_LAYOUT);
            final MethodHandle statusName = downcall(
                lookup, "kweb_menus_status_name", FunctionDescriptor.of(ADDRESS, JAVA_INT)
            );
            final MethodHandle openHandle = downcall(
                lookup, "kweb_menus_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
            );
            final String providerId = readCString((MemorySegment) downcall(
                lookup, "kweb_menus_provider_id", FunctionDescriptor.of(ADDRESS)
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
                if (status != 0) {
                    throw new IllegalStateException(
                        "The menus provider could not open: "
                            + readCString((MemorySegment) statusName.invokeExact(status)) + "."
                    );
                }
            }
            return new MenuFfm(
                downcall(lookup, "kweb_menus_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                downcall(lookup, "kweb_menus_set_application_menu", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                downcall(lookup, "kweb_menus_set_window_menu",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, STRING_LAYOUT, JAVA_LONG, ADDRESS)),
                downcall(lookup, "kweb_menus_declare_page_menu",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, STRING_LAYOUT, ADDRESS)),
                downcall(lookup, "kweb_menus_clear_page_menu",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, STRING_LAYOUT, STRING_LAYOUT)),
                downcall(lookup, "kweb_menus_show_popup",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                downcall(lookup, "kweb_menus_poll_event", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                downcall(lookup, "kweb_menus_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                statusName,
                providerId
            );
        } catch (Throwable error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("KWebMenus native ABI could not be bound.", error);
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
            if (status != 0) throw nativeFailure("capabilities", status);
            return new Capabilities(
                result.get(JAVA_INT, CAPABILITIES_LAYOUT.byteOffset(groupElement("flags"))) & 0xffffffffL,
                result.get(JAVA_LONG, CAPABILITIES_LAYOUT.byteOffset(groupElement("native_roles")))
            );
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The menus capabilities call failed.", error);
        }
    }

    public void setApplicationMenu(FlatTree tree) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment nativeTree = tree == null ? MemorySegment.NULL : writeTree(arena, tree);
            final int status = (int) setApplicationMenu.invokeExact(1L, nativeTree);
            if (status != 0) throw nativeFailure("set-application-menu", status);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The application menu call failed.", error);
        }
    }

    public void setWindowMenu(String windowId, long nativeWindow, FlatTree tree) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment window = writeBareString(arena, windowId);
            final MemorySegment nativeTree = tree == null ? MemorySegment.NULL : writeTree(arena, tree);
            final int status = (int) setWindowMenu.invokeExact(1L, window, nativeWindow, nativeTree);
            if (status != 0) throw nativeFailure("set-window-menu", status);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The window menu call failed.", error);
        }
    }

    public void declarePageMenu(String pageToken, FlatTree tree) {
        requireOpen();
        Objects.requireNonNull(tree, "tree");
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment token = writeBareString(arena, pageToken);
            final MemorySegment nativeTree = writeTree(arena, tree);
            final int status = (int) declarePageMenu.invokeExact(1L, token, nativeTree);
            if (status != 0) throw nativeFailure("declare-page-menu", status);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The page menu call failed.", error);
        }
    }

    public void clearPageMenu(String pageToken, String menuId) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment token = writeBareString(arena, pageToken);
            final MemorySegment menu = writeBareString(arena, menuId);
            final int status = (int) clearPageMenu.invokeExact(1L, token, menu);
            if (status != 0) throw nativeFailure("clear-page-menu", status);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The page menu clear call failed.", error);
        }
    }

    public PopupResult showPopup(
        int ownerKind,
        String ownerId,
        String menuId,
        int source,
        int screenX,
        int screenY,
        long nativeWindow
    ) {
        requireOpen();
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment request = arena.allocate(POPUP_REQUEST_LAYOUT);
            request.set(JAVA_INT, POPUP_REQUEST_LAYOUT.byteOffset(groupElement("struct_size")),
                (int) POPUP_REQUEST_LAYOUT.byteSize());
            request.set(JAVA_INT, POPUP_REQUEST_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
            writeString(arena, request, POPUP_REQUEST_LAYOUT, "menu_id", menuId);
            request.set(JAVA_INT, POPUP_REQUEST_LAYOUT.byteOffset(groupElement("owner_kind")), ownerKind);
            request.set(JAVA_INT, POPUP_REQUEST_LAYOUT.byteOffset(groupElement("source")), source);
            writeString(arena, request, POPUP_REQUEST_LAYOUT, "owner_id", ownerId == null ? "" : ownerId);
            request.set(JAVA_INT, POPUP_REQUEST_LAYOUT.byteOffset(groupElement("screen_x")), screenX);
            request.set(JAVA_INT, POPUP_REQUEST_LAYOUT.byteOffset(groupElement("screen_y")), screenY);
            request.set(JAVA_LONG, POPUP_REQUEST_LAYOUT.byteOffset(groupElement("native_window")), nativeWindow);
            final MemorySegment result = arena.allocate(POPUP_RESULT_LAYOUT);
            result.set(JAVA_INT, POPUP_RESULT_LAYOUT.byteOffset(groupElement("struct_size")),
                (int) POPUP_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, POPUP_RESULT_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
            final int status = (int) showPopup.invokeExact(1L, request, result);
            if (status != 0) throw nativeFailure("show-popup", status);
            return new PopupResult(
                result.get(JAVA_INT, POPUP_RESULT_LAYOUT.byteOffset(groupElement("outcome"))),
                result.get(JAVA_INT, POPUP_RESULT_LAYOUT.byteOffset(groupElement("dismiss_reason"))),
                result.get(JAVA_LONG, POPUP_RESULT_LAYOUT.byteOffset(groupElement("tree_version"))),
                readFixedCString(result, POPUP_RESULT_LAYOUT.byteOffset(groupElement("popup_id")), MAX_ID + 1),
                readFixedCString(result, POPUP_RESULT_LAYOUT.byteOffset(groupElement("command_id")), MAX_ID + 1),
                readFixedCString(result, POPUP_RESULT_LAYOUT.byteOffset(groupElement("code")), 96)
            );
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The menus popup call failed.", error);
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
            if (status != 0) throw nativeFailure("poll-event", status);
            return new Event(
                event.get(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("kind"))),
                event.get(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("dismiss_reason"))),
                event.get(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("owner_kind"))),
                event.get(JAVA_INT, EVENT_LAYOUT.byteOffset(groupElement("source"))),
                event.get(JAVA_LONG, EVENT_LAYOUT.byteOffset(groupElement("sequence"))),
                event.get(JAVA_LONG, EVENT_LAYOUT.byteOffset(groupElement("tree_version"))),
                readFixedCString(event, EVENT_LAYOUT.byteOffset(groupElement("owner_id")), MAX_ID + 1),
                readFixedCString(event, EVENT_LAYOUT.byteOffset(groupElement("popup_id")), MAX_ID + 1),
                readFixedCString(event, EVENT_LAYOUT.byteOffset(groupElement("command_id")), MAX_ID + 1),
                readFixedCString(event, EVENT_LAYOUT.byteOffset(groupElement("code")), 96)
            );
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The menus event poll failed.", error);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            closeHandle.invokeExact(1L);
        } catch (Throwable ignored) {
            // The provider keeps one process-wide handle; a second close is a
            // typed OWNER_CLOSED status rather than a second native call.
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private static MemorySegment writeTree(Arena arena, FlatTree tree) {
        final MemorySegment items = arena.allocate(ITEM_LAYOUT, tree.items().size());
        for (int index = 0; index < tree.items().size(); index += 1) {
            final FlatItem item = tree.items().get(index);
            final MemorySegment target = items.asSlice(index * ITEM_LAYOUT.byteSize(), ITEM_LAYOUT.byteSize());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("struct_size")), (int) ITEM_LAYOUT.byteSize());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("kind")), item.kind());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("flags")), item.flags());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("role")), item.role());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("mnemonic")), item.mnemonic());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("accelerator_modifiers")),
                item.acceleratorModifiers());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("accelerator_key")), item.acceleratorKey());
            writeString(arena, target, ITEM_LAYOUT, "id", item.id());
            writeString(arena, target, ITEM_LAYOUT, "label", item.label());
            writeString(arena, target, ITEM_LAYOUT, "icon_id", item.iconId());
            writeString(arena, target, ITEM_LAYOUT, "icon_sha256", item.iconSha256());
            final byte[] pixels = item.iconPixels();
            if (pixels != null && pixels.length > 0) {
                final MemorySegment segment = arena.allocate(pixels.length);
                MemorySegment.copy(pixels, 0, segment, JAVA_BYTE, 0, pixels.length);
                target.set(ADDRESS, ITEM_LAYOUT.byteOffset(groupElement("icon_pixels"), groupElement("data")), segment);
                target.set(JAVA_LONG, ITEM_LAYOUT.byteOffset(groupElement("icon_pixels"), groupElement("size")),
                    pixels.length);
            }
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("icon_width")), item.iconWidth());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("icon_height")), item.iconHeight());
            target.set(JAVA_INT, ITEM_LAYOUT.byteOffset(groupElement("submenu_count")), item.childCount());
        }
        final MemorySegment treeSegment = arena.allocate(TREE_LAYOUT);
        treeSegment.set(JAVA_INT, TREE_LAYOUT.byteOffset(groupElement("struct_size")), (int) TREE_LAYOUT.byteSize());
        treeSegment.set(JAVA_INT, TREE_LAYOUT.byteOffset(groupElement("abi_version")), ABI_VERSION);
        treeSegment.set(JAVA_LONG, TREE_LAYOUT.byteOffset(groupElement("version")), tree.version());
        treeSegment.set(JAVA_INT, TREE_LAYOUT.byteOffset(groupElement("item_count")), tree.items().size());
        writeString(arena, treeSegment, TREE_LAYOUT, "menu_id", tree.menuId());
        treeSegment.set(ADDRESS, TREE_LAYOUT.byteOffset(groupElement("items")), items);
        return treeSegment;
    }

    private static void writeString(
        Arena arena,
        MemorySegment target,
        GroupLayout layout,
        String field,
        String value
    ) {
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
                "The menus provider struct " + which + " is " + actual + " bytes, expected " + layout.byteSize() + "."
            );
        }
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return lookup.find(name)
            .map(symbol -> java.lang.foreign.Linker.nativeLinker().downcallHandle(symbol, descriptor))
            .orElseThrow(() -> new IllegalStateException("The menus provider does not export " + name + "."));
    }

    private static String readCString(MemorySegment address) {
        if (address == null || address.equals(MemorySegment.NULL)) return "";
        return address.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** One typed native failure; Kotlin maps the status to a published code. */
    public static final class MenuNativeException extends RuntimeException {
        public final int status;

        MenuNativeException(String operation, int status, String statusName) {
            super("The menus " + operation + " call failed with " + statusName + " (" + status + ").");
            this.status = status;
        }
    }

    private MenuNativeException nativeFailure(String operation, int status) {
        return new MenuNativeException(operation, status, statusName(status));
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("The menus provider handle is closed.");
    }

}
