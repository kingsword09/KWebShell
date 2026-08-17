package io.github.kingsword09.kwebshell.interop.probe;

import java.nio.file.Path;

final class JniProbe {
    private static Path loadedLibrary;

    static synchronized void load(Path library) {
        Path exact = ExactLibrary.argument(library.toString(), "JNI interop probe library");
        if (loadedLibrary != null) {
            if (!loadedLibrary.equals(exact)) {
                throw new IllegalStateException(
                    "JNI interop probe is already bound to a different exact library: " + loadedLibrary
                );
            }
            return;
        }
        System.load(exact.toString());
        loadedLibrary = exact;
    }

    static native int abiVersion();

    static long abiVersionBatch(int operations) {
        if (operations <= 0) {
            throw new IllegalArgumentException("operations must be positive.");
        }
        long result = 0;
        for (int index = 0; index < operations; ++index) {
            result ^= abiVersion();
        }
        return result;
    }

    static native long integerCall(long handle, int width, int height);

    static native long utf8Call(String text);

    static native long fixedUpcall(FixedSink sink, int count);

    static native long utf8Upcall(Utf8Sink sink, String text, int count);

    static native long ownerCycles(FixedSink sink, int count);

    static native long liveNativeBytes();

    @FunctionalInterface
    interface FixedSink {
        long receive(long sequence);
    }

    @FunctionalInterface
    interface Utf8Sink {
        long receive(String text, long sequence);
    }

    private JniProbe() {
    }
}
