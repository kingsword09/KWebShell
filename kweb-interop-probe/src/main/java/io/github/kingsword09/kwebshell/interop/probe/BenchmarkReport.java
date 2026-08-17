package io.github.kingsword09.kwebshell.interop.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

final class BenchmarkReport {
    static void write(
        Path output,
        List<BenchmarkRunner.Result> results,
        String decision,
        double maximumHighFrequencyRatio,
        double maximumZeroArgumentP95Nanos,
        double maximumOwnerRatio,
        double maximumOwnerP95Nanos,
        double maximumP95AllocatedBytes,
        long maximumNativeLiveBytes,
        long nativeLiveBytesBefore,
        long nativeLiveBytesAfter
    ) throws IOException {
        if (!output.isAbsolute() || !output.equals(output.normalize())) {
            throw new IllegalArgumentException("Benchmark output path must be absolute and normalized: " + output);
        }
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Benchmark output path must have a parent directory.");
        }
        Files.createDirectories(parent);
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n")
            .append("  \"schemaVersion\": 1,\n")
            .append("  \"decision\": ").append(quoted(decision)).append(",\n")
            .append("  \"percentileMethod\": \"linear-interpolation\",\n")
            .append("  \"varianceMethod\": \"population\",\n")
            .append("  \"sampling\": {\n")
            .append("    \"warmupSamples\": ").append(BenchmarkRunner.WARMUP_SAMPLES).append(",\n")
            .append("    \"measuredSamples\": ").append(BenchmarkRunner.MEASURED_SAMPLES).append("\n")
            .append("  },\n")
            .append("  \"platform\": {\n")
            .append("    \"osName\": ").append(quoted(System.getProperty("os.name"))).append(",\n")
            .append("    \"osArch\": ").append(quoted(System.getProperty("os.arch"))).append(",\n")
            .append("    \"javaVersion\": ").append(quoted(System.getProperty("java.version"))).append("\n")
            .append("  },\n")
            .append("  \"thresholds\": {\n")
            .append(format("    \"maximumHighFrequencyP95Ratio\": %.3f,\n", maximumHighFrequencyRatio))
            .append(format("    \"maximumZeroArgumentP95Nanos\": %.3f,\n", maximumZeroArgumentP95Nanos))
            .append(format("    \"maximumOwnerP95Ratio\": %.3f,\n", maximumOwnerRatio))
            .append(format("    \"maximumOwnerP95Nanos\": %.3f,\n", maximumOwnerP95Nanos))
            .append(format("    \"maximumP95AllocatedBytes\": %.3f,\n", maximumP95AllocatedBytes))
            .append("    \"maximumNativeLiveBytes\": ").append(maximumNativeLiveBytes).append("\n")
            .append("  },\n")
            .append("  \"nativeMemory\": {\n")
            .append("    \"liveBytesBefore\": ").append(nativeLiveBytesBefore).append(",\n")
            .append("    \"liveBytesAfter\": ").append(nativeLiveBytesAfter).append("\n")
            .append("  },\n")
            .append("  \"results\": [\n");
        for (int index = 0; index < results.size(); ++index) {
            BenchmarkRunner.Result result = results.get(index);
            json.append("    {\n")
                .append("      \"operation\": ").append(quoted(result.operation())).append(",\n")
                .append("      \"boundary\": ").append(quoted(result.boundary())).append(",\n")
                .append(format("      \"medianNanos\": %.3f,\n", result.medianNanos()))
                .append(format("      \"p95Nanos\": %.3f,\n", result.p95Nanos()))
                .append(format(
                    "      \"latencyVarianceNanosSquared\": %.3f,\n",
                    result.latencyVarianceNanosSquared()
                ))
                .append(format("      \"medianAllocatedBytes\": %.3f,\n", result.medianAllocatedBytes()))
                .append(format("      \"p95AllocatedBytes\": %.3f,\n", result.p95AllocatedBytes()))
                .append(format(
                    "      \"allocatedBytesVariance\": %.3f,\n",
                    result.allocatedBytesVariance()
                ))
                .append("      \"operationsPerSample\": ").append(result.operationsPerSample()).append(",\n")
                .append("      \"samples\": ").append(result.samples()).append("\n")
                .append("    }");
            json.append(index + 1 == results.size() ? "\n" : ",\n");
        }
        json.append("  ]\n}\n");

        Path temporary = Files.createTempFile(parent, ".interop-benchmark-", ".tmp");
        try {
            Files.writeString(
                temporary,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (IOException | RuntimeException | Error error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private static String format(String pattern, double value) {
        return String.format(Locale.ROOT, pattern, value);
    }

    private static String quoted(String value) {
        String escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private BenchmarkReport() {
    }
}
