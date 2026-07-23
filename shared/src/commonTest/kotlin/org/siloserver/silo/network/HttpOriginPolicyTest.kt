package org.siloserver.silo.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpOriginPolicyTest {
    @Test
    fun defaultPortsAndCaseNormalize() {
        assertTrue(isSameHttpOrigin("HTTPS://Silo.Example", "https://silo.example:443/a"))
        assertTrue(isSameHttpOrigin("http://silo.example", "http://SILO.EXAMPLE:80/a"))
        assertEquals(
            HttpOrigin(scheme = "https", host = "silo.example", port = 443),
            httpOrigin("HTTPS://Silo.Example"),
        )
    }

    @Test
    fun pathQueryAndFragmentDoNotAffectOrigin() {
        assertTrue(
            isSameHttpOrigin(
                "https://silo.example/base?server=true#settings",
                "https://SILO.EXAMPLE/library/items?offset=10#details",
            ),
        )
    }

    @Test
    fun matchingNonDefaultPortsArePreserved() {
        assertEquals(
            HttpOrigin(scheme = "https", host = "silo.example", port = 8443),
            httpOrigin("https://silo.example:8443/a"),
        )
        assertTrue(
            isSameHttpOrigin(
                "https://silo.example:8443",
                "https://SILO.EXAMPLE:8443/a",
            ),
        )
    }

    @Test
    fun schemeHostPortAndUserInfoMismatchesFailClosed() {
        assertFalse(isSameHttpOrigin("https://silo.example", "http://silo.example/a"))
        assertFalse(isSameHttpOrigin("https://silo.example", "https://cdn.silo.example/a"))
        assertFalse(isSameHttpOrigin("https://silo.example", "https://silo.example:444/a"))
        assertNull(httpOrigin("https://user@silo.example/a"))
        assertNull(httpOrigin("file:///tmp/a"))
    }

    @Test
    fun everyUserInfoFormIsRejected() {
        assertNull(httpOrigin("https://user:password@silo.example/a"))
        assertNull(httpOrigin("https://:password@silo.example/a"))
        assertNull(httpOrigin("https://@silo.example/a"))
        assertNull(httpOrigin("https://user:@silo.example/a"))
    }

    @Test
    fun relativeAndMissingAuthorityUrlsAreRejected() {
        listOf(
            "",
            "   ",
            "/relative/path",
            "silo.example/path",
            "https://",
            "https:///path",
            "https://?query",
            "https://#fragment",
            "https://:443/path",
        ).forEach { raw ->
            assertNull(httpOrigin(raw), "Expected a missing authority to be rejected: '$raw'")
        }
    }

    @Test
    fun malformedHostsAndPortsAreRejected() {
        assertNull(httpOrigin("https://silo example/path"))
        assertNull(httpOrigin("https://silo.example:65536/path"))
        assertNull(httpOrigin("https://silo.example:-1/path"))
    }

    @Test
    fun invalidOriginsNeverCompareEqual() {
        assertFalse(isSameHttpOrigin("", ""))
        assertFalse(isSameHttpOrigin("/server", "/request"))
        assertFalse(
            isSameHttpOrigin(
                "https://user@silo.example",
                "https://user@silo.example/path",
            ),
        )
    }
}
