package io.github.kingsword09.kwebshell.bridge.codegen

import java.nio.file.Path
import kotlin.io.path.absolute

public fun main(arguments: Array<String>): Unit {
    require(arguments.size == 2) {
        "Usage: kweb-bridge-codegen <schema.json> <output-directory>"
    }
    val schema = Path.of(arguments[0]).absolute().normalize()
    val output = Path.of(arguments[1]).absolute().normalize()
    BridgeGenerator().generate(schema, output).forEach(::println)
}
