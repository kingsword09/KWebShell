package io.github.kingsword09.kwebshell.service.dialogs

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import java.nio.file.Files
import java.awt.Window

public fun main(): Unit = runBlocking {
    val window = onDialogsAwtThread {
        ComposeWindow().apply {
            title = "KWebShell dialogs smoke"
            setBounds(140, 140, 640, 480)
            isVisible = true
        }
    }
    val library = Path.of(requireNotNull(System.getProperty("kweb.dialogs.library")))
    val root = Files.createTempDirectory("kweb-native-dialog-")
    val input = Files.write(root.resolve("input.txt"), byteArrayOf(0, 17, 127, 255.toByte()))
    val output = root.resolve("output.txt")
    val visibleWindows = onDialogsAwtThread { Window.getWindows().filter { it.isShowing }.toSet() }
    try {
        val selector = NativeFileDialogSelector(
            window,
            library,
            root,
            mapOf(KWebFileDialogMode.OPEN to input.fileName.toString(), KWebFileDialogMode.SAVE to output.fileName.toString()),
        )
        val service = JvmKWebDialogs.openForTesting(window, selector)
        try {
            for ((mode, path) in listOf(KWebFileDialogMode.OPEN to input, KWebFileDialogMode.SAVE to output)) {
                val pending = async(Dispatchers.Default) {
                    runCatching { service.selectFile(KWebFileDialogRequest(mode, "KWebShell select $mode",
                        defaultDirectory = root.toString(), defaultName = path.fileName.toString(),
                        filters = listOf(KWebFileFilter("Text", listOf("txt")))) ) }
                }
                chooseNativeFile(selector, pending, path, mode)
                val selected = requireNotNull(pending.await().getOrThrow())
                check(selected.name == path.fileName.toString())
                check(selected.mode == mode)
                when (mode) {
                    KWebFileDialogMode.OPEN -> {
                        val read = service.readFile(selected.handle, 0, 4)
                        check(read.bytes == listOf(0, 17, 127, 255) && read.eof)
                    }
                    KWebFileDialogMode.SAVE -> {
                        check(service.writeFile(selected.handle, 0, listOf(255, 127, 17, 0)).written == 4)
                        check(service.truncateFile(selected.handle, 3).sizeBytes == 3L)
                    }
                }
                service.closeFile(selected.handle)
                check(!selector.isVisible())
                println("Native $mode selection returned a scoped handle and completed real file IO.")
            }
            check(Files.readAllBytes(output).toList() == listOf(255.toByte(), 127.toByte(), 17.toByte()))
            val cancelled = async(Dispatchers.Default) {
                runCatching { service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Cancel")) }
            }
            cancelNativePicker(selector, cancelled)
            check(cancelled.await().getOrThrow() == null)
            val aborted = async(Dispatchers.Default) {
                service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Abort"))
            }
            awaitNativePicker(selector, aborted)
            aborted.cancelAndJoin()
            check(!selector.isVisible())
            println("Native user cancellation and coroutine abort released the active picker.")
        } finally {
            service.close()
        }
        for (mode in KWebFileDialogMode.entries) {
            val selector = NativeFileDialogSelector(
                window,
                library,
                root,
                mapOf(KWebFileDialogMode.OPEN to input.fileName.toString(), KWebFileDialogMode.SAVE to output.fileName.toString()),
            )
            val service = JvmKWebDialogs.openForTesting(window, selector)
            try {
                val selection = async(Dispatchers.Default) {
                    runCatching { service.selectFile(KWebFileDialogRequest(mode, "KWebShell dialog smoke")) }
                }
                awaitNativePicker(selector, selection)
                service.close()
                val failure = withTimeout(5_000) { selection.await() }.exceptionOrNull()
                check(failure is KWebNativeException && failure.code == "service.owner-closed") { "Unexpected cancellation: $failure" }
                check(!selector.isVisible())
                check(service.lifecycle.value == KWebLifecycleState.CLOSED)
                check(onDialogsAwtThread { window.isDisplayable && window.isShowing })
                println("Native $mode picker became visible, cancelled, and released its owner.")
            } finally {
                service.close()
            }
        }
        check(onDialogsAwtThread { Window.getWindows().filter { it.isShowing }.toSet() } == visibleWindows)
    } finally {
        onDialogsAwtThread { window.dispose() }
        Files.deleteIfExists(output)
        Files.delete(input)
        Files.delete(root)
    }
    println("KWebShell native file dialog selection, IO, and cancellation smoke passed.")
}
