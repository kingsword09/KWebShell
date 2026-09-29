package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebSecurityChallenge
import io.github.kingsword09.kwebshell.core.KWebSecurityDecision
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebDesktopSecurityTest {
    @Test
    fun parsesTlsSummaryWithoutPrivateMaterial() {
        val event = event(
            type = NativeBrowserEventType.SECURITY_CHALLENGE,
            requestId = 41,
            origin = "https://localhost:8443",
            url = "https://localhost:8443/",
            details = """
                {
                  "version":1,"kind":"tls","requestId":"41",
                  "requestUrl":"https://localhost:8443/",
                  "origin":"https://localhost:8443","error":"authorityInvalid",
                  "deadlineEpochMillis":41000,
                  "certificate":{
                    "sha256Fingerprint":"${"a".repeat(64)}",
                    "subject":"localhost","issuer":"test-ca","serialNumber":"01",
                    "validStartEpochMillis":null,"validExpiryEpochMillis":null
                  }
                }
            """.trimIndent(),
        )

        val challenge = KWebDesktopSecurityJson.parseChallenge(event, "profile", "page")
        val tls = challenge as KWebSecurityChallenge.Tls
        assertEquals("localhost", tls.certificate.subject)
        assertTrue(tls.toString().contains("${"a".repeat(64)}"))
        val payload = KWebDesktopSecurityJson.decisionPayload(
            tls,
            KWebSecurityDecision.Tls.ALLOW_ONCE,
        )
        assertTrue("privateKey" !in payload)
        assertTrue("der" !in payload.lowercase())
    }

    @Test
    fun rejectsUnknownNativeFieldsAndUnofferedCertificate() {
        val event = event(
            type = NativeBrowserEventType.SECURITY_CHALLENGE,
            requestId = 43,
            details = """
                {
                  "version":1,"kind":"clientCertificate","requestId":"43",
                  "origin":"https://example.test","host":"example.test","port":443,
                  "isProxy":false,"certificates":[],"deadlineEpochMillis":43000,
                  "unexpected":true
                }
            """.trimIndent(),
        )
        val error = assertFailsWith<KWebConfigurationException> {
            KWebDesktopSecurityJson.parseChallenge(event, "profile", "page")
        }
        assertEquals("security.challenge.callback-failed", error.code)

        val challenge = KWebSecurityChallenge.ClientCertificate(
            requestId = 44,
            profileId = "profile",
            pageId = "page",
            origin = "https://example.test",
            deadlineEpochMillis = 44000,
            host = "example.test",
            port = 443,
            isProxy = false,
            certificates = emptyList(),
        )
        assertFailsWith<KWebConfigurationException> {
            KWebDesktopSecurityJson.decisionPayload(
                challenge,
                KWebSecurityDecision.ClientCertificate.SELECT("b".repeat(64)),
            )
        }
    }

    private fun event(
        type: NativeBrowserEventType,
        requestId: Long,
        origin: String = "",
        url: String = "",
        details: String,
    ): NativeBrowserEvent = NativeBrowserEvent(
        type = type,
        engine = 1,
        browser = 2,
        sequence = requestId,
        flags = 0,
        text = "",
        statusCode = 0,
        width = 0,
        height = 0,
        requestId = requestId,
        origin = origin,
        url = url,
        details = details,
    )
}
