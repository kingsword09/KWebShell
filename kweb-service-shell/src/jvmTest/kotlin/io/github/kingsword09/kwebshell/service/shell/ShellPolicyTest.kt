package io.github.kingsword09.kwebshell.service.shell

import io.github.kingsword09.kwebshell.core.KWebNativeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellPolicyTest {
    @Test
    fun rejectsEncodedControlsSeparatorsAndAddresslessMailto() {
        val encodedControl = assertFailsWith<KWebNativeException> {
            ShellPolicy.normalizeExternalUri(
                KWebShellExternalUriRequest("https://example.invalid/%0A"),
                KWebShellConfiguration(),
            )
        }
        assertEquals(KWebShellErrorCode.URI_INVALID, encodedControl.code)

        val encodedSeparator = assertFailsWith<KWebNativeException> {
            ShellPolicy.normalizeExternalUri(
                KWebShellExternalUriRequest("https://example.invalid/%5Csecret"),
                KWebShellConfiguration(),
            )
        }
        assertEquals(KWebShellErrorCode.URI_INVALID, encodedSeparator.code)

        val addresslessMailto = assertFailsWith<KWebNativeException> {
            ShellPolicy.normalizeExternalUri(
                KWebShellExternalUriRequest("mailto:?subject=missing-address"),
                KWebShellConfiguration(),
            )
        }
        assertEquals(KWebShellErrorCode.URI_INVALID, addresslessMailto.code)
    }

    @Test
    fun preservesAllowedUnicodeAndRejectsNestedDangerousSchemes() {
        val allowed = ShellPolicy.normalizeExternalUri(
            KWebShellExternalUriRequest("https://example.invalid/%E2%9C%93?q=hello%20world"),
            KWebShellConfiguration(),
        )
        assertEquals("https://example.invalid/%E2%9C%93?q=hello%20world", allowed)

        val nested = assertFailsWith<KWebNativeException> {
            ShellPolicy.normalizeExternalUri(
                KWebShellExternalUriRequest("https://example.invalid/%6a%61%76%61%73%63%72%69%70%74:alert(1)"),
                KWebShellConfiguration(),
            )
        }
        assertEquals(KWebShellErrorCode.SCHEME_DENIED, nested.code)
    }
}
