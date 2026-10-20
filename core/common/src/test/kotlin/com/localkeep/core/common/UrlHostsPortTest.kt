package com.localkeep.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [UrlHosts.explicitPort] is how the duplicate detector tells two services on one box apart.
 * [UrlHosts.normalise] has to keep dropping the port: it is the shared identity the import merger
 * and the CSV titler also use, and a port in an untitled row's title would be new text on screen.
 */
class UrlHostsPortTest {

    @Test
    fun `an explicit port is read with or without a scheme or a path`() {
        assertEquals(8080, UrlHosts.explicitPort("https://192.168.1.10:8080"))
        assertEquals(9000, UrlHosts.explicitPort("192.168.1.10:9000/login?next=/"))
        assertEquals(3000, UrlHosts.explicitPort("localhost:3000"))
    }

    @Test
    fun `no port, a blank url and the ordinary web ports are all null`() {
        assertNull(UrlHosts.explicitPort("https://example.com/login"))
        assertNull(UrlHosts.explicitPort("example.com"))
        assertNull(UrlHosts.explicitPort("   "))
        assertNull(UrlHosts.explicitPort("https://example.com:443"))
        assertNull(UrlHosts.explicitPort("http://example.com:80/x"))
    }

    @Test
    fun `userinfo is not mistaken for a port`() {
        assertEquals(8443, UrlHosts.explicitPort("https://ada:secret@nas.local:8443/app"))
        assertNull(UrlHosts.explicitPort("https://ada:secret@nas.local/app"))
    }

    // java.net.URI returns a null host and a -1 port for these, so the port has to come from the
    // hand-parsed authority - the same route normalise() takes for the host.
    @Test
    fun `a host java_net_URI cannot parse still yields its port`() {
        assertEquals(8443, UrlHosts.explicitPort("https://my_host.example.com:8443/app"))
        assertNull(UrlHosts.explicitPort("https://my_host.example.com/app"))
    }

    @Test
    fun `a bracketed IPv6 literal keeps its colons and still yields its port`() {
        assertEquals(3000, UrlHosts.explicitPort("https://[::1]:3000/"))
        assertNull(UrlHosts.explicitPort("https://[::1]/"))
    }

    @Test
    fun `normalise still drops the port`() {
        assertEquals("192.168.1.10", UrlHosts.normalise("https://192.168.1.10:8080"))
        assertEquals("my_host.example.com", UrlHosts.normalise("https://my_host.example.com:8443/app"))
    }
}
