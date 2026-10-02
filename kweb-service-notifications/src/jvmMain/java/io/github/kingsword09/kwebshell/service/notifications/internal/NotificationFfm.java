package io.github.kingsword09.kwebshell.service.notifications.internal;

import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationCloseReason;
import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationPermissionStatus;
import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationAction;
import io.github.kingsword09.kwebshell.service.notifications.NativeNotificationCapabilities;
import io.github.kingsword09.kwebshell.service.notifications.NativeNotificationEvent;
import io.github.kingsword09.kwebshell.service.notifications.NativeNotificationEventKind;
import io.github.kingsword09.kwebshell.service.notifications.NativeNotificationPermission;
import io.github.kingsword09.kwebshell.service.notifications.NativeNotificationRequest;
import io.github.kingsword09.kwebshell.service.notifications.NotificationNativeExecutor;
import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationActionKind;
import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationIcon;
import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationTimeout;
import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationUrgency;

import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

public final class NotificationFfm implements NotificationNativeExecutor {
    public static final int ABI_VERSION = 1;
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_NATIVE_UNAVAILABLE = 3;
    public static final int STATUS_PERMISSION_DENIED = 4;
    public static final int STATUS_PERMISSION_UNDETERMINED = 5;
    public static final int STATUS_ACTIONS_UNSUPPORTED = 6;
    public static final int STATUS_REPLY_UNSUPPORTED = 7;
    public static final int STATUS_TIMEOUT_UNSUPPORTED = 8;
    public static final int STATUS_NOT_FOUND = 9;
    public static final int STATUS_NATIVE_FAILED = 10;
    public static final int STATUS_NO_EVENT = 11;

    public static final int PERMISSION_NOT_DETERMINED = 1;
    public static final int PERMISSION_GRANTED = 2;
    public static final int PERMISSION_DENIED = 3;
    public static final int PERMISSION_NOT_APPLICABLE = 4;
    public static final int PERMISSION_UNAVAILABLE = 5;

    public static final int CAP_ACTIONS = 1;
    public static final int CAP_REPLIES = 2;
    public static final int CAP_REPLACEMENT = 4;
    public static final int CAP_TIMEOUT = 8;
    public static final int CAP_ACTIVATION = 16;

    public static final int ACTION_BUTTON = 1;
    public static final int ACTION_REPLY = 2;
    public static final int ICON_APPLICATION = 1;
    public static final int URGENCY_LOW = 1;
    public static final int URGENCY_NORMAL = 2;
    public static final int URGENCY_HIGH = 3;
    public static final int TIMEOUT_SYSTEM = 1;
    public static final int TIMEOUT_SHORT = 2;
    public static final int TIMEOUT_LONG = 3;
    public static final int TIMEOUT_PERSISTENT = 4;
    public static final int EVENT_ACTION = 1;
    public static final int EVENT_CLOSED = 2;
    public static final int EVENT_FAILED = 3;
    public static final int CLOSE_PROGRAMMATIC = 1;
    public static final int CLOSE_REPLACED = 2;
    public static final int CLOSE_EXPIRED = 3;
    public static final int CLOSE_USER_DISMISSED = 4;
    public static final int CLOSE_OWNER_CLOSED = 5;
    public static final int CLOSE_NATIVE = 6;

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfLong SIZE_T = ValueLayout.JAVA_LONG;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final GroupLayout STRING = MemoryLayout.structLayout(
        PTR.withName("data"), SIZE_T.withName("size")
    );
    private static final GroupLayout CONFIG = MemoryLayout.structLayout(
        I32.withName("struct_size"), I32.withName("abi_version"), I32.withName("reserved"),
        MemoryLayout.paddingLayout(4), STRING.withName("application_id"), STRING.withName("package_identity")
    );
    private static final GroupLayout ACTION = MemoryLayout.structLayout(
        I32.withName("struct_size"), I32.withName("abi_version"), I32.withName("kind"), I32.withName("reserved"),
        STRING.withName("id"), STRING.withName("title"), STRING.withName("reply_placeholder")
    );
    private static final GroupLayout REQUEST = MemoryLayout.structLayout(
        I32.withName("struct_size"), I32.withName("abi_version"), I32.withName("icon"),
        I32.withName("urgency"), I32.withName("timeout"), I32.withName("action_count"), I32.withName("reserved"),
        MemoryLayout.paddingLayout(4), STRING.withName("id"), STRING.withName("tag"), STRING.withName("title"),
        STRING.withName("body"), MemoryLayout.sequenceLayout(3, ACTION).withName("actions")
    );
    private static final GroupLayout PERMISSION = MemoryLayout.structLayout(
        I32.withName("struct_size"), I32.withName("abi_version"), I32.withName("status"), I32.withName("reserved"),
        MemoryLayout.sequenceLayout(128, ValueLayout.JAVA_BYTE).withName("provider")
    );
    private static final GroupLayout CAPABILITIES = MemoryLayout.structLayout(
        I32.withName("struct_size"), I32.withName("abi_version"), I32.withName("flags"), I32.withName("reserved")
    );
    private static final GroupLayout EVENT = MemoryLayout.structLayout(
        I32.withName("struct_size"), I32.withName("abi_version"), I32.withName("kind"), I32.withName("close_reason"),
        I64.withName("sequence"),
        MemoryLayout.sequenceLayout(129, ValueLayout.JAVA_BYTE).withName("id"),
        MemoryLayout.sequenceLayout(65, ValueLayout.JAVA_BYTE).withName("action_id"),
        MemoryLayout.sequenceLayout(1025, ValueLayout.JAVA_BYTE).withName("reply"),
        MemoryLayout.sequenceLayout(96, ValueLayout.JAVA_BYTE).withName("code"),
        MemoryLayout.paddingLayout(5)
    );

