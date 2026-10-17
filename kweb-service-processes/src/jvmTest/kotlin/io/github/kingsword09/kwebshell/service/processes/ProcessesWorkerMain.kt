package io.github.kingsword09.kwebshell.service.processes

/**
 * The declared JVM worker entry point of the RFC 0024 integration fixture. It runs
 * as an ordinary managed process: the service verifies the launcher identity,
 * applies the declared limits, and owns its lifetime.
 */
public fun main() {
    println("kweb-worker-ready")
    println("kweb-worker-classpath-entries=" + System.getProperty("java.class.path").split(java.io.File.pathSeparator).size)
}
