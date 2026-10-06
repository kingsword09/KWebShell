package io.github.kingsword09.kwebshell.service.image

import java.security.MessageDigest

internal actual fun kWebImageSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }
