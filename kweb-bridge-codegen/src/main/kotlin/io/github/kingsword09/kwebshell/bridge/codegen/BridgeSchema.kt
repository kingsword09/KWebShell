package io.github.kingsword09.kwebshell.bridge.codegen

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class BridgeSchema(
    val namespace: String,
    val kotlinPackage: String,
    val version: Int,
    val types: List<BridgeType>,
    val methods: List<BridgeMethod>,
)

@Serializable
internal data class BridgeType(
    val name: String,
    val fields: List<BridgeField>,
)

@Serializable
internal data class BridgeField(
    val name: String,
    val type: String,
    val nullable: Boolean = false,
    @SerialName("list") val isList: Boolean = false,
)

@Serializable
internal data class BridgeMethod(
    val name: String,
    val request: String,
    val response: String,
)
