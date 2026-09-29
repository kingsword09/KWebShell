package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebSecurityContractTest {
    @Test
    fun certificatesExposeOnlyBoundedPublicMetadata() {
        val certificate = KWebCertificateSummary(
            sha256Fingerprint = "a".repeat(64),
            subject = "subject",
            issuer = "issuer",
            serialNumber = "01:02",
            validStartEpochMillis = 1_000L,
            validExpiryEpochMillis = 2_000L,
        )

        assertEquals("a".repeat(64), certificate.sha256Fingerprint)
        assertFailsWith<KWebConfigurationException> {
            certificate.copy(sha256Fingerprint = "A".repeat(64))
        }
        assertFailsWith<KWebConfigurationException> {
            certificate.copy(subject = "x".repeat(513))
        }
    }

    @Test
    fun challengeBoundariesRejectMalformedValues() {
        val certificate = KWebCertificateSummary(
            sha256Fingerprint = "b".repeat(64),
            subject = "subject",
            issuer = "issuer",
            serialNumber = "serial",
            validStartEpochMillis = null,
            validExpiryEpochMillis = null,
        )
        val challenge = KWebSecurityChallenge.ClientCertificate(
            requestId = 2L,
            profileId = "profile",
            pageId = "page",
            origin = "https://example.test",
            deadlineEpochMillis = 2_000L,
            host = "example.test",
            port = 443,
            isProxy = false,
            certificates = listOf(certificate),
        )
        assertEquals(1, challenge.certificates.size)
    }
}
