package io.github.kingsword09.kwebshell.interop.probe;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class JniFfmContractMain {
    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected <absolute-interop-probe-library>.");
        }
        Path library = ExactLibrary.argument(arguments[0], "interop probe library");
        JniProbe.load(library);
        try (FfmProbe ffm = FfmProbe.open(library)) {
            require(JniProbe.abiVersion() == ffm.abiVersion(),
                "JNI and FFM zero-argument results differ.");
            require(JniProbe.integerCall(91, 1280, 720) == ffm.integerCall(91, 1280, 720),
                "JNI and FFM integer results differ.");
            String unicode = "KWebShell - \u4E2D\u6587 - \uD83D\uDE80";
            require(JniProbe.utf8Call(unicode) == ffm.utf8Call(unicode),
                "JNI and FFM Unicode results differ.");
            require(JniProbe.utf8Call("\uD800") == FfmProbe.INVALID_VALUE,
                "JNI accepted an unpaired surrogate.");

            AtomicLong jniFixedSum = new AtomicLong();
            AtomicLong ffmFixedSum = new AtomicLong();
            long jniFixed = JniProbe.fixedUpcall(sequence -> {
                jniFixedSum.addAndGet(sequence);
                return sequence * 3;
            }, 32);
            long ffmFixed = ffm.fixedUpcall(sequence -> {
                ffmFixedSum.addAndGet(sequence);
                return sequence * 3;
            }, 32);
            require(jniFixed == ffmFixed && jniFixedSum.get() == ffmFixedSum.get(),
                "JNI and FFM fixed upcalls differ.");

            AtomicLong jniUtf8Sum = new AtomicLong();
            AtomicLong ffmUtf8Sum = new AtomicLong();
            long jniUtf8 = JniProbe.utf8Upcall((value, sequence) -> {
                require(value.equals(unicode), "JNI UTF-8 callback changed its payload.");
                jniUtf8Sum.addAndGet(sequence);
                return value.length() + sequence;
            }, unicode, 16);
            long ffmUtf8 = ffm.utf8Upcall((value, sequence) -> {
                require(value.equals(unicode), "FFM UTF-8 callback changed its payload.");
                ffmUtf8Sum.addAndGet(sequence);
                return value.length() + sequence;
            }, unicode, 16);
            require(jniUtf8 == ffmUtf8 && jniUtf8Sum.get() == ffmUtf8Sum.get(),
                "JNI and FFM UTF-8 upcalls differ.");

            AtomicInteger jniFailures = new AtomicInteger();
            expectFailure(() -> JniProbe.fixedUpcall(sequence -> {
                jniFailures.incrementAndGet();
                throw new IllegalStateException("expected JNI callback failure");
            }, 4));
            require(jniFailures.get() == 4, "JNI callback exception escaped native code.");

            AtomicLong jniOwners = new AtomicLong();
            AtomicLong ffmOwners = new AtomicLong();
            long jniOwnerResult = JniProbe.ownerCycles(sequence -> {
                jniOwners.addAndGet(sequence);
                return sequence;
            }, 64);
            long ffmOwnerResult = ffm.ownerCycles(sequence -> {
                ffmOwners.addAndGet(sequence);
                return sequence;
            }, 64);
            require(jniOwnerResult == ffmOwnerResult && jniOwners.get() == ffmOwners.get(),
                "JNI and FFM owner lifecycles differ.");
            require(JniProbe.liveNativeBytes() == 0 && ffm.liveNativeBytes() == 0,
                "Interop contract leaked native bytes.");
        }
        System.out.println("JNI and JDK 25 FFM behavior matched through the same native probe library.");
    }

    private static void expectFailure(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new IllegalStateException("Callback failure unexpectedly crossed native code without an error.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private JniFfmContractMain() {
    }
}
