package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeContractSessionTest {
    @Test
    fun resolvesOnlyDeclaredPlatformLibraries() {
        assertEquals("kwebshell_abi.dll", nativeAbiLibraryFileName("Windows 11"))
        assertEquals("libkwebshell_abi.dylib", nativeAbiLibraryFileName("Mac OS X"))
        assertEquals("libkwebshell_abi.so", nativeAbiLibraryFileName("Linux"))
        assertEquals("kwebshell_engine.dll", nativeEngineLibraryFileName("Windows 11"))
        assertEquals("libkwebshell_engine.dylib", nativeEngineLibraryFileName("Mac OS X"))
        assertEquals("libkwebshell_engine.so", nativeEngineLibraryFileName("Linux"))

        val error = assertFailsWith<KWebConfigurationException> {
            nativeAbiLibraryFileName("FreeBSD")
        }
        assertEquals("native.platform.unsupported", error.code)
    }

    @Test
    fun rejectsInvalidOrIncompleteNativeLibraryPaths() {
        val invalidPathError = assertFailsWith<KWebConfigurationException> {
            resolveNativeLibraryPaths("\u0000", "Mac OS X")
        }
        assertEquals("native.library.path-invalid", invalidPathError.code)

        val directory = Files.createTempDirectory("kwebshell-native-path-test")
        val jniFile = Files.createFile(directory.resolve("jni-library"))
        try {
            val missingAbiError = assertFailsWith<KWebConfigurationException> {
                resolveNativeLibraryPaths(jniFile.toString(), System.getProperty("os.name"))
            }
            assertEquals("native.abi-library.path-invalid", missingAbiError.code)
        } finally {
            Files.deleteIfExists(jniFile)
            Files.deleteIfExists(directory)
        }

        val completeDirectory = Files.createTempDirectory("kwebshell-native-engine-path-test")
        val completeJni = Files.createFile(completeDirectory.resolve("jni-library"))
        val completeAbi = Files.createFile(
            completeDirectory.resolve(nativeAbiLibraryFileName(System.getProperty("os.name"))),
        )
        try {
            val missingEngineError = assertFailsWith<KWebConfigurationException> {
                resolveNativeLibraryPaths(completeJni.toString(), System.getProperty("os.name"))
            }
            assertEquals("native.engine-library.path-invalid", missingEngineError.code)
        } finally {
            Files.deleteIfExists(completeAbi)
            Files.deleteIfExists(completeJni)
            Files.deleteIfExists(completeDirectory)
        }
    }

    @Test
    fun loadsTheExactVersionedNativeLibrary() {
        assertEquals(NATIVE_ABI_VERSION, NativeBindings.abiVersion())
        assertEquals(0, NativeContractSession.liveNativeSessionCount())
    }

    @Test
    fun preservesUnicodeAndDispatchesOrderedCallbacksOnOneThread() {
        val events = CopyOnWriteArrayList<NativeContractEvent>()
        val callbackThreads = CopyOnWriteArrayList<String>()
        val session = NativeContractSession.open { event ->
            events += event
            callbackThreads += Thread.currentThread().name
        }
        val url = "https://example.test/路径?q=🙂"

        session.requestNavigation(url)
        session.resize(1280, 720)
        session.close()

        assertEquals(
            listOf(
                NativeEventType.SESSION_OPENED,
                NativeEventType.NAVIGATION_REQUESTED,
                NativeEventType.VIEWPORT_CHANGED,
                NativeEventType.SESSION_CLOSED,
            ),
            events.map { it.type },
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.sequence })
        assertEquals(url, events.single { it.type == NativeEventType.NAVIGATION_REQUESTED }.text)
        val viewport = events.single { it.type == NativeEventType.VIEWPORT_CHANGED }
        assertEquals(1280, viewport.width)
        assertEquals(720, viewport.height)
        assertEquals(1, callbackThreads.toSet().size)
        assertTrue(callbackThreads.toSet().singleOrNull()?.startsWith("KWebShell-JNI-callback-") == true)
        assertEquals(KWebLifecycleState.CLOSED, session.lifecycle.value)
        assertEquals(0, NativeContractSession.liveNativeSessionCount())
    }

    @Test
    fun repeatsTwoHundredFiftySixJniCyclesWithoutLeaks() {
        repeat(256) { cycle ->
            val session = NativeContractSession.open()
            session.requestNavigation("https://cycle.test/$cycle")
            session.resize(640 + cycle, 480 + cycle)
            session.close()
            assertEquals(KWebLifecycleState.CLOSED, session.lifecycle.value)
            assertEquals(0, NativeContractSession.liveNativeSessionCount())
        }
    }

    @Test
    fun rejectsInvalidEncodingAndDimensionsWithTypedErrors() {
        val session = NativeContractSession.open()

        val textError = assertFailsWith<KWebNativeException> {
            session.requestNavigation("https://invalid.test/\uD800")
        }
        assertEquals("native.abi.invalid-text-encoding", textError.code)
        val dimensionError = assertFailsWith<KWebNativeException> {
            session.resize(0, 720)
        }
        assertEquals("native.abi.invalid-dimensions", dimensionError.code)

        session.close()
        assertEquals(0, NativeContractSession.liveNativeSessionCount())
    }

    @Test
    fun concurrentCommandsAndCloseReturnOnlyDeclaredErrors() {
        val events = CopyOnWriteArrayList<NativeContractEvent>()
        val session = NativeContractSession.open { events += it }
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(9)

        repeat(8) { threadIndex ->
            executor.submit {
                start.await()
                repeat(100) { request ->
                    try {
                        if ((request + threadIndex) % 2 == 0) {
                            session.requestNavigation("https://race.test/$threadIndex/$request")
                        } else {
                            session.resize(800 + request, 600 + request)
                        }
                    } catch (error: KWebNativeException) {
                        if (error.code !in DECLARED_RACE_ERRORS) {
                            failures += error
                        }
                    } catch (error: Throwable) {
                        failures += error
                    }
                }
            }
        }
        executor.submit {
            start.await()
            try {
                session.close()
            } catch (error: Throwable) {
                failures += error
            }
        }
        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))

        session.close()
        assertTrue(failures.isEmpty(), failures.joinToString { it.toString() })
        assertEquals(KWebLifecycleState.CLOSED, session.lifecycle.value)
        assertEquals(NativeEventType.SESSION_CLOSED, events.last().type)
        assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
        assertEquals(0, NativeContractSession.liveNativeSessionCount())
    }

    @Test
    fun closeIsIdempotentAndNoCallbackStartsAfterItReturns() {
        val events = CopyOnWriteArrayList<NativeContractEvent>()
        val session = NativeContractSession.open { events += it }
        session.requestNavigation("https://close.test")
        session.close()
        val countAfterClose = events.size

        Thread.sleep(100)
        session.close()

        assertEquals(countAfterClose, events.size)
        val error = assertFailsWith<KWebNativeException> {
            session.resize(800, 600)
        }
        assertEquals("native.session.closed", error.code)
        assertEquals(0, NativeContractSession.liveNativeSessionCount())
    }

    @Test
    fun concurrentKotlinCloseCallersShareOneCompletion() {
        val session = NativeContractSession.open()
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(8)
        repeat(8) {
            executor.submit {
                start.await()
                try {
                    session.close()
                } catch (error: Throwable) {
                    failures += error
                }
            }
        }

        start.countDown()
        executor.shutdown()
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))

        assertTrue(failures.isEmpty(), failures.joinToString { it.toString() })
        assertEquals(KWebLifecycleState.CLOSED, session.lifecycle.value)
        assertEquals(0, NativeContractSession.liveNativeSessionCount())
    }

    @Test
    fun listenerFailureIsObservableAndStillReleasesNativeOwnership() {
        val session = NativeContractSession.open { event ->
            if (event.type == NativeEventType.NAVIGATION_REQUESTED) {
                error("listener failure")
            }
        }
        session.requestNavigation("https://listener-failure.test")

        val error = assertFailsWith<KWebNativeException> {
            session.close()
        }

        assertEquals("native.callback.listener-failed", error.code)
        assertEquals(KWebLifecycleState.FAILED, session.lifecycle.value)
        assertEquals(0, NativeContractSession.liveNativeSessionCount())
    }

    @Test
    fun callbackDispatcherCloseIsRejectedWithoutDeadlock() {
        val sessionReference = AtomicReference<NativeContractSession>()
        val callbackError = AtomicReference<KWebNativeException>()
        val session = NativeContractSession.open { event ->
            if (event.type == NativeEventType.NAVIGATION_REQUESTED) {
                try {
                    sessionReference.get().close()
                } catch (error: KWebNativeException) {
                    callbackError.set(error)
                }
            }
        }
        sessionReference.set(session)
        session.requestNavigation("https://reentrant-close.test")
        session.close()

        assertEquals("native.session.close-from-callback", callbackError.get()?.code)
        assertEquals(KWebLifecycleState.CLOSED, session.lifecycle.value)
        assertEquals(0, NativeContractSession.liveNativeSessionCount())
    }

    private companion object {
        val DECLARED_RACE_ERRORS = setOf(
            "native.session.closed",
            "native.abi.session-closing",
            "native.abi.invalid-handle",
        )
    }
}
