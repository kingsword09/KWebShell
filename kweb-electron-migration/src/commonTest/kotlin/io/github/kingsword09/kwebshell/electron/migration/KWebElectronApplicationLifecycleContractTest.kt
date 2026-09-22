package io.github.kingsword09.kwebshell.electron.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebElectronApplicationLifecycleContractTest {
    @Test
    fun mapsClosedLifecycleDeclarations() {
        val report = KWebElectronApplicationLifecycleMapper.map(validManifest())

        assertTrue(report.ready)
        assertEquals(
            listOf("open-file", "open-url", "quit", "relaunch", "requestSingleInstanceLock", "second-instance", "whenReady"),
            report.mappedDeclarations,
        )
    }

    @Test
    fun blocksRawArgumentsRendererQuitDynamicListenersAndArbitraryRelaunch() {
        val report = KWebElectronApplicationLifecycleMapper.map(
            validManifest().copy(
                dynamicListeners = listOf("app.on"),
                readsRawArguments = true,
                rendererRequestsQuit = true,
                arbitraryRelaunchExecutable = "/tmp/other",
            ),
        )

        assertFalse(report.ready)
        assertEquals(
            listOf(
                "dynamic-listener:app.on",
                "raw-process-arguments",
                "renderer-controlled-quit",
                "arbitrary-relaunch-executable",
            ),
            report.blockingFindings,
        )
    }

    @Test
    fun invalidIdentityOrTargetFailsBeforeMapping() {
        val error = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronApplicationLifecycleMapper.map(validManifest().copy(targets = listOf("freebsd-x64")))
        }
        assertEquals(KWebElectronMigrationErrorCode.LIFECYCLE_INVALID, error.code)
    }

    private fun validManifest(): KWebElectronApplicationLifecycleManifest =
        KWebElectronApplicationLifecycleManifest(
            schemaVersion = 1,
            applicationId = "io.github.kwebshell.fixture",
            targets = listOf("macos-arm64", "windows-x64", "linux-x64"),
            declarations = listOf(
                "whenReady",
                "requestSingleInstanceLock",
                "second-instance",
                "open-url",
                "open-file",
                "quit",
                "relaunch",
            ),
        )
}
