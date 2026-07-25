package com.webivation.grafit.ring

import java.util.UUID

/**
 * BLE protocol constants and packet parser for the Colmi R02 fitness ring.
 *
 * The R02 uses a proprietary UART-style BLE transport:
 *  - One writable characteristic to send command frames.
 *  - One notify characteristic to receive response / event frames.
 *
 * All multi-byte integers are little-endian unless noted otherwise.
 *
 * Frame layout (8 bytes fixed):
 *  [0]    command byte
 *  [1]    sub-command / data type
 *  [2..6] payload
 *  [7]    checksum (XOR of bytes 0..6)
 *
 * References: reverse-engineering notes from the open-source Gadgetbridge
 * project and community contributions for "Colmi R02".
 */
object R02Protocol {

    // -----------------------------------------------------------------------
    // BLE service / characteristic UUIDs
    // -----------------------------------------------------------------------

    /** Primary custom service exposed by the R02. */
    val SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Write-without-response characteristic: phone → ring (commands). */
    val CHAR_WRITE_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Notify characteristic: ring → phone (responses / events). */
    val CHAR_NOTIFY_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Standard BLE descriptor UUID to enable notifications. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // -----------------------------------------------------------------------
    // Command bytes
    // -----------------------------------------------------------------------

    const val CMD_REAL_TIME_HEART_RATE: Byte = 0x30
    const val CMD_REAL_TIME_SPO2: Byte       = 0x31
    const val CMD_STEPS_TODAY: Byte          = 0x43
    const val CMD_BATTERY: Byte              = 0x03
    const val CMD_DEVICE_INFO: Byte          = 0x10

    // -----------------------------------------------------------------------
    // Command frame builders
    // -----------------------------------------------------------------------

    /** Builds a command frame with checksum. */
    fun buildCommand(cmd: Byte, subCmd: Byte = 0x01, payload: ByteArray = ByteArray(5)): ByteArray {
        require(payload.size <= 5) { "Payload must be ≤ 5 bytes" }
        val frame = ByteArray(8)
        frame[0] = cmd
        frame[1] = subCmd
        payload.copyInto(frame, destinationOffset = 2, endIndex = minOf(payload.size, 5))
        frame[7] = frame.take(7).fold(0) { acc, b -> acc xor b.toInt() }.toByte()
        return frame
    }

    val CMD_GET_HEART_RATE: ByteArray get() = buildCommand(CMD_REAL_TIME_HEART_RATE)
    val CMD_GET_SPO2: ByteArray       get() = buildCommand(CMD_REAL_TIME_SPO2)
    val CMD_GET_STEPS: ByteArray      get() = buildCommand(CMD_STEPS_TODAY)
    val CMD_GET_BATTERY: ByteArray    get() = buildCommand(CMD_BATTERY)

    // -----------------------------------------------------------------------
    // Response parser
    // -----------------------------------------------------------------------

    /**
     * Attempts to parse a notification frame received from the ring and
     * returns the corresponding [RingMetric].
     *
     * Unrecognised or malformed frames return a [RingMetric] with all fields
     * set to [RingMetric.UNAVAILABLE].
     */
    fun parse(data: ByteArray): RingMetric {
        if (data.size < 8) return RingMetric()

        // Verify checksum
        val computed = data.take(7).fold(0) { acc, b -> acc xor b.toInt() }.toByte()
        if (computed != data[7]) return RingMetric()  // checksum mismatch

        return when (data[0]) {
            CMD_REAL_TIME_HEART_RATE -> RingMetric(
                heartRateBpm = data[2].toInt() and 0xFF
            )
            CMD_REAL_TIME_SPO2 -> RingMetric(
                spO2Percent = data[2].toInt() and 0xFF
            )
            CMD_STEPS_TODAY -> {
                // Little-endian 32-bit: data[1]=LSB … data[4]=MSB
                val steps = (data[1].toInt() and 0xFF) or
                        ((data[2].toInt() and 0xFF) shl 8) or
                        ((data[3].toInt() and 0xFF) shl 16) or
                        ((data[4].toInt() and 0xFF) shl 24)
                RingMetric(steps = steps)
            }
            CMD_BATTERY -> RingMetric(
                batteryPercent = data[1].toInt() and 0xFF
            )
            else -> RingMetric()
        }
    }
}
