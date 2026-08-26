package io.github.kingsword09.kwebshell.core

public data class KWebBounds(
    public val width: Int,
    public val height: Int,
) {
    init {
        if (width <= 0 || height <= 0) {
            throw KWebConfigurationException(
                code = "page.bounds.invalid",
                details = mapOf("width" to width.toString(), "height" to height.toString()),
                message = "Page bounds must have positive width and height.",
            )
        }
    }
}
