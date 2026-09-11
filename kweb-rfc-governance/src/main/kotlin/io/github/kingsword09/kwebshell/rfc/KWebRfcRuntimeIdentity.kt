package io.github.kingsword09.kwebshell.rfc

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The pinned CEF/Chromium identity that all current evidence must be bound to. */
@Serializable
public data class KWebRfcRuntimeIdentity(
    public val cefVersion: String,
    public val chromiumVersion: String,
) {
    public companion object {
        private val format: Json = Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

        /** Loads the identity from the pinned `runtime/cef-runtime.json` manifest. */
        public fun load(path: Path): KWebRfcRuntimeIdentity {
            val identity = try {
                format.decodeFromString(serializer(), Files.readString(path))
            } catch (error: SerializationException) {
                throw KWebRfcGovernanceException(
                    code = "rfc.runtime.invalid",
                    message = "The pinned CEF runtime manifest does not declare a usable identity.",
                    cause = error,
                )
            } catch (error: IllegalArgumentException) {
                throw KWebRfcGovernanceException(
                    code = "rfc.runtime.invalid",
                    message = "The pinned CEF runtime manifest does not declare a usable identity.",
                    cause = error,
                )
            }
            if (identity.cefVersion.isBlank() || identity.chromiumVersion.isBlank()) {
                throw KWebRfcGovernanceException(
                    code = "rfc.runtime.invalid",
                    message = "The pinned CEF runtime manifest must declare both CEF and Chromium versions.",
                )
            }
            return identity
        }
    }
}
