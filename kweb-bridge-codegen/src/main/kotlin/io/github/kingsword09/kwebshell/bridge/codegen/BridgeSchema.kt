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
    val streams: List<BridgeStream> = emptyList(),
)

/**
 * One declared server stream. The renderer opens it as one persistent query on
 * the same exact-origin transport; frames are chunk values, capacity is the
 * renderer queue bound (and the initial credit), and a binary stream carries
 * base64 chunks bounded by maxAggregateBytes.
 */
@Serializable
internal data class BridgeStream(
    val name: String,
    val request: String,
    val chunk: String = "Bytes",
    val binary: Boolean = false,
    val capacity: Int,
    val schemaVersion: Int,
    val maxAggregateBytes: Long? = null,
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
