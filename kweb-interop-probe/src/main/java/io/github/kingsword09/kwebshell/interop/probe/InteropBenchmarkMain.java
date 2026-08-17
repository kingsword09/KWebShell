package io.github.kingsword09.kwebshell.interop.probe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class InteropBenchmarkMain {
    private static final double MAXIMUM_HIGH_FREQUENCY_P95_RATIO = 5.0;
    private static final double MAXIMUM_ZERO_ARGUMENT_P95_NANOS = 100.0;
    private static final double MAXIMUM_OWNER_P95_RATIO = 1000.0;
    private static final double MAXIMUM_OWNER_P95_NANOS = 5_000_000.0;
    private static final double MAXIMUM_P95_ALLOCATED_BYTES = 1024.0 * 1024.0;
    private static final long MAXIMUM_NATIVE_LIVE_BYTES = 0;

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                "Expected <absolute-interop-probe-library> <absolute-report-json>."
            );
        }
        if (Runtime.version().feature() != 25) {
            throw new IllegalStateException("Interop benchmark must run on JDK 25 LTS.");
        }
        Path library = ExactLibrary.argument(arguments[0], "interop probe library");
        Path report = Path.of(arguments[1]);
        JniProbe.load(library);
        long nativeLiveBytesBefore = JniProbe.liveNativeBytes();
        long nativeLiveBytesAfter;
        List<BenchmarkRunner.Result> results = new ArrayList<>();
        try (FfmProbe ffm = FfmProbe.open(library)) {
            addZeroArgumentBenchmarks(results, ffm);
            addIntegerBenchmarks(results, ffm);
            addUnicodeBenchmarks(results, ffm);
            addCallbackBenchmarks(results, ffm);
            addOwnerBenchmarks(results, ffm);
            nativeLiveBytesAfter = ffm.liveNativeBytes();
        }

        boolean accepted = evaluate(results) &&
            nativeLiveBytesBefore == MAXIMUM_NATIVE_LIVE_BYTES &&
            nativeLiveBytesAfter == MAXIMUM_NATIVE_LIVE_BYTES;
        BenchmarkReport.write(
            report,
            results,
            accepted ? "GO" : "NO_GO",
            MAXIMUM_HIGH_FREQUENCY_P95_RATIO,
            MAXIMUM_ZERO_ARGUMENT_P95_NANOS,
            MAXIMUM_OWNER_P95_RATIO,
            MAXIMUM_OWNER_P95_NANOS,
            MAXIMUM_P95_ALLOCATED_BYTES,
            MAXIMUM_NATIVE_LIVE_BYTES,
            nativeLiveBytesBefore,
            nativeLiveBytesAfter
        );
        print(results);
        if (!accepted) {
            throw new IllegalStateException("JNI/FFM benchmark exceeded an Objective 8.1 acceptance threshold.");
        }
        System.out.println("Objective 8.1 JNI/FFM benchmark decision: GO");
    }

    private static void addZeroArgumentBenchmarks(List<BenchmarkRunner.Result> results, FfmProbe ffm) {
        int operations = 100_000;
        addPair(
            results,
            "zero-argument-downcall",
            operations,
            () -> JniProbe.abiVersionBatch(operations),
            () -> ffm.abiVersionBatch(operations)
        );
    }

    private static void addIntegerBenchmarks(List<BenchmarkRunner.Result> results, FfmProbe ffm) {
        int operations = 100_000;
        addPair(
            results,
            "integer-downcall",
            operations,
            () -> integerBatch(operations, true, ffm),
            () -> integerBatch(operations, false, ffm)
        );
    }

    private static long integerBatch(int operations, boolean jni, FfmProbe ffm) {
        long result = 0;
        for (int index = 0; index < operations; ++index) {
            result ^= jni
                ? JniProbe.integerCall(index + 1L, 1280, 720)
                : ffm.integerCall(index + 1L, 1280, 720);
        }
        return result;
    }

    private static void addUnicodeBenchmarks(List<BenchmarkRunner.Result> results, FfmProbe ffm) {
        String unit = "KWebShell-\u4E2D\u6587-\uD83D\uDE80";
        addUnicodePair(results, ffm, "unicode-small", unit, 10_000);
        addUnicodePair(results, ffm, "unicode-medium", unit.repeat(64), 1_000);
        String maximum = "\uD83D\uDE80".repeat((int) (FfmProbe.MAXIMUM_UTF8_SIZE / 4));
        require(maximum.getBytes(StandardCharsets.UTF_8).length == FfmProbe.MAXIMUM_UTF8_SIZE,
            "Maximum Unicode benchmark payload is not exactly 1 MiB.");
        addUnicodePair(results, ffm, "unicode-maximum", maximum, 8);
    }

    private static void addUnicodePair(
        List<BenchmarkRunner.Result> results,
        FfmProbe ffm,
        String operation,
        String value,
        int operations
    ) {
        addPair(
            results,
            operation,
            operations,
            () -> unicodeBatch(operations, value, true, ffm),
            () -> unicodeBatch(operations, value, false, ffm)
        );
    }

    private static long unicodeBatch(int operations, String value, boolean jni, FfmProbe ffm) {
        long result = 0;
        for (int index = 0; index < operations; ++index) {
            result ^= jni ? JniProbe.utf8Call(value) : ffm.utf8Call(value);
        }
        return result;
    }

    private static void addCallbackBenchmarks(List<BenchmarkRunner.Result> results, FfmProbe ffm) {
        int fixedCallbacks = 100_000;
        addPair(
            results,
            "fixed-upcall",
            fixedCallbacks,
            () -> JniProbe.fixedUpcall(sequence -> sequence * 3, fixedCallbacks),
            () -> ffm.fixedUpcall(sequence -> sequence * 3, fixedCallbacks)
        );
        String value = "KWebShell-\u4E2D\u6587-\uD83D\uDE80".repeat(64);
        int utf8Callbacks = 1_000;
        addPair(
            results,
            "unicode-upcall",
            utf8Callbacks,
            () -> JniProbe.utf8Upcall((text, sequence) -> text.length() + sequence, value, utf8Callbacks),
            () -> ffm.utf8Upcall((text, sequence) -> text.length() + sequence, value, utf8Callbacks)
        );
    }

    private static void addOwnerBenchmarks(List<BenchmarkRunner.Result> results, FfmProbe ffm) {
        int owners = 100;
        addPair(
            results,
            "owner-lifecycle",
            owners,
            () -> JniProbe.ownerCycles(sequence -> sequence, owners),
            () -> ffm.ownerCycles(sequence -> sequence, owners)
        );
    }

    private static void addPair(
        List<BenchmarkRunner.Result> results,
        String operation,
        long operations,
        BenchmarkRunner.Batch jni,
        BenchmarkRunner.Batch ffm
    ) {
        results.add(BenchmarkRunner.measure(operation, "JNI", operations, jni));
        results.add(BenchmarkRunner.measure(operation, "FFM", operations, ffm));
    }

    private static boolean evaluate(List<BenchmarkRunner.Result> results) {
        boolean accepted = true;
        for (int index = 0; index < results.size(); index += 2) {
            BenchmarkRunner.Result jni = results.get(index);
            BenchmarkRunner.Result ffm = results.get(index + 1);
            require(jni.operation().equals(ffm.operation()), "Benchmark result pairing is invalid.");
            if (jni.operation().equals("zero-argument-downcall")) {
                accepted &= ffm.p95Nanos() <= MAXIMUM_ZERO_ARGUMENT_P95_NANOS;
                accepted &= ffm.p95AllocatedBytes() <= MAXIMUM_P95_ALLOCATED_BYTES;
                continue;
            }
            double maximumRatio = jni.operation().equals("owner-lifecycle")
                ? MAXIMUM_OWNER_P95_RATIO
                : MAXIMUM_HIGH_FREQUENCY_P95_RATIO;
            double ratio = ffm.p95Nanos() / Math.max(jni.p95Nanos(), 0.001);
            accepted &= ratio <= maximumRatio;
            accepted &= ffm.p95AllocatedBytes() <= MAXIMUM_P95_ALLOCATED_BYTES;
            if (jni.operation().equals("owner-lifecycle")) {
                accepted &= ffm.p95Nanos() <= MAXIMUM_OWNER_P95_NANOS;
            }
        }
        return accepted;
    }

    private static void print(List<BenchmarkRunner.Result> results) {
        for (BenchmarkRunner.Result result : results) {
            System.out.printf(
                Locale.ROOT,
                "%s %s median=%.3fns p95=%.3fns allocated-p95=%.3fB%n",
                result.operation(),
                result.boundary(),
                result.medianNanos(),
                result.p95Nanos(),
                result.p95AllocatedBytes()
            );
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private InteropBenchmarkMain() {
    }
}
