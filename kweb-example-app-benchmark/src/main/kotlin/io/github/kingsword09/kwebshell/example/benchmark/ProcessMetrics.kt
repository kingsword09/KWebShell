package io.github.kingsword09.kwebshell.example.benchmark

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class ProcessMetrics(
    val residentBytes: Double,
    val privateBytes: Double,
    val cpuMs: Double,
    val threadCount: Double,
)

internal object ProcessMetricsSampler {
    fun sample(rootPid: Long = ProcessHandle.current().pid()): ProcessMetrics {
        val pids = processTree(rootPid)
        val samples = if (isWindows()) sampleWindowsTree(rootPid, pids) else pids.map { pid -> sampleOne(pid) }
        return ProcessMetrics(
            residentBytes = samples.sumOf { it.residentBytes },
            privateBytes = samples.sumOf { it.privateBytes },
            cpuMs = samples.sumOf { it.cpuMs },
            threadCount = samples.sumOf { it.threadCount },
        )
    }

    private fun processTree(rootPid: Long): List<Long> {
        val root = ProcessHandle.of(rootPid).orElseThrow {
            BenchmarkException("process.root-missing", "Process $rootPid is not alive.")
        }
        val descendants = root.descendants().map(ProcessHandle::pid).toList()
        return listOf(rootPid) + descendants
    }

    private fun sampleOne(pid: Long): ProcessMetrics = when {
        isMac() -> sampleMac(pid)
        isLinux() -> sampleLinux(pid)
        else -> throw BenchmarkException("process.platform-unsupported", "Process metrics are unsupported on '${System.getProperty("os.name")}'.")
    }

    private fun sampleMac(pid: Long): ProcessMetrics {
        val ps = runCommand(listOf("/bin/ps", "-p", pid.toString(), "-o", "rss=,time="), "process.mac.ps")
            .trim().split(Regex("\\s+"))
        if (ps.size != 2) throw BenchmarkException("process.mac.ps-invalid", "macOS ps returned an invalid row for PID $pid.")
        val resident = ps[0].toLongOrNull()?.times(1024L)?.toDouble()
            ?: throw BenchmarkException("process.mac.rss-invalid", "macOS RSS for PID $pid is invalid.")
        val cpu = parseCpuTime(ps[1])
        val footprint = runCommand(listOf("/usr/bin/vmmap", "-summary", pid.toString()), "process.mac.vmmap")
        val match = Regex("(?m)^Physical footprint:\\s+([0-9.]+)([KMG]?)$").find(footprint)
            ?: throw BenchmarkException("process.mac.footprint-missing", "vmmap returned no physical footprint for PID $pid.")
        val multiplier = when (match.groupValues[2]) {
            "" -> 1.0
            "K" -> 1024.0
            "M" -> 1024.0 * 1024.0
            "G" -> 1024.0 * 1024.0 * 1024.0
            else -> throw BenchmarkException("process.mac.footprint-unit", "vmmap returned an unknown footprint unit.")
        }
        val privateBytes = match.groupValues[1].toDouble() * multiplier
        val threadCount = runCommand(listOf("/bin/ps", "-M", pid.toString()), "process.mac.threads")
            .lineSequence().filter(String::isNotBlank).count().minus(1).takeIf { it > 0 }?.toDouble()
            ?: throw BenchmarkException("process.mac.threads-invalid", "macOS thread count for PID $pid is invalid.")
        return ProcessMetrics(resident, privateBytes, cpu, threadCount)
    }

