package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebCookieFilter
import io.github.kingsword09.kwebshell.core.KWebCookiePartitionKey
import io.github.kingsword09.kwebshell.core.KWebCookieSameSite
import io.github.kingsword09.kwebshell.core.KWebCookieSourceScheme
import io.github.kingsword09.kwebshell.core.KWebProfileDataKind
import io.github.kingsword09.kwebshell.core.KWebProfileDataFilter
import io.github.kingsword09.kwebshell.core.KWebProfileTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebDesktopProfileDataTest {
    @Test
    fun parsesCompleteChromiumCookieFields() {
        val cookies = KWebDesktopProfileDataJson.parseCookies(
            """
            {
              "cookies": [{
                "name": "sid",
                "value": "redacted",
                "domain": ".example.test",
                "path": "/",
                "creation": 2000.2,
                "lastAccess": 2000.4,
                "expires": 2000.5,
                "size": 12,
                "httpOnly": true,
                "secure": true,
                "session": false,
                "sameSite": "Strict",
                "priority": "High",
                "sourceScheme": "Secure",
                "sourcePort": 443,
                "partitionKey": {"topLevelSite":"https://top.example","hasCrossSiteAncestor":false},
                "partitionKeyOpaque": false
              }]
            }
            """.trimIndent(),
        )

        val cookie = cookies.single()
        assertEquals("sid", cookie.name)
        assertEquals(KWebCookieSameSite.STRICT, cookie.sameSite)
        assertEquals(KWebCookieSourceScheme.HTTPS, cookie.sourceScheme)
        assertEquals(443, cookie.sourcePort)
        assertEquals(
            KWebCookiePartitionKey("https://top.example", hasCrossSiteAncestor = false),
            cookie.partitionKey,
        )
        assertTrue(cookie.expiresEpochMillis!! > 2_000_000L)
        assertTrue(
            cookie.matches(
                KWebCookieFilter(
                    origin = "https://example.test",
                    name = "sid",
                    timeRange = KWebProfileTimeRange(
                        sinceEpochMillis = 2_000_000L,
                        untilEpochMillis = 2_001_000L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun mapsStorageUsageAndOriginStorageKinds() {
        val usage = KWebDesktopProfileDataJson.parseStorageUsage(
            """
            {
              "origin": "https://example.test",
              "usage": 42,
              "quota": 4096,
              "usageBreakdown": [
                {"storageType": "local_storage", "usage": 12},
                {"storageType": "indexeddb", "usage": 30}
              ]
            }
            """.trimIndent(),
            "https://example.test",
        )

        assertEquals(42, usage.usageBytes)
        assertEquals(4096, usage.quotaBytes)
        assertEquals(listOf("local_storage", "indexeddb"), usage.breakdown.map { it.storageType })
        assertEquals(
            "local_storage,indexeddb,cache_storage",
            KWebDesktopProfileDataJson.storageTypes(
                setOf(
                    KWebProfileDataKind.LOCAL_STORAGE,
                    KWebProfileDataKind.INDEXED_DB,
                    KWebProfileDataKind.CACHE_STORAGE,
                ),
            ),
        )
        val filter = KWebProfileDataFilter(
            kinds = setOf(KWebProfileDataKind.LOCAL_STORAGE),
            origin = "https://example.test",
        )
        assertEquals("https://example.test", filter.origin)
    }

    @Test
    fun fillsStorageUsageOriginFromTheValidatedRequestWhenChromiumOmitsIt() {
        val usage = KWebDesktopProfileDataJson.parseStorageUsage(
            """{"usage":42,"quota":4096,"usageBreakdown":[]}""",
            "https://example.test",
        )

        assertEquals("https://example.test", usage.origin)
        assertEquals(42, usage.usageBytes)
    }

    @Test
    fun rejectsInvalidStorageUsageAndOversizedCookieListings() {
        assertFailsWith<IllegalArgumentException> {
            KWebDesktopProfileDataJson.parseStorageUsage(
                """{"origin":"https://example.test","usage":8,"quota":7}""",
                "https://example.test",
            )
        }
        val cookie = """{"name":"n","value":"v","domain":"example.test","path":"/"}"""
        val oversized = (0 until 4097).joinToString(",") { cookie }
        assertFailsWith<IllegalArgumentException> {
            KWebDesktopProfileDataJson.parseCookies("""{"cookies":[$oversized]}""")
        }
    }

    @Test
    fun cookieTimeRangeUsesCreationOrLastAccessActivity() {
        val cookies = KWebDesktopProfileDataJson.parseCookies(
            """
            {
              "cookies": [{
                "name": "sid",
                "value": "redacted",
                "domain": "example.test",
                "path": "/",
                "creation": 10.0,
                "lastAccess": 20.0,
                "expires": 1.0
              }]
            }
            """.trimIndent(),
        )
        assertTrue(
            cookies.single().matches(
                KWebCookieFilter(timeRange = KWebProfileTimeRange(15_000, 25_000)),
            ),
        )
    }
}
