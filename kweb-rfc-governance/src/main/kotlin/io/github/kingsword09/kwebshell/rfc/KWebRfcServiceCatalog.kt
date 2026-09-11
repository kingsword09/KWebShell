package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPaths
import io.github.kingsword09.kwebshell.service.dialogs.KWebDialogs
import io.github.kingsword09.kwebshell.service.windowcontrols.KWebWindowControls

/** The published contract version of one installed native service. */
public data class KWebRfcServiceContract(
    public val id: String,
    public val version: String,
)

/**
 * The live native-service descriptor catalog. Evidence records must bind service
 * versions that still match these descriptors; a mismatch means the underlying
 * contract changed and the evidence expired.
 */
public object KWebRfcServiceCatalog {
    public val published: List<KWebRfcServiceContract> = listOf(
        KWebRfcServiceContract(KWebAppPaths.DESCRIPTOR.id, KWebAppPaths.DESCRIPTOR.version.toString()),
        KWebRfcServiceContract(KWebWindowControls.DESCRIPTOR.id, KWebWindowControls.DESCRIPTOR.version.toString()),
        KWebRfcServiceContract(KWebDialogs.DESCRIPTOR.id, KWebDialogs.DESCRIPTOR.version.toString()),
    )

    public fun find(id: String): KWebRfcServiceContract? = published.singleOrNull { it.id == id }
}