    private final Path libraryPath;
    private final Arena arena;
    private final long handle;
    private final MethodHandle permission;
    private final MethodHandle requestPermission;
    private final MethodHandle capabilities;
    private final MethodHandle show;
    private final MethodHandle closeNotification;
    private final MethodHandle pollEvent;
    private final MethodHandle close;
    private final MethodHandle providerId;

    private NotificationFfm(Path libraryPath, Arena arena, long handle, MethodHandle permission,
                            MethodHandle requestPermission, MethodHandle capabilities, MethodHandle show,
                            MethodHandle closeNotification, MethodHandle pollEvent, MethodHandle close,
                            MethodHandle providerId) {
        this.libraryPath = libraryPath;
        this.arena = arena;
        this.handle = handle;
        this.permission = permission;
        this.requestPermission = requestPermission;
        this.capabilities = capabilities;
        this.show = show;
        this.closeNotification = closeNotification;
        this.pollEvent = pollEvent;
        this.close = close;
        this.providerId = providerId;
    }

    public static NotificationFfm open(Path requestedPath, String applicationId, String packageIdentity) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        if (!requestedPath.isAbsolute() || !requestedPath.normalize().equals(requestedPath) ||
            !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException("The KWebNotifications native library must be an absolute regular file.");
        }
        final Path libraryPath;
        try {
            libraryPath = requestedPath.toRealPath();
        } catch (Exception error) {
            throw new IllegalArgumentException("The KWebNotifications native library could not be canonicalized.", error);
        }
        final Arena arena = Arena.ofShared();
        try {
            final SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            final MethodHandle abi = bind(lookup, "kweb_notifications_abi_version", FunctionDescriptor.of(I32));
            if ((int) abi.invokeExact() != ABI_VERSION) throw new IllegalStateException("KWebNotifications ABI version mismatch.");
            final MethodHandle open = bind(lookup, "kweb_notifications_open", FunctionDescriptor.of(I32, PTR, PTR));
            try (Arena call = Arena.ofConfined()) {
                final MemorySegment config = call.allocate(CONFIG);
                setI32(config, CONFIG, "struct_size", (int) CONFIG.byteSize());
                setI32(config, CONFIG, "abi_version", ABI_VERSION);
                setI32(config, CONFIG, "reserved", 0);
                setString(config, CONFIG, "application_id", call, applicationId);
                setString(config, CONFIG, "package_identity", call, packageIdentity);
                final MemorySegment handle = call.allocate(I64);
                final int status = (int) open.invokeExact(config, handle);
                if (status != STATUS_OK) throw new NativeFailure(status, statusName(status));
                return new NotificationFfm(
                    libraryPath,
                    arena,
                    handle.get(I64, 0),
                    bind(lookup, "kweb_notifications_permission", FunctionDescriptor.of(I32, I64, PTR)),
                    bind(lookup, "kweb_notifications_request_permission", FunctionDescriptor.of(I32, I64, PTR)),
                    bind(lookup, "kweb_notifications_capabilities", FunctionDescriptor.of(I32, I64, PTR)),
                    bind(lookup, "kweb_notifications_show", FunctionDescriptor.of(I32, I64, PTR)),
                    bind(lookup, "kweb_notifications_close_notification", FunctionDescriptor.of(I32, I64, STRING)),
                    bind(lookup, "kweb_notifications_poll_event", FunctionDescriptor.of(I32, I64, PTR)),
                    bind(lookup, "kweb_notifications_close", FunctionDescriptor.of(I32, I64)),
                    bind(lookup, "kweb_notifications_provider_id", FunctionDescriptor.of(PTR))
                );
            }
        } catch (Throwable error) {
            arena.close();
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("The KWebNotifications native ABI could not be opened.", error);
        }
    }

    @Override public String getProviderId() {
        try {
            return readCString((MemorySegment) providerId.invokeExact(), 128);
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "provider-id-failed", error);
        }
    }

    @Override public NativeNotificationPermission permission() { return readPermission(permission); }
    @Override public NativeNotificationPermission requestPermission() { return readPermission(requestPermission); }

    private NativeNotificationPermission readPermission(MethodHandle method) {
        try (Arena call = Arena.ofConfined()) {
            final MemorySegment result = call.allocate(PERMISSION);
            setI32(result, PERMISSION, "struct_size", (int) PERMISSION.byteSize());
            setI32(result, PERMISSION, "abi_version", ABI_VERSION);
            final int status = (int) method.invokeExact(handle, result);
            if (status != STATUS_OK) throw new NativeFailure(status, statusName(status));
            validateHeader(result, PERMISSION);
            return new NativeNotificationPermission(
                permissionStatus(result.get(I32, offset(PERMISSION, "status"))),
                readArrayString(result, PERMISSION, "provider", 128)
            );
        } catch (NativeFailure error) { throw error; }
        catch (Throwable error) { throw new NativeFailure(STATUS_NATIVE_FAILED, "permission-call-failed", error); }
    }

    @Override public NativeNotificationCapabilities capabilities() {
        try (Arena call = Arena.ofConfined()) {
            final MemorySegment result = call.allocate(CAPABILITIES);
            setI32(result, CAPABILITIES, "struct_size", (int) CAPABILITIES.byteSize());
            setI32(result, CAPABILITIES, "abi_version", ABI_VERSION);
            final int status = (int) capabilities.invokeExact(handle, result);
            if (status != STATUS_OK) throw new NativeFailure(status, statusName(status));
            validateHeader(result, CAPABILITIES);
            final int flags = result.get(I32, offset(CAPABILITIES, "flags"));
            return new NativeNotificationCapabilities(
                (flags & CAP_ACTIONS) != 0, (flags & CAP_REPLIES) != 0,
                (flags & CAP_REPLACEMENT) != 0, (flags & CAP_TIMEOUT) != 0,
                (flags & CAP_ACTIVATION) != 0
            );
        } catch (NativeFailure error) { throw error; }
        catch (Throwable error) { throw new NativeFailure(STATUS_NATIVE_FAILED, "capabilities-call-failed", error); }
    }

    @Override public void show(NativeNotificationRequest value) {
        try (Arena call = Arena.ofConfined()) {
            final MemorySegment request = call.allocate(REQUEST);
            setI32(request, REQUEST, "struct_size", (int) REQUEST.byteSize());
            setI32(request, REQUEST, "abi_version", ABI_VERSION);
            setI32(request, REQUEST, "icon", ICON_APPLICATION);
            setI32(request, REQUEST, "urgency", urgency(value.getUrgency()));
            setI32(request, REQUEST, "timeout", timeout(value.getTimeout()));
            final List<KWebNotificationAction> actions = value.getActions();
            setI32(request, REQUEST, "action_count", actions.size());
            setI32(request, REQUEST, "reserved", 0);
            setString(request, REQUEST, "id", call, value.getId());
            setString(request, REQUEST, "tag", call, value.getTag());
            setString(request, REQUEST, "title", call, value.getTitle());
            setString(request, REQUEST, "body", call, value.getBody());
            final long actionsOffset = offset(REQUEST, "actions");
            for (int index = 0; index < actions.size(); index++) {
                final MemorySegment action = request.asSlice(actionsOffset + index * ACTION.byteSize(), ACTION.byteSize());
                final KWebNotificationAction item = actions.get(index);
                setI32(action, ACTION, "struct_size", (int) ACTION.byteSize());
                setI32(action, ACTION, "abi_version", ABI_VERSION);
                setI32(action, ACTION, "kind", item.getKind() == KWebNotificationActionKind.REPLY ? ACTION_REPLY : ACTION_BUTTON);
                setI32(action, ACTION, "reserved", 0);
                setString(action, ACTION, "id", call, item.getId());
                setString(action, ACTION, "title", call, item.getTitle());
                setString(action, ACTION, "reply_placeholder", call, item.getReplyPlaceholder());
            }
            final int status = (int) show.invokeExact(handle, request);
            if (status != STATUS_OK) throw new NativeFailure(status, statusName(status));
        } catch (NativeFailure error) { throw error; }
        catch (Throwable error) { throw new NativeFailure(STATUS_NATIVE_FAILED, "show-call-failed", error); }
    }

    @Override public void close(String id) {
        try (Arena call = Arena.ofConfined()) {
            final MemorySegment string = call.allocate(STRING);
            setString(string, STRING, null, call, id);
            final int status = (int) closeNotification.invokeExact(handle, string);
            if (status != STATUS_OK) throw new NativeFailure(status, statusName(status));
        } catch (NativeFailure error) { throw error; }
        catch (Throwable error) { throw new NativeFailure(STATUS_NATIVE_FAILED, "close-notification-failed", error); }
    }

    @Override public NativeNotificationEvent pollEvent() {
        try (Arena call = Arena.ofConfined()) {
            final MemorySegment event = call.allocate(EVENT);
            setI32(event, EVENT, "struct_size", (int) EVENT.byteSize());
            setI32(event, EVENT, "abi_version", ABI_VERSION);
            final int status = (int) pollEvent.invokeExact(handle, event);
            if (status == STATUS_NO_EVENT) return null;
            if (status != STATUS_OK) throw new NativeFailure(status, statusName(status));
            validateHeader(event, EVENT);
            final int kind = event.get(I32, offset(EVENT, "kind"));
            if (kind == EVENT_ACTION) {
                return new NativeNotificationEvent(
                    readArrayString(event, EVENT, "id", 129), NativeNotificationEventKind.ACTION,
                    readArrayString(event, EVENT, "action_id", 65), readOptionalArrayString(event, EVENT, "reply", 1025), null, null
                );
            }
            if (kind == EVENT_CLOSED) {
                return new NativeNotificationEvent(
                    readArrayString(event, EVENT, "id", 129), NativeNotificationEventKind.CLOSED,
                    null, null, closeReason(event.get(I32, offset(EVENT, "close_reason"))), null
                );
            }
            return new NativeNotificationEvent(
                readArrayString(event, EVENT, "id", 129), NativeNotificationEventKind.FAILED,
                null, null, null, readArrayString(event, EVENT, "code", 96)
            );
        } catch (NativeFailure error) { throw error; }
        catch (Throwable error) { throw new NativeFailure(STATUS_NATIVE_FAILED, "poll-event-failed", error); }
    }

    @Override public void close() {
        try {
            final int status = (int) close.invokeExact(handle);
            if (status != STATUS_OK) throw new NativeFailure(status, statusName(status));
        } catch (NativeFailure error) { throw error; }
        catch (Throwable error) { throw new NativeFailure(STATUS_NATIVE_FAILED, "close-failed", error); }
        finally { arena.close(); }
    }

    private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return java.lang.foreign.Linker.nativeLinker().downcallHandle(
            lookup.find(name).orElseThrow(() -> new IllegalStateException("Missing KWebNotifications ABI symbol: " + name)),
            descriptor
        );
    }

    private static void validateHeader(MemorySegment segment, GroupLayout layout) {
        final int size = segment.get(I32, offset(layout, "struct_size"));
        final int abi = segment.get(I32, offset(layout, "abi_version"));
        if (size < layout.byteSize() || abi != ABI_VERSION) throw new NativeFailure(STATUS_NATIVE_FAILED, "invalid-result");
    }

    private static void setI32(MemorySegment segment, GroupLayout layout, String field, int value) {
        segment.set(I32, offset(layout, field), value);
    }

    private static void setString(MemorySegment segment, GroupLayout layout, String field, Arena arena, String value) {
        final MemorySegment target = field == null ? segment : segment.asSlice(offset(layout, field), STRING.byteSize());
        final byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        final MemorySegment data = bytes.length == 0 ? MemorySegment.NULL : arena.allocate(bytes.length, 1);
        if (bytes.length != 0) data.asByteBuffer().put(bytes);
        target.set(PTR, offset(STRING, "data"), data);
        target.set(SIZE_T, offset(STRING, "size"), bytes.length);
    }

    private static long offset(GroupLayout layout, String field) {
        return layout.byteOffset(groupElement(field));
    }

    private static String readArrayString(MemorySegment segment, GroupLayout layout, String field, int maximum) {
        final byte[] bytes = segment.asSlice(offset(layout, field), maximum).toArray(ValueLayout.JAVA_BYTE);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) length++;
        return decode(bytes, length);
    }

    private static String readOptionalArrayString(MemorySegment segment, GroupLayout layout, String field, int maximum) {
        final String value = readArrayString(segment, layout, field, maximum);
        return value.isEmpty() ? null : value;
    }

    private static String readCString(MemorySegment pointer, long maximum) {
        if (pointer == null || pointer.address() == 0) throw new NativeFailure(STATUS_NATIVE_FAILED, "null-provider");
        final byte[] bytes = pointer.reinterpret(maximum).toArray(ValueLayout.JAVA_BYTE);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) length++;
        if (length == bytes.length) throw new NativeFailure(STATUS_NATIVE_FAILED, "unterminated-provider");
        return decode(bytes, length);
    }

    private static String decode(byte[] bytes, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException error) { throw new NativeFailure(STATUS_NATIVE_FAILED, "invalid-utf8", error); }
    }

    private static int urgency(KWebNotificationUrgency value) { return value.ordinal() + 1; }
    private static int timeout(KWebNotificationTimeout value) { return value.ordinal() + 1; }
    private static KWebNotificationPermissionStatus permissionStatus(int status) {
        return switch (status) {
            case PERMISSION_NOT_DETERMINED -> KWebNotificationPermissionStatus.NOT_DETERMINED;
            case PERMISSION_GRANTED -> KWebNotificationPermissionStatus.GRANTED;
            case PERMISSION_DENIED -> KWebNotificationPermissionStatus.DENIED;
            case PERMISSION_NOT_APPLICABLE -> KWebNotificationPermissionStatus.NOT_APPLICABLE;
            default -> KWebNotificationPermissionStatus.UNAVAILABLE;
        };
    }
    private static KWebNotificationCloseReason closeReason(int reason) {
        return switch (reason) {
            case CLOSE_PROGRAMMATIC -> KWebNotificationCloseReason.PROGRAMMATIC;
            case CLOSE_REPLACED -> KWebNotificationCloseReason.REPLACED;
            case CLOSE_EXPIRED -> KWebNotificationCloseReason.EXPIRED;
            case CLOSE_USER_DISMISSED -> KWebNotificationCloseReason.USER_DISMISSED;
            case CLOSE_OWNER_CLOSED -> KWebNotificationCloseReason.OWNER_CLOSED;
            default -> KWebNotificationCloseReason.NATIVE;
        };
    }
    private static String statusName(int status) { return "native-status-" + status; }

    public static final class NativeFailure extends RuntimeException {
        private final int status;
        private final String statusName;
        private NativeFailure(int status, String statusName) { this(status, statusName, null); }
        private NativeFailure(int status, String statusName, Throwable cause) { super(statusName, cause); this.status = status; this.statusName = statusName; }
        public int status() { return status; }
        public String statusName() { return statusName; }
    }

    public Path libraryPath() { return libraryPath; }
}
