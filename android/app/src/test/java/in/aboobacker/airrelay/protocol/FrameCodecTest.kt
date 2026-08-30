package `in`.aboobacker.airrelay.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class FrameCodecTest {

    private fun roundTrip(frame: Frame): Frame {
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), frame)
        return FrameCodec.read(DataInputStream(ByteArrayInputStream(out.toByteArray())))
    }

    @Test
    fun `round trips a payload frame`() {
        val frame = Frame(FrameType.CLIPBOARD_TEXT, "hello".encodeToByteArray())
        val decoded = roundTrip(frame)
        assertEquals(FrameType.CLIPBOARD_TEXT, decoded.type)
        assertArrayEquals(frame.payload, decoded.payload)
    }

    @Test
    fun `round trips an empty frame`() {
        val decoded = roundTrip(Frame(FrameType.PING, ByteArray(0)))
        assertEquals(FrameType.PING, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `wire format matches spec`() {
        // length u32 BE covering type+payload, then type byte, then payload
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), Frame(FrameType.PONG, byteArrayOf(0x42)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x03, 0x42), out.toByteArray())
    }

    @Test
    fun `find phone wire format matches spec`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), Frame(FrameType.FIND_PHONE, ByteArray(0)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x70), out.toByteArray())

        val stop = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(stop), Frame(FrameType.FIND_PHONE_STOP, ByteArray(0)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x71), stop.toByteArray())
    }

    @Test
    fun `rejects unknown type`() {
        val bytes = byteArrayOf(0, 0, 0, 1, 0xFF.toByte())
        assertThrows(IOException::class.java) {
            FrameCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }

    @Test
    fun `rejects oversized frame`() {
        val bytes = byteArrayOf(0x7F, 0, 0, 0, 0x01)
        assertThrows(IOException::class.java) {
            FrameCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }
}
