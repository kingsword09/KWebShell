package io.github.kingsword09.kwebshell.service.securestorage.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The JDK 25 FFM binding over the versioned secure-storage C ABI. Layouts stay
 * internal: Kotlin owns identifier and scope policy, and never sees a
 * MemorySegment. A value buffer is zeroed through the ABI before it is released.
 */
public final class SecureStorageFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_BACKEND_UNAVAILABLE = 3;
    public static final int STATUS_SERVICE_UNAVAILABLE = 4;
    public static final int STATUS_ACCESSIBILITY_UNSUPPORTED = 5;
    public static final int STATUS_USER_PRESENCE_UNSUPPORTED = 6;
    public static final int STATUS_USER_PRESENCE_DENIED = 7;
    public static final int STATUS_LOCKED = 8;
    public static final int STATUS_CANCELLED = 9;
    public static final int STATUS_SECRET_NOT_FOUND = 10;
    public static final int STATUS_CORRUPTED = 11;
    public static final int STATUS_ROTATION_UNSUPPORTED = 12;
    public static final int STATUS_NATIVE_FAILED = 13;
    public static final int STATUS_OWNER_CLOSED = 14;
    public static final int STATUS_ALREADY_OPEN = 15;
    public static final int STATUS_BUFFER_TOO_SMALL = 16;

    public static final int SCOPE_APPLICATION = 1;
    public static final int SCOPE_PROFILE = 2;

    public static final int ACCESS_WHEN_UNLOCKED = 1;
    public static final int ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY = 2;
    public static final int ACCESS_AFTER_FIRST_UNLOCK = 3;
    public static final int ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY = 4;

    public static final int BACKEND_MACOS_KEYCHAIN = 1;
    public static final int BACKEND_WINDOWS_CREDENTIAL_MANAGER = 2;
    public static final int BACKEND_LINUX_SECRET_SERVICE = 3;

    public static final int CAP_ACCESSIBILITY_POLICY = 1;
    public static final int CAP_USER_PRESENCE = 1 << 1;
    public static final int CAP_ROTATION = 1 << 2;
    public static final int CAP_PROFILE_SCOPE = 1 << 3;

    public static final int ROTATION_CURRENT = 1;
    public static final int ROTATION_STALE_ENVELOPE = 2;
    public static final int ROTATION_STALE_POLICY = 3;

    private static final int MAX_ID = 129;
    private static final int MAX_OWNER = 129;
    private static final int MAX_PROVIDER = 64;
    private static final int MAX_VALUE = 4096;
    private static final int MAX_METADATA = 32;

    private static final GroupLayout STRING_LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("data"),
        JAVA_LONG.withName("size")
    );
    private static final GroupLayout BYTES_LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("data"),
        JAVA_LONG.withName("size")
    );
    private static final GroupLayout CONFIGURATION_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("application_id"),
        STRING_LAYOUT.withName("package_identity"),
        JAVA_INT.withName("reserved"),
        paddingLayout(4)
    );
    private static final GroupLayout CAPABILITIES_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(MAX_PROVIDER, JAVA_BYTE).withName("provider_id"),
        JAVA_INT.withName("backend"),
        JAVA_INT.withName("capability_bits"),
        JAVA_INT.withName("accessibility_bits"),
        JAVA_INT.withName("max_value_bytes"),
        JAVA_INT.withName("secret_count")
    );
    private static final GroupLayout REFERENCE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("secret_id"),
        STRING_LAYOUT.withName("owner_id"),
        JAVA_INT.withName("scope"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout PUT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        REFERENCE_LAYOUT.withName("reference"),
        BYTES_LAYOUT.withName("value"),
        JAVA_INT.withName("accessibility"),
        JAVA_INT.withName("require_user_presence")
    );
    private static final GroupLayout METADATA_LAYOUT = MemoryLayout.structLayout(
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("secret_id"),
        paddingLayout(3),
        JAVA_INT.withName("scope"),
        JAVA_INT.withName("backend"),
        JAVA_INT.withName("accessibility"),
        JAVA_INT.withName("envelope_version"),
        JAVA_INT.withName("generation"),
        JAVA_INT.withName("size_bytes"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout VALUE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("size"),
        JAVA_INT.withName("capacity"),
        ADDRESS.withName("data")
    );
    private static final GroupLayout PRESENCE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("present"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout ROTATION_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("status"),
        JAVA_INT.withName("generation")
    );
    private static final GroupLayout LIST_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("count"),
        JAVA_INT.withName("capacity"),
        ADDRESS.withName("entries")
    );

    private static final ConcurrentHashMap<Path, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    public record Capabilities(
        String providerId,
        int backend,
        int capabilityBits,
        int accessibilityBits,
        int maxValueBytes,
        int secretCount
    ) {
        public Capabilities {
            Objects.requireNonNull(providerId, "providerId");
        }
    }

    public record FlatMetadata(
        String secretId,
        int scope,
        int backend,
        int accessibility,
        int envelopeVersion,
        int generation,
        int sizeBytes
    ) {
        public FlatMetadata {
            Objects.requireNonNull(secretId, "secretId");
        }
    }

    public record Rotation(int status, int generation) {}

    /** One typed native failure; Kotlin maps the status to a published code. */
    public static final class SecureStorageNativeException extends RuntimeException {
        public final int status;

        SecureStorageNativeException(String operation, int status, String statusName) {
            super("The secure-storage " + operation + " call failed with " + statusName + " (" + status + ").");
            this.status = status;
        }
    }

    private final Arena arena;
    private final MemorySegment handle;
    private final MemorySegment metadataBuffer;
    private final MemorySegment valueBuffer;
    private final MethodHandle capabilities;
    private final MethodHandle put;
    private final MethodHandle get;
    private final MethodHandle release;
    private final MethodHandle delete;
    private final MethodHandle exists;
    private final MethodHandle rotate;
    private final MethodHandle rotationStatus;
    private final MethodHandle list;
    private final MethodHandle closeCall;
    private boolean closed;

    private SecureStorageFfm(
        Arena arena,
        MemorySegment handle,
        MemorySegment metadataBuffer,
        MemorySegment valueBuffer,
        MethodHandle capabilities,
        MethodHandle put,
        MethodHandle get,
        MethodHandle release,
        MethodHandle delete,
        MethodHandle exists,
        MethodHandle rotate,
        MethodHandle rotationStatus,
        MethodHandle list,
        MethodHandle closeCall
    ) {
        this.arena = arena;
        this.handle = handle;
        this.metadataBuffer = metadataBuffer;
        this.valueBuffer = valueBuffer;
        this.capabilities = capabilities;
        this.put = put;
        this.get = get;
        this.release = release;
        this.delete = delete;
        this.exists = exists;
        this.rotate = rotate;
        this.rotationStatus = rotationStatus;
        this.list = list;
        this.closeCall = closeCall;
    }

    public static SecureStorageFfm open(final Path library, final String applicationId, final String packageIdentity) {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(packageIdentity, "packageIdentity");
        verifyLayouts();
        try {
            final SymbolLookup lookup = library(library);
            final Arena arena = Arena.ofShared();
            final MemorySegment configuration = arena.allocate(CONFIGURATION_LAYOUT);
            writeString(configuration, 8L, applicationId.getBytes(StandardCharsets.UTF_8), arena);
            writeString(configuration, 24L, packageIdentity.getBytes(StandardCharsets.UTF_8), arena);
            configuration.set(JAVA_INT, 0L, (int) CONFIGURATION_LAYOUT.byteSize());
            configuration.set(JAVA_INT, 4L, ABI_VERSION);
            configuration.set(JAVA_INT, 40L, 0);
            final MemorySegment handle = arena.allocate(JAVA_LONG);
            final MethodHandle open = method(lookup, "kweb_secure_storage_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            final int status = (int) open.invokeExact(configuration, handle);
            if (status != STATUS_OK) {
                throw new SecureStorageNativeException("open", status, statusName(status));
            }
            return new SecureStorageFfm(
                arena,
                handle,
                arena.allocate(METADATA_LAYOUT, MAX_METADATA),
                arena.allocate(MAX_VALUE),
                method(lookup, "kweb_secure_storage_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_secure_storage_put", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_secure_storage_get", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_secure_storage_release", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_secure_storage_delete", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_secure_storage_exists", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_secure_storage_rotate", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_secure_storage_rotation_status", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_secure_storage_list", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_secure_storage_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException("open", -1, error.getClass().getSimpleName());
        }
    }

    public Capabilities capabilities() {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(CAPABILITIES_LAYOUT);
            result.set(JAVA_INT, 0L, (int) CAPABILITIES_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) capabilities.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status != STATUS_OK) {
                throw new SecureStorageNativeException("capabilities", status, statusName(status));
            }
            return new Capabilities(
                result.asSlice(8L, MAX_PROVIDER).getString(0L),
                result.get(JAVA_INT, 8L + MAX_PROVIDER),
                result.get(JAVA_INT, 12L + MAX_PROVIDER),
                result.get(JAVA_INT, 16L + MAX_PROVIDER),
                result.get(JAVA_INT, 20L + MAX_PROVIDER),
                result.get(JAVA_INT, 24L + MAX_PROVIDER)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException("capabilities", -1, error.getClass().getSimpleName());
        }
    }

    public FlatMetadata put(
        final String secretId,
        final int scope,
        final String ownerId,
        final byte[] value,
        final int accessibility,
        final boolean requireUserPresence
    ) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment request = arena.allocate(PUT_LAYOUT);
            writeReference(request.asSlice(8L, REFERENCE_LAYOUT.byteSize()), secretId, ownerId, scope, arena);
            writeBytes(request, 56L, value, arena);
            request.set(JAVA_INT, 0L, (int) PUT_LAYOUT.byteSize());
            request.set(JAVA_INT, 4L, ABI_VERSION);
            request.set(JAVA_INT, 72L, accessibility);
            request.set(JAVA_INT, 76L, requireUserPresence ? 1 : 0);
            final MemorySegment result = arena.allocate(METADATA_LAYOUT);
            final int status = (int) put.invokeExact(handle.get(JAVA_LONG, 0L), request, result);
            if (status != STATUS_OK) {
                throw new SecureStorageNativeException("put", status, statusName(status));
            }
            return metadataOf(result);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException("put", -1, error.getClass().getSimpleName());
        }
    }

    /** Reads one secret, returning the bytes and the record's metadata. */
    public ReadResult get(final String secretId, final int scope, final String ownerId) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment reference = arena.allocate(REFERENCE_LAYOUT);
            writeReference(reference, secretId, ownerId, scope, arena);
            final MemorySegment result = arena.allocate(VALUE_LAYOUT);
            result.set(JAVA_INT, 0L, (int) VALUE_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            result.set(JAVA_INT, 12L, MAX_VALUE);
            result.set(ADDRESS, 16L, valueBuffer);
            final int status = (int) get.invokeExact(handle.get(JAVA_LONG, 0L), reference, result);
            if (status != STATUS_OK) {
                throw new SecureStorageNativeException("get", status, statusName(status));
            }
            final byte[] bytes = valueBuffer.asSlice(0L, result.get(JAVA_INT, 8L)).toArray(JAVA_BYTE);
            // The ABI zeroes the staging buffer before returning it.
            final int releaseStatus = (int) release.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (releaseStatus != STATUS_OK) {
                throw new SecureStorageNativeException("release", releaseStatus, statusName(releaseStatus));
            }
            return new ReadResult(bytes);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException("get", -1, error.getClass().getSimpleName());
        }
    }

    public boolean delete(final String secretId, final int scope, final String ownerId) {
        return presenceCall(delete, "delete", secretId, scope, ownerId);
    }

    public boolean exists(final String secretId, final int scope, final String ownerId) {
        return presenceCall(exists, "exists", secretId, scope, ownerId);
    }

    public FlatMetadata rotate(final String secretId, final int scope, final String ownerId) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment reference = arena.allocate(REFERENCE_LAYOUT);
            writeReference(reference, secretId, ownerId, scope, arena);
            final MemorySegment result = arena.allocate(METADATA_LAYOUT);
            final int status = (int) rotate.invokeExact(handle.get(JAVA_LONG, 0L), reference, result);
            if (status != STATUS_OK) {
                throw new SecureStorageNativeException("rotate", status, statusName(status));
            }
            return metadataOf(result);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException("rotate", -1, error.getClass().getSimpleName());
        }
    }

    public Rotation rotationStatus(final String secretId, final int scope, final String ownerId) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment reference = arena.allocate(REFERENCE_LAYOUT);
            writeReference(reference, secretId, ownerId, scope, arena);
            final MemorySegment result = arena.allocate(ROTATION_LAYOUT);
            result.set(JAVA_INT, 0L, (int) ROTATION_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) rotationStatus.invokeExact(handle.get(JAVA_LONG, 0L), reference, result);
            if (status != STATUS_OK) {
                throw new SecureStorageNativeException("rotation-status", status, statusName(status));
            }
            return new Rotation(result.get(JAVA_INT, 8L), result.get(JAVA_INT, 12L));
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException("rotation-status", -1, error.getClass().getSimpleName());
        }
    }

    public List<FlatMetadata> list() {
        ensureOpen();
        try {
            final MemorySegment result = arena.allocate(LIST_LAYOUT);
            result.set(JAVA_INT, 0L, (int) LIST_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            result.set(JAVA_INT, 12L, MAX_METADATA);
            result.set(ADDRESS, 16L, metadataBuffer);
            final int status = (int) list.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status != STATUS_OK) {
                throw new SecureStorageNativeException("list", status, statusName(status));
            }
            final int count = result.get(JAVA_INT, 8L);
            final List<FlatMetadata> values = new ArrayList<>(count);
            for (int index = 0; index < count; index += 1) {
                values.add(metadataOf(metadataBuffer.asSlice(METADATA_LAYOUT.byteSize() * index, METADATA_LAYOUT.byteSize())));
            }
            return List.copyOf(values);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException("list", -1, error.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            final int status = (int) closeCall.invokeExact(handle.get(JAVA_LONG, 0L));
            if (status != STATUS_OK && status != STATUS_OWNER_CLOSED) {
                throw new SecureStorageNativeException("close", status, statusName(status));
            }
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException("close", -1, error.getClass().getSimpleName());
        } finally {
            valueBuffer.asByteBuffer().put(new byte[MAX_VALUE]);
            arena.close();
        }
    }

    /** The bytes one read returned. The buffer that held them is already zeroed. */
    public record ReadResult(byte[] bytes) {}

    private boolean presenceCall(
        final MethodHandle handleMethod,
        final String operation,
        final String secretId,
        final int scope,
        final String ownerId
    ) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment reference = arena.allocate(REFERENCE_LAYOUT);
            writeReference(reference, secretId, ownerId, scope, arena);
            final MemorySegment result = arena.allocate(PRESENCE_LAYOUT);
            result.set(JAVA_INT, 0L, (int) PRESENCE_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) handleMethod.invokeExact(handle.get(JAVA_LONG, 0L), reference, result);
            if (status != STATUS_OK) {
                throw new SecureStorageNativeException(operation, status, statusName(status));
            }
            return result.get(JAVA_INT, 8L) != 0;
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new SecureStorageNativeException(operation, -1, error.getClass().getSimpleName());
        }
    }

    private static FlatMetadata metadataOf(final MemorySegment record) {
        return new FlatMetadata(
            record.asSlice(0L, MAX_ID).getString(0L),
            record.get(JAVA_INT, 132L),
            record.get(JAVA_INT, 136L),
            record.get(JAVA_INT, 140L),
            record.get(JAVA_INT, 144L),
            record.get(JAVA_INT, 148L),
            record.get(JAVA_INT, 152L)
        );
    }

    private static void writeReference(
        final MemorySegment target,
        final String secretId,
        final String ownerId,
        final int scope,
        final Arena arena
    ) {
        writeString(target, 8L, secretId.getBytes(StandardCharsets.UTF_8), arena);
        writeString(target, 24L, ownerId.getBytes(StandardCharsets.UTF_8), arena);
        target.set(JAVA_INT, 0L, (int) REFERENCE_LAYOUT.byteSize());
        target.set(JAVA_INT, 4L, ABI_VERSION);
        target.set(JAVA_INT, 40L, scope);
        target.set(JAVA_INT, 44L, 0);
    }

    private static void writeBytes(final MemorySegment target, final long offset, final byte[] value, final Arena arena) {
        final MemorySegment buffer = arena.allocate(Math.max(value.length, 1));
        buffer.asByteBuffer().put(value);
        target.set(ADDRESS, offset, buffer);
        target.set(JAVA_LONG, offset + 8, value.length);
    }

    private static void writeString(final MemorySegment target, final long offset, final byte[] value, final Arena arena) {
        final MemorySegment buffer = arena.allocate(Math.max(value.length, 1));
        buffer.asByteBuffer().put(value);
        target.set(ADDRESS, offset, buffer);
        target.set(JAVA_LONG, offset + 8, value.length);
    }

    private static MethodHandle method(final SymbolLookup lookup, final String name, final FunctionDescriptor descriptor) {
        return lookup.find(name)
            .map(symbol -> java.lang.foreign.Linker.nativeLinker().downcallHandle(symbol, descriptor))
            .orElseThrow(() -> new SecureStorageNativeException(name, -1, "missing-symbol"));
    }

    private static SymbolLookup library(final Path library) {
        Objects.requireNonNull(library, "library");
        return LIBRARIES.computeIfAbsent(library.toAbsolutePath().normalize(), path -> {
            try {
                return SymbolLookup.libraryLookup(path, Arena.global());
            } catch (final IllegalArgumentException error) {
                throw new SecureStorageNativeException("library", -1, "unloadable");
            }
        });
    }

    private void ensureOpen() {
        if (closed) {
            throw new SecureStorageNativeException("call", STATUS_OWNER_CLOSED, statusName(STATUS_OWNER_CLOSED));
        }
    }

    /** Fails fast when a layout disagrees with the C ABI this binding was built for. */
    private static void verifyLayouts() {
        checkLayout("configuration", CONFIGURATION_LAYOUT, 48L);
        checkLayout("capabilities", CAPABILITIES_LAYOUT, 92L);
        checkLayout("reference", REFERENCE_LAYOUT, 48L);
        checkLayout("put", PUT_LAYOUT, 80L);
        checkLayout("metadata", METADATA_LAYOUT, 160L);
        checkLayout("value", VALUE_LAYOUT, 24L);
        checkLayout("presence", PRESENCE_LAYOUT, 16L);
        checkLayout("rotation", ROTATION_LAYOUT, 16L);
        checkLayout("list", LIST_LAYOUT, 24L);
    }

    private static void checkLayout(final String name, final MemoryLayout layout, final long expected) {
        if (layout.byteSize() != expected) {
            throw new IllegalStateException(
                "The secure-storage " + name + " layout is " + layout.byteSize() + " bytes, expected " + expected + "."
            );
        }
    }

    public static String statusName(final int status) {
        return switch (status) {
            case STATUS_OK -> "ok";
            case STATUS_INVALID_ARGUMENT -> "invalid-argument";
            case STATUS_ABI_MISMATCH -> "abi-mismatch";
            case STATUS_BACKEND_UNAVAILABLE -> "backend-unavailable";
            case STATUS_SERVICE_UNAVAILABLE -> "service-unavailable";
            case STATUS_ACCESSIBILITY_UNSUPPORTED -> "accessibility-unsupported";
            case STATUS_USER_PRESENCE_UNSUPPORTED -> "user-presence-unsupported";
            case STATUS_USER_PRESENCE_DENIED -> "user-presence-denied";
            case STATUS_LOCKED -> "locked";
            case STATUS_CANCELLED -> "cancelled";
            case STATUS_SECRET_NOT_FOUND -> "secret-not-found";
            case STATUS_CORRUPTED -> "corrupted";
            case STATUS_ROTATION_UNSUPPORTED -> "rotation-unsupported";
            case STATUS_NATIVE_FAILED -> "native-failed";
            case STATUS_OWNER_CLOSED -> "owner-closed";
            case STATUS_ALREADY_OPEN -> "already-open";
            case STATUS_BUFFER_TOO_SMALL -> "buffer-too-small";
            default -> "unknown";
        };
    }
}
