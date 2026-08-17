package io.github.kingsword09.kwebshell.interop.probe;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ProbeContractMain {
    private static final int PROBE_ABI_VERSION = 1;
    private static final int PARENT_SURFACE_INVALID = 32;

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected <absolute-interop-probe-library>.");
        }
        Path library = ExactLibrary.argument(arguments[0], "interop probe library");
        expectFailure(
            () -> ExactLibrary.argument("relative-probe-library", "interop probe library"),
            IllegalArgumentException.class
        );
        Path missingLibrary = library.resolveSibling(library.getFileName() + ".missing");
        expectFailure(
            () -> ExactLibrary.argument(missingLibrary.toString(), "interop probe library"),
            IllegalArgumentException.class
        );
        FfmProbe probe = FfmProbe.open(library);
        try {
            require(probe.abiVersion() == PROBE_ABI_VERSION, "Probe ABI version mismatch.");
            require(NativeLayouts.POINTER.byteSize() == 8, "FFM pointer layout is not 64-bit.");
            require(NativeLayouts.SIZE_T.byteSize() == 8, "FFM size_t layout is not 64-bit.");
            require(NativeLayouts.ALL.size() == 8, "The frozen ABI layout inventory must contain exactly 8 entries.");
            for (NativeLayouts.LayoutSpec layout : NativeLayouts.ALL) {
                require(
                    probe.layoutSize(layout.nativeId()) == layout.layout().byteSize(),
                    layout.layout().name().orElse("layout") + " size mismatch."
                );
                require(
                    probe.layoutAlignment(layout.nativeId()) == layout.layout().byteAlignment(),
                    layout.layout().name().orElse("layout") + " alignment mismatch."
                );
                require(
                    probe.layoutFieldCount(layout.nativeId()) == layout.fields().size(),
                    layout.layout().name().orElse("layout") + " field-count mismatch."
                );
                for (int index = 0; index < layout.fields().size(); ++index) {
                    require(
                        probe.layoutFieldOffset(layout.nativeId(), index) == layout.offset(index),
                        layout.layout().name().orElse("layout") + "." + layout.fields().get(index) +
                            " offset mismatch."
                    );
                }
                require(
                    probe.layoutFieldOffset(layout.nativeId(), layout.fields().size()) == FfmProbe.INVALID_VALUE,
                    layout.layout().name().orElse("layout") + " accepted an invalid field index."
                );
            }

            long integerResult = probe.integerCall(7, 800, 600);
            require(integerResult != FfmProbe.INVALID_VALUE, "Valid integer downcall failed.");
            require(integerResult == probe.integerCall(7, 800, 600), "Integer downcall is not deterministic.");
            require(probe.integerCall(7, 0, 600) == FfmProbe.INVALID_VALUE, "Invalid dimensions were accepted.");

            String unicode = "KWebShell - \u4E2D\u6587 - \uD83D\uDE80";
            long unicodeResult = probe.utf8Call(unicode);
            require(unicodeResult != FfmProbe.INVALID_VALUE, "Valid Unicode downcall failed.");
            require(unicodeResult == probe.utf8Call(unicode), "Unicode downcall is not deterministic.");
            expectFailure(() -> probe.utf8Call("\uD800"), IllegalArgumentException.class);
            String maximumUnicode = "\uD83D\uDE80".repeat((int) (FfmProbe.MAXIMUM_UTF8_SIZE / 4));
            require(probe.utf8Call(maximumUnicode) != FfmProbe.INVALID_VALUE,
                "Maximum Unicode payload was rejected.");
            expectFailure(() -> probe.utf8Call(maximumUnicode + "K"), IllegalArgumentException.class);

            AtomicLong fixedSum = new AtomicLong();
            long fixedResult = probe.fixedUpcall(sequence -> {
                fixedSum.addAndGet(sequence);
                return sequence ^ 0x55AA55AAL;
            }, 32);
            require(fixedResult != FfmProbe.INVALID_VALUE && fixedSum.get() == 528,
                "Fixed shared-Arena upcall failed.");

            long callerThread = Thread.currentThread().threadId();
            AtomicBoolean observedNativeThread = new AtomicBoolean();
            AtomicLong threadedSum = new AtomicLong();
            long threadedResult = probe.threadedFixedUpcall(sequence -> {
                observedNativeThread.set(Thread.currentThread().threadId() != callerThread);
                threadedSum.addAndGet(sequence);
                return sequence ^ 0x55AA55AAL;
            }, 32);
            require(threadedResult != FfmProbe.INVALID_VALUE && threadedSum.get() == 528,
                "Native-thread shared-Arena upcall failed.");
            require(observedNativeThread.get(), "Native-thread upcall ran on the initiating Java thread.");
            AtomicInteger threadedFailures = new AtomicInteger();
            expectFailure(
                () -> probe.threadedFixedUpcall(sequence -> {
                    threadedFailures.incrementAndGet();
                    throw new IllegalStateException("expected native-thread callback failure");
                }, 4),
                FfmProbe.CallbackFailureException.class
            );
            require(threadedFailures.get() == 4,
                "A native-thread callback exception escaped into native code.");

            AtomicLong utf8Sum = new AtomicLong();
            long upcallResult = probe.utf8Upcall((value, sequence) -> {
                require(value.equals(unicode), "UTF-8 upcall changed its payload.");
                utf8Sum.addAndGet(sequence);
                return value.length() + sequence;
            }, unicode, 16);
            require(upcallResult != FfmProbe.INVALID_VALUE && utf8Sum.get() == 136,
                "UTF-8 shared-Arena upcall failed.");

            AtomicInteger malformedCallbacks = new AtomicInteger();
            expectFailure(
                () -> probe.malformedUtf8Upcall((value, sequence) -> {
                    malformedCallbacks.incrementAndGet();
                    return sequence;
                }),
                FfmProbe.CallbackFailureException.class
            );
            require(malformedCallbacks.get() == 0,
                "Malformed native UTF-8 reached the Java callback.");

            AtomicInteger containedCallbacks = new AtomicInteger();
            expectFailure(
                () -> probe.fixedUpcall(sequence -> {
                    containedCallbacks.incrementAndGet();
                    throw new IllegalStateException("expected callback failure");
                }, 4),
                FfmProbe.CallbackFailureException.class
            );
            require(containedCallbacks.get() == 4,
                "A Java callback exception escaped into native code.");
            AtomicLong ownerSum = new AtomicLong();
            require(probe.ownerCycles(sequence -> {
                ownerSum.addAndGet(sequence);
                return sequence;
            }, 64) != FfmProbe.INVALID_VALUE && ownerSum.get() == 2080,
                "FFM owner lifecycle failed.");
            require(probe.validateNativeParent(0) == PARENT_SURFACE_INVALID, "Null native parent was accepted.");
            require(probe.liveNativeBytes() == 0, "Probe reports leaked native bytes.");
        } finally {
            probe.close();
        }
        expectFailure(probe::abiVersion, IllegalStateException.class);
        expectFailure(probe::close, IllegalStateException.class);
        System.out.println(
            "JDK 25 FFM layout, downcall, shared-Arena native-thread upcall, and closure " +
                "contract passed " +
                "for 8 ABI layouts."
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void expectFailure(Runnable operation, Class<? extends Throwable> expected) {
        try {
            operation.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) {
                return;
            }
            throw new IllegalStateException("Operation failed with an unexpected error type.", error);
        }
        throw new IllegalStateException("Operation unexpectedly succeeded.");
    }

    private ProbeContractMain() {
    }
}