    private fun sampleWindowsTree(rootPid: Long, pids: List<Long>): List<ProcessMetrics> {
        val dollar = '$'
        val script = dollar + "processes = Get-Process -Id ${pids.joinToString(",")} -ErrorAction SilentlyContinue; " +
            dollar + "processes | ForEach-Object { [pscustomobject]@{" +
            "Id=[double]" + dollar + "_.Id;" +
            "WorkingSet=[double]" + dollar + "_.WorkingSet64;" +
            "Private=[double]" + dollar + "_.PrivateMemorySize64;" +
            "CpuMs=[double]" + dollar + "_.TotalProcessorTime.TotalMilliseconds;" +
            "Threads=[double]" + dollar + "_.Threads.Count} } | ConvertTo-Csv -NoTypeInformation"
        val output = runCommand(listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", script), "process.windows.powershell")
        val rows = output.lineSequence().filter(String::isNotBlank).toList()
        if (rows.size < 2) throw BenchmarkException("process.windows.output-invalid", "PowerShell returned no process rows.")
        val samples = rows.drop(1).map { row ->
            val values = parseCsvLine(row).map { value ->
                value.toDoubleOrNull() ?: throw BenchmarkException("process.windows.value-invalid", "PowerShell value '$value' is invalid.")
            }
            if (values.size != 5) throw BenchmarkException("process.windows.column-count", "PowerShell returned ${values.size} process metrics.")
            WindowsProcessSample(
                pid = values[0].toLong(),
                metrics = ProcessMetrics(values[1], values[2], values[3], values[4]),
            )
        }
        if (samples.none { it.pid == rootPid }) {
            throw BenchmarkException("process.windows.root-missing", "PowerShell returned no row for root PID $rootPid.")
        }
        return samples.map(WindowsProcessSample::metrics)
    }

    private fun sampleLinux(pid: Long): ProcessMetrics {
        val status = Files.readAllLines(Path.of("/proc/$pid/status"))
        fun statusBytes(name: String): Double {
            val value = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
                ?: throw BenchmarkException("process.linux.status-missing", "Linux status field '$name' is missing for PID $pid.")
            return value * 1024.0
        }
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        val fields = stat.substringAfterLast(") ").split(' ')
        if (fields.size < 18) throw BenchmarkException("process.linux.stat-invalid", "Linux /proc stat for PID $pid is incomplete.")
        val ticks = (fields[11].toLongOrNull() ?: throw BenchmarkException("process.linux.cpu-invalid", "Linux user ticks are invalid.")) +
            (fields[12].toLongOrNull() ?: throw BenchmarkException("process.linux.cpu-invalid", "Linux system ticks are invalid."))
        val clockTicks = runCommand(listOf("getconf", "CLK_TCK"), "process.linux.clock-ticks").trim().toLongOrNull()
            ?: throw BenchmarkException("process.linux.clock-ticks-invalid", "Linux CLK_TCK is invalid.")
        val threads = status.firstOrNull { it.startsWith("Threads:") }?.substringAfter(':')?.trim()?.toDoubleOrNull()
            ?: throw BenchmarkException("process.linux.threads-invalid", "Linux thread count is invalid.")
        return ProcessMetrics(statusBytes("VmRSS"), statusBytes("RssAnon"), ticks * 1000.0 / clockTicks, threads)
    }

    private fun runCommand(command: List<String>, code: String): String {
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            throw BenchmarkException("$code-start-failed", "Could not start '${command.first()}'.", error)
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw BenchmarkException("$code-timeout", "Command '${command.first()}' exceeded 15 seconds.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.exitValue() != 0) throw BenchmarkException(code, "Command '${command.first()}' exited ${process.exitValue()}: $output")
        return output
    }

    private fun parseCpuTime(value: String): Double {
        val days = value.substringBefore('-', missingDelimiterValue = "0").toLongOrNull() ?: 0L
        val clock = value.substringAfter('-', value).split(':')
        val seconds = when (clock.size) {
            2 -> clock[0].toDouble() * 60.0 + clock[1].toDouble()
            3 -> clock[0].toDouble() * 3600.0 + clock[1].toDouble() * 60.0 + clock[2].toDouble()
            else -> throw BenchmarkException("process.cpu-time-invalid", "CPU time '$value' is invalid.")
        }
        return Duration.ofDays(days).toMillis() + seconds * 1000.0
    }

    private fun parseCsvLine(line: String): List<String> = line.trim().removePrefix("\"").removeSuffix("\"").split("\",\"")
    private data class WindowsProcessSample(val pid: Long, val metrics: ProcessMetrics)
    private fun isMac() = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac")
    private fun isWindows() = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")
    private fun isLinux() = System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("linux")
}
