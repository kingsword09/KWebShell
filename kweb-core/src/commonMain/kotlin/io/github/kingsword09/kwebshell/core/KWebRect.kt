package io.github.kingsword09.kwebshell.core

public data class KWebRect(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw KWebConfigurationException(
                code = "page.bounds.invalid",
                details = mapOf(
                    "x" to x.toString(),
                    "y" to y.toString(),
                    "width" to width.toString(),
                    "height" to height.toString(),
                ),
                message = "Page bounds must have non-negative origin and positive width and height.",
            )
        }
    }
}
