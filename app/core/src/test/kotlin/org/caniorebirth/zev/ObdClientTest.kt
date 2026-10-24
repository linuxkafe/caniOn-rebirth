package org.caniorebirth.zev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdClientTest {

    private class FakeTransport : ObdClient.Transport {
        val written = mutableListOf<String>()
        var opened = false
        var closed = false
        override fun open() { opened = true }
        override fun write(bytes: ByteArray) { written.add(String(bytes, Charsets.UTF_8)) }
        override fun close() { closed = true }
    }

    @Test
    fun `decodes frames delivered by the adapter`() {
        val transport = FakeTransport()
        val parser = ZevCanParser()
        val client = ObdClient(transport, parser)
        val frames = mutableListOf<CanFrame>()
        client.onFrame = { frames.add(it) }

        client.start()
        assertTrue(transport.opened)

        // Simulate an adapter reply: a frame line terminated by CR, then prompt.
        client.onBytes("29800000000000029CE\r>".toByteArray(Charsets.UTF_8))

        assertEquals(1, frames.size)
        assertEquals(0x298, frames[0].id)
        assertEquals(702, parser.signals().motorRpm)
    }

    @Test
    fun `reports adapter errors`() {
        val transport = FakeTransport()
        val client = ObdClient(transport)
        var reported: String? = null
        client.onAdapterError = { reported = it }

        client.start()
        client.onBytes("ERROR\r>".toByteArray(Charsets.UTF_8))

        assertEquals("ERROR", reported)
    }
}