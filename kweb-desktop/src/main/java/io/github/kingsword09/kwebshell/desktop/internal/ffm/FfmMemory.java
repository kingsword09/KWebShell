package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class FfmMemory {
    static final long MAXIMUM_TEXT_SIZE = 1024L * 1024L;
    static final long MAXIMUM_PATH_SIZE = 32768L;

    private static final long STRING_DATA_OFFSET = FfmLayouts.offset(FfmLayouts.STRING_VIEW, "data");
    private static final long STRING_SIZE_OFFSET = FfmLayouts.offset(FfmLayouts.STRING_VIEW, "size");

    static EncodedUtf8 encode(String value, Arena arena, long maximumSize) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(arena, "arena");
        if (maximumSize < 0 || maximumSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The UTF-8 size limit is invalid: " + maximumSize);
        }
        long maximumEncodedSize = Math.multiplyExact((long) value.length(), 3L);
        long capacity = Math.max(1L, Math.min(maximumSize + 1L, maximumEncodedSize));
        MemorySegment segment = arena.allocate(capacity, 1);
        ByteBuffer output = segment.asByteBuffer();
        try {
            var encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            var encoding = encoder.encode(CharBuffer.wrap(value), output, true);
            if (encoding.isOverflow()) {
                throw new FfmTextException(FfmStatus.TEXT_TOO_LARGE, maximumSize);
            }
            if (encoding.isError()) {
                encoding.throwException();
            }
            var flushing = encoder.flush(output);
            if (flushing.isOverflow()) {
                throw new FfmTextException(FfmStatus.TEXT_TOO_LARGE, maximumSize);
            }
            if (flushing.isError()) {
                flushing.throwException();
            }
            if (output.position() > maximumSize) {
                throw new FfmTextException(FfmStatus.TEXT_TOO_LARGE, maximumSize);
            }
            return new EncodedUtf8(segment, output.position());
        } catch (CharacterCodingException error) {
            throw new FfmTextException(FfmStatus.INVALID_TEXT_ENCODING, maximumSize, error);
        }
    }

    @SuppressWarnings("restricted")
    static String decode(MemorySegment pointer, long size, long maximumSize) {
        if (size < 0 || size > maximumSize || size > Integer.MAX_VALUE) {
            throw new FfmTextException(FfmStatus.TEXT_TOO_LARGE, maximumSize);
        }
        if (size == 0) {
            return "";
        }
        if (pointer.address() == 0) {
            throw new FfmTextException(FfmStatus.INVALID_ARGUMENT, maximumSize);
        }
        byte[] bytes = pointer.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new FfmTextException(FfmStatus.INVALID_TEXT_ENCODING, maximumSize, error);
        }
    }

    static void writeStringView(
        MemorySegment structure,
        GroupLayout structureLayout,
        String field,
        EncodedUtf8 value
    ) {
        long fieldOffset = FfmLayouts.offset(structureLayout, field);
        MemorySegment view = structure.asSlice(fieldOffset, FfmLayouts.STRING_VIEW.byteSize());
        MemorySegment pointer = value.size() == 0 ? MemorySegment.NULL : value.segment();
        view.set(FfmLayouts.POINTER, STRING_DATA_OFFSET, pointer);
        view.set(FfmLayouts.SIZE_T, STRING_SIZE_OFFSET, value.size());
    }

    static String readStringView(
        MemorySegment structure,
        GroupLayout structureLayout,
        String field,
        long maximumSize
    ) {
        long fieldOffset = FfmLayouts.offset(structureLayout, field);
        MemorySegment view = structure.asSlice(fieldOffset, FfmLayouts.STRING_VIEW.byteSize());
        MemorySegment pointer = view.get(FfmLayouts.POINTER, STRING_DATA_OFFSET);
        long size = view.get(FfmLayouts.SIZE_T, STRING_SIZE_OFFSET);
        return decode(pointer, size, maximumSize);
    }

    record EncodedUtf8(MemorySegment segment, long size) {
    }

    private FfmMemory() {
    }
}
