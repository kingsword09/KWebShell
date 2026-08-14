package io.github.kingsword09.kwebshell.extensions

internal enum class KWebPortablePathIssue {
    EMPTY,
    ABSOLUTE,
    INVALID_CHARACTER,
    TRAVERSAL,
    TRAILING_DOT_OR_SPACE,
    RESERVED_NAME,
    WILDCARD,
    TOO_LONG,
}

internal fun portablePathIssue(
    value: String,
    allowWildcard: Boolean = false,
): KWebPortablePathIssue? {
    if (value.isEmpty()) return KWebPortablePathIssue.EMPTY
    if (value.length > MAX_PATH_LENGTH || value.split('/').any { it.length > MAX_SEGMENT_LENGTH }) {
        return KWebPortablePathIssue.TOO_LONG
    }
    if (value.startsWith('/') || value.startsWith('\\')) return KWebPortablePathIssue.ABSOLUTE
    if (value.any { it == '\u0000' || it == '\u007f' || it.code < 0x20 || it in WINDOWS_INVALID_CHARACTERS }) {
        return KWebPortablePathIssue.INVALID_CHARACTER
    }
    if (!allowWildcard && '*' in value) return KWebPortablePathIssue.WILDCARD
    if (allowWildcard && value.any { it in "?[]{}" }) return KWebPortablePathIssue.WILDCARD

    val segments = value.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return KWebPortablePathIssue.TRAVERSAL
    if (segments.any { it.endsWith('.') || it.endsWith(' ') }) return KWebPortablePathIssue.TRAILING_DOT_OR_SPACE
    if (segments.any(::isWindowsReservedName)) return KWebPortablePathIssue.RESERVED_NAME
    return null
}

internal fun canonicalWebAccessibleResourcePath(value: String): String =
    if (value.startsWith('/') && !value.startsWith("//")) value.drop(1) else value

private fun isWindowsReservedName(segment: String): Boolean {
    val basename = segment.substringBefore('.').uppercase()
    return basename in WINDOWS_RESERVED_NAMES ||
        basename.length == 4 &&
        (basename.startsWith("COM") || basename.startsWith("LPT")) &&
        (basename.last() in '1'..'9' || basename.last() in WINDOWS_RESERVED_SUPERSCRIPT_DIGITS)
}

private const val MAX_PATH_LENGTH: Int = 4096
private const val MAX_SEGMENT_LENGTH: Int = 255
private const val WINDOWS_INVALID_CHARACTERS: String = "\\:<>\"|?"
private val WINDOWS_RESERVED_SUPERSCRIPT_DIGITS: Set<Char> = setOf('\u00b9', '\u00b2', '\u00b3')
private val WINDOWS_RESERVED_NAMES: Set<String> = setOf(
    "CON",
    "PRN",
    "AUX",
    "NUL",
    "CLOCK$",
    "CONIN$",
    "CONOUT$",
)
