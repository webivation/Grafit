package com.webivation.grafit.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.iq80.snappy.Snappy
import java.io.ByteArrayOutputStream

/**
 * Unit tests for [PrometheusEncoder].
 *
 * Validates that the manual protobuf serialisation and Snappy framing produce
 * the correct binary layout expected by the Prometheus remote-write spec.
 */
class PrometheusEncoderTest {

    // -----------------------------------------------------------------------
    // Varint encoding
    // -----------------------------------------------------------------------

    @Test
    fun `writeVarint encodes zero as single zero byte`() {
        val out = ByteArrayOutputStream()
        PrometheusEncoder.writeVarint(out, 0L)
        val bytes = out.toByteArray()
        assertEquals(1, bytes.size)
        assertEquals(0.toByte(), bytes[0])
    }

    @Test
    fun `writeVarint encodes 1 as single byte 0x01`() {
        val out = ByteArrayOutputStream()
        PrometheusEncoder.writeVarint(out, 1L)
        assertEquals(byteArrayOf(0x01).toList(), out.toByteArray().toList())
    }

    @Test
    fun `writeVarint encodes 128 as two bytes`() {
        // 128 = 0x80 → varint: 0x80 0x01
        val out = ByteArrayOutputStream()
        PrometheusEncoder.writeVarint(out, 128L)
        val bytes = out.toByteArray()
        assertEquals(2, bytes.size)
        assertEquals(0x80.toByte(), bytes[0])
        assertEquals(0x01.toByte(), bytes[1])
    }

    @Test
    fun `writeVarint encodes 300 correctly`() {
        // 300 = 0x12C → varint: 0xAC 0x02
        val out = ByteArrayOutputStream()
        PrometheusEncoder.writeVarint(out, 300L)
        val bytes = out.toByteArray()
        assertEquals(2, bytes.size)
        assertEquals(0xAC.toByte(), bytes[0])
        assertEquals(0x02.toByte(), bytes[1])
    }

    // -----------------------------------------------------------------------
    // WriteRequest encoding
    // -----------------------------------------------------------------------

    @Test
    fun `encodeWriteRequest returns non-empty bytes for one time series`() {
        val ts = PrometheusTimeSeries(
            labels = listOf(
                PrometheusLabel("__name__", "test_metric"),
                PrometheusLabel("job", "grafit")
            ),
            samples = listOf(PrometheusSample(42.0, 1_700_000_000_000L))
        )
        val encoded = PrometheusEncoder.encodeWriteRequest(listOf(ts))
        assertTrue("Encoded bytes must not be empty", encoded.isNotEmpty())
    }

    @Test
    fun `encodeWriteRequest starts with field-1 LEN tag (0x0A)`() {
        val ts = PrometheusTimeSeries(
            labels = listOf(PrometheusLabel("__name__", "m")),
            samples = listOf(PrometheusSample(1.0, 1_000L))
        )
        val encoded = PrometheusEncoder.encodeWriteRequest(listOf(ts))
        // Field 1, wire type 2 (LEN) = (1 << 3) | 2 = 0x0A
        assertEquals(0x0A.toByte(), encoded[0])
    }

    @Test
    fun `encodeWriteRequest is deterministic for same input`() {
        val ts = PrometheusTimeSeries(
            labels = listOf(PrometheusLabel("__name__", "det_metric")),
            samples = listOf(PrometheusSample(99.0, 12345L))
        )
        val first  = PrometheusEncoder.encodeWriteRequest(listOf(ts))
        val second = PrometheusEncoder.encodeWriteRequest(listOf(ts))
        assertEquals(first.toList(), second.toList())
    }

    @Test
    fun `encodeWriteRequest grows with additional time series`() {
        val ts = PrometheusTimeSeries(
            labels = listOf(PrometheusLabel("__name__", "m")),
            samples = listOf(PrometheusSample(0.0, 0L))
        )
        val one  = PrometheusEncoder.encodeWriteRequest(listOf(ts))
        val two  = PrometheusEncoder.encodeWriteRequest(listOf(ts, ts))
        assertTrue("Two series must produce more bytes than one", two.size > one.size)
    }

    @Test
    fun `encodeWriteRequest for empty list returns empty bytes`() {
        val encoded = PrometheusEncoder.encodeWriteRequest(emptyList())
        assertEquals(0, encoded.size)
    }

    // -----------------------------------------------------------------------
    // Full encode (protobuf + snappy)
    // -----------------------------------------------------------------------

    @Test
    fun `encode produces snappy-decompressible bytes`() {
        val ts = PrometheusTimeSeries(
            labels = listOf(PrometheusLabel("__name__", "heart_rate_bpm")),
            samples = listOf(PrometheusSample(72.0, System.currentTimeMillis()))
        )
        val compressed = PrometheusEncoder.encode(listOf(ts))
        val decompressed = Snappy.uncompress(compressed, 0, compressed.size)
        assertNotEquals(0, decompressed.size)
    }

    @Test
    fun `encode round-trips through snappy back to original protobuf`() {
        val ts = PrometheusTimeSeries(
            labels = listOf(
                PrometheusLabel("__name__", "spo2_percent"),
                PrometheusLabel("device", "R02")
            ),
            samples = listOf(PrometheusSample(98.0, 1_700_000_000_000L))
        )
        val proto      = PrometheusEncoder.encodeWriteRequest(listOf(ts))
        val compressed = PrometheusEncoder.encode(listOf(ts))
        val restored   = Snappy.uncompress(compressed, 0, compressed.size)
        assertEquals(proto.toList(), restored.toList())
    }

    // -----------------------------------------------------------------------
    // Double encoding correctness (IEEE 754 little-endian)
    // -----------------------------------------------------------------------

    @Test
    fun `double value 1_0 encodes to well-known IEEE 754 bytes`() {
        // 1.0 in IEEE 754 double = 3FF0000000000000 little-endian
        val ts = PrometheusTimeSeries(
            labels = listOf(PrometheusLabel("__name__", "x")),
            samples = listOf(PrometheusSample(1.0, 0L))
        )
        val proto = PrometheusEncoder.encodeWriteRequest(listOf(ts))
        // Find the 8-byte double: after LEN-delimited TimeSeries > Sample bytes
        // We locate the 64-bit fixed field by searching for known bytes
        val protoList = proto.toList()
        // 1.0 little-endian: 00 00 00 00 00 00 F0 3F
        val expected = listOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0x3F)
            .map { it.toByte() }
        assertTrue(
            "1.0 IEEE 754 bytes should appear in encoded output",
            Collections.indexOfSubList(protoList, expected) >= 0
        )
    }
}

// Polyfill for Collections.indexOfSubList on Kotlin lists
private object Collections {
    fun indexOfSubList(source: List<Byte>, target: List<Byte>): Int {
        if (target.isEmpty()) return 0
        outer@ for (i in 0..source.size - target.size) {
            for (j in target.indices) {
                if (source[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
