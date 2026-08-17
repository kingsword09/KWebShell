package io.github.kingsword09.kwebshell.interop.probe;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

final class BenchmarkRunner {
    static final int WARMUP_SAMPLES = 5;
    static final int MEASURED_SAMPLES = 15;
    private static final com.sun.management.ThreadMXBean THREAD_BEAN = threadBean();
    private static volatile long blackhole;

    static Result measure(
        String operation,
        String boundary,
        long operationsPerSample,
        Batch batch
    ) {
        if (operationsPerSample <= 0) {
            throw new IllegalArgumentException("operationsPerSample must be positive.");
        }
        for (int sample = 0; sample < WARMUP_SAMPLES; ++sample) {
            blackhole ^= batch.run();
        }
        double[] latency = new double[MEASURED_SAMPLES];
        double[] allocation = new double[MEASURED_SAMPLES];
        long thread = Thread.currentThread().threadId();
        for (int sample = 0; sample < MEASURED_SAMPLES; ++sample) {
            long allocatedBefore = THREAD_BEAN.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            long value = batch.run();
            long elapsed = System.nanoTime() - started;
            long allocated = THREAD_BEAN.getThreadAllocatedBytes(thread) - allocatedBefore;
            blackhole ^= value;
            latency[sample] = (double) elapsed / operationsPerSample;
            allocation[sample] = (double) Math.max(0, allocated) / operationsPerSample;
        }
        Arrays.sort(latency);
        Arrays.sort(allocation);
        return new Result(
            operation,
            boundary,
            percentile(latency, 0.50),
            percentile(latency, 0.95),
            variance(latency),
            percentile(allocation, 0.50),
            percentile(allocation, 0.95),
            variance(allocation),
            operationsPerSample,
            MEASURED_SAMPLES
        );
    }

    private static double percentile(double[] sorted, double percentile) {
        double position = percentile * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = position - lower;
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction;
    }

    private static double variance(double[] values) {
        double mean = Arrays.stream(values).average().orElseThrow();
        double squaredDifferences = 0;
        for (double value : values) {
            double difference = value - mean;
            squaredDifferences += difference * difference;
        }
        return squaredDifferences / values.length;
    }

    private static com.sun.management.ThreadMXBean threadBean() {
        java.lang.management.ThreadMXBean candidate = ManagementFactory.getThreadMXBean();
        if (!(candidate instanceof com.sun.management.ThreadMXBean bean) ||
            !bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("HotSpot thread-allocation measurement is unavailable.");
        }
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        return bean;
    }

    @FunctionalInterface
    interface Batch {
        long run();
    }

    record Result(
        String operation,
        String boundary,
        double medianNanos,
        double p95Nanos,
        double latencyVarianceNanosSquared,
        double medianAllocatedBytes,
        double p95AllocatedBytes,
        double allocatedBytesVariance,
        long operationsPerSample,
        int samples
    ) {
    }

    private BenchmarkRunner() {
    }
}
