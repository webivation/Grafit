package com.webivation.grafit.network

import org.iq80.snappy.Snappy
import java.io.ByteArrayOutputStream

// ---------------------------------------------------------------------------
// Lightweight domain types
// ---------------------------------------------------------------------------

data class PrometheusLabel(val name: String, val value: String)

data class PrometheusSample(
    /** Metric value. */
    val value: Double,
    /** Unix epoch **milliseconds** (Prometheus remote-write spec). */
    val timestampMs: Long
)

data class PrometheusTimeSeries(
    val labels: List<PrometheusLabel>,
    val samples: List<PrometheusSample>
)

// ---------------------------------------------------------------------------
// Encoder
// ---------------------------------------------------------------------------

/**
 * Encodes a list of [PrometheusTimeSeries] into a Prometheus Remote-Write
 * payload: manual protobuf serialisation followed by Snappy framing.
 *
 * Wire format matches the WriteRequest proto used by Prometheus and accepted by
 * Grafana Cloud's `/api/prom/push` endpoint.
 *
 * Protobuf schema (prometheus/prometheus, prompb/types.proto):
 * ```
 * message WriteRequest  { repeated TimeSeries timeseries = 1; }
 * message TimeSeries    { repeated Label labels = 1; repeated Sample samples = 2; }
 * message Label         { string name = 1; string value = 2; }
 * message Sample        { double value = 1; int64 timestamp = 2; }
 * ```
 */
object PrometheusEncoder {

    /**
     * Returns a Snappy-compressed protobuf WriteRequest ready to POST.
     */
    fun encode(timeSeries: List<PrometheusTimeSeries>): ByteArray {
        val proto = encodeWriteRequest(timeSeries)
        return Snappy.compress(proto)
    }

    // -----------------------------------------------------------------------
    // Protobuf encoding helpers
    // -----------------------------------------------------------------------

    internal fun encodeWriteRequest(timeSeries: List<PrometheusTimeSeries>): ByteArray {
        val out = ByteArrayOutputStream()
        for (ts in timeSeries) {
            val tsBytes = encodeTimeSeries(ts)
            writeTag(out, fieldNumber = 1, wireType = WIRE_LEN)
            writeVarint(out, tsBytes.size.toLong())
            out.write(tsBytes)
        }
        return out.toByteArray()
    }

    private fun encodeTimeSeries(ts: PrometheusTimeSeries): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in ts.labels) {
            val lb = encodeLabel(label)
            writeTag(out, 1, WIRE_LEN)
            writeVarint(out, lb.size.toLong())
            out.write(lb)
        }
        for (sample in ts.samples) {
            val sb = encodeSample(sample)
            writeTag(out, 2, WIRE_LEN)
            writeVarint(out, sb.size.toLong())
            out.write(sb)
        }
        return out.toByteArray()
    }

    private fun encodeLabel(label: PrometheusLabel): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, 1, label.name)
        writeString(out, 2, label.value)
        return out.toByteArray()
    }

    private fun encodeSample(sample: PrometheusSample): ByteArray {
        val out = ByteArrayOutputStream()
        // field 1: value (double) → wire type 1 (64-bit fixed)
        writeTag(out, 1, WIRE_64BIT)
        val bits = java.lang.Double.doubleToRawLongBits(sample.value)
        writeLittleEndian64(out, bits)
        // field 2: timestamp (int64) → wire type 0 (varint)
        writeTag(out, 2, WIRE_VARINT)
        writeVarint(out, sample.timestampMs)
        return out.toByteArray()
    }

    // -----------------------------------------------------------------------
    // Primitive protobuf writers
    // -----------------------------------------------------------------------

    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LEN = 2

    private fun writeTag(out: ByteArrayOutputStream, fieldNumber: Int, wireType: Int) =
        writeVarint(out, ((fieldNumber shl 3) or wireType).toLong())

    private fun writeString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeTag(out, fieldNumber, WIRE_LEN)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    internal fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and 0x7F.inv().toLong() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun writeLittleEndian64(out: ByteArrayOutputStream, value: Long) {
        for (i in 0..7) out.write(((value ushr (i * 8)) and 0xFF).toInt())
    }
}
