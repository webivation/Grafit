package com.webivation.grafit.network

import java.io.ByteArrayOutputStream

/**
 * Minimal Snappy block-format encoder: emits the input as "literal" chunks
 * with no back-reference (LZ77) compression. This is a fully spec-compliant
 * Snappy stream — decoders don't require back-references, only correct
 * framing — just an uncompressed one.
 *
 * Written to sidestep every general-purpose Snappy library available for
 * Android (`org.iq80.snappy`, and even the current `io.airlift.compress.v3`
 * rewrite) relying on `sun.misc.Unsafe` fields Android's ART runtime doesn't
 * provide, which crashes with `NoSuchFieldError` at the moment real data
 * first reaches the encode path. Our payloads are a handful of Prometheus
 * samples per flush — at most a few hundred bytes — so the compression ratio
 * a real LZ77 pass would add is not worth the dependency risk.
 */
internal object PureSnappy {

    fun compress(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, input.size)
        var offset = 0
        while (offset < input.size) {
            val chunkLength = minOf(MAX_LITERAL_CHUNK, input.size - offset)
            writeLiteralTag(out, chunkLength)
            out.write(input, offset, chunkLength)
            offset += chunkLength
        }
        return out.toByteArray()
    }

    /** Tag byte(s) for a literal chunk of [length] bytes (Snappy tag type 0b00). */
    private fun writeLiteralTag(out: ByteArrayOutputStream, length: Int) {
        val n = length - 1
        when {
            n < 60 -> out.write(n shl 2)
            n < 0x100 -> {
                out.write(60 shl 2)
                out.write(n and 0xFF)
            }
            else -> {
                // n < 0x10000, guaranteed by MAX_LITERAL_CHUNK
                out.write(61 shl 2)
                out.write(n and 0xFF)
                out.write((n ushr 8) and 0xFF)
            }
        }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    /** Largest chunk length representable with the 2-extra-byte literal tag. */
    private const val MAX_LITERAL_CHUNK = 0x10000
}
