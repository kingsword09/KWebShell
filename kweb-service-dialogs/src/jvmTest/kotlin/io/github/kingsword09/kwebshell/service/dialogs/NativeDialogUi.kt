package io.github.kingsword09.kwebshell.service.dialogs

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Robot
import java.awt.Window
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

internal suspend fun awaitNativePicker(selector: NativeFileDialogSelector, pending: Deferred<*>) {
    withTimeout(10_000) {
        while (!selector.isVisible()) {
            check(!pending.isCompleted) { "Native picker completed before becoming visible: ${pending.await()}" }
            delay(10)
        }
    }
}

internal suspend fun chooseNativeFile(
    selector: NativeFileDialogSelector,
    pending: Deferred<*>,
    path: Path,
    mode: KWebFileDialogMode,
) {
    awaitNativePicker(selector, pending)
    println("Native picker is ready for ${path.fileName}.")
    val robot = Robot().apply { autoDelay = 20; isAutoWaitForIdle = true }
    robot.evidence("ready")
    val os = System.getProperty("os.name")
    if (os.startsWith("Mac")) {
        // Go To Folder is stable regardless of the panel's current focus or view mode.
        robot.chord(KeyEvent.VK_META, KeyEvent.VK_SHIFT, KeyEvent.VK_G)
        delay(400)
        robot.chord(KeyEvent.VK_META, KeyEvent.VK_A)
        robot.typeAscii(if (mode == KWebFileDialogMode.OPEN) path.toString() else path.parent.toString())
        robot.waitForIdle()
        delay(600)
        robot.chord(KeyEvent.VK_ENTER)
        delay(600)
        robot.chord(KeyEvent.VK_ENTER)
    } else if (os.startsWith("Windows")) {
        // IFileDialog receives the exact folder and file name through the native request.
        // Confirming its default button verifies those ABI fields without focus-sensitive accelerators.
        robot.chord(KeyEvent.VK_ALT, KeyEvent.VK_O)
    } else {
        robot.chord(KeyEvent.VK_CONTROL, KeyEvent.VK_L)
        delay(400)
        robot.evidence("location")
        robot.chord(KeyEvent.VK_CONTROL, KeyEvent.VK_A)
        robot.typeAscii(path.toAbsolutePath().toString())
        robot.waitForIdle()
        delay(600)
        robot.evidence("typed")
        robot.chord(KeyEvent.VK_ENTER)
    }
    robot.evidence("submitted")
    println("Native picker received the file selection keys.")
    try {
        withTimeout(15_000) {
            // Native panels can load or validate a target asynchronously before enabling Open/Save.
            while ((os.startsWith("Mac") || os.startsWith("Windows")) &&
                !pending.isCompleted && selector.isVisible()) {
                if (withTimeoutOrNull(750) { pending.await(); true } == null && selector.isVisible()) {
                    if (os.startsWith("Windows")) robot.chord(KeyEvent.VK_ALT, KeyEvent.VK_O)
                    else robot.chord(KeyEvent.VK_ENTER)
                }
            }
            pending.await()
        }
    } finally {
        robot.evidence("completed")
    }
}

private fun Robot.evidence(stage: String) {
    val directory = System.getProperty("kweb.dialogs.ui.evidence")?.let(Path::of) ?: return
    Files.createDirectories(directory)
    val bounds = onDialogsAwtThread { Window.getWindows().first { it.isShowing }.bounds }
    ImageIO.write(createScreenCapture(bounds), "png", directory.resolve("$stage.png").toFile())
}

internal suspend fun cancelNativePicker(selector: NativeFileDialogSelector, pending: Deferred<*>) {
    awaitNativePicker(selector, pending)
    val robot = Robot()
    robot.chord(KeyEvent.VK_ESCAPE)
    if (System.getProperty("os.name").startsWith("Mac") &&
        withTimeoutOrNull(1_000) { pending.await() } == null) {
        robot.chord(KeyEvent.VK_META, KeyEvent.VK_PERIOD)
    }
    withTimeout(5_000) { pending.await() }
}

private fun Robot.chord(vararg codes: Int) {
    codes.forEach(::keyPress)
    codes.reversed().forEach(::keyRelease)
}

private fun Robot.typeAscii(value: String) {
    value.forEach { character ->
        val (key, shift) = when (character) {
            ':' -> KeyEvent.VK_SEMICOLON to true
            '_' -> KeyEvent.VK_MINUS to true
            else -> KeyEvent.getExtendedKeyCodeForChar(character.uppercaseChar().code) to character.isUpperCase()
        }
        require(character.code in 32..126 && key != KeyEvent.VK_UNDEFINED) { "UI fixture path must be ASCII." }
        if (shift) keyPress(KeyEvent.VK_SHIFT)
        keyPress(key)
        keyRelease(key)
        if (shift) keyRelease(KeyEvent.VK_SHIFT)
    }
}
