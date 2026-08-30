package `in`.aboobacker.airrelay.protocol

import kotlinx.serialization.encodeToString
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
    fun `media frames wire format matches spec`() {
        val state = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(state), Frame(FrameType.MEDIA_STATE, ByteArray(0)))
        assertEquals(0x80, state.toByteArray()[4].toInt() and 0xFF)

        val action = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(action), Frame(FrameType.MEDIA_ACTION, ByteArray(0)))
        assertEquals(0x81, action.toByteArray()[4].toInt() and 0xFF)
    }

    @Test
    fun `media state decodes shared vector`() {
        val json = """{"packageName":"com.spotify.music","appName":"Spotify","title":"Song","artist":"Artist","playing":true,"artPng":"YXJ0"}"""
        val state = ProtocolJson.decodeFromString<MediaState>(json)
        assertEquals("Song", state.title)
        assertEquals(true, state.playing)
        assertEquals("YXJ0", state.artPng)
    }

    @Test
    fun `media state decodes empty session vector`() {
        val state = ProtocolJson.decodeFromString<MediaState>("""{"playing":false}""")
        assertEquals(null, state.title)
        assertEquals(false, state.playing)
    }

    @Test
    fun `media action round trips`() {
        val json = ProtocolJson.encodeToString(MediaAction("next"))
        assertEquals("next", ProtocolJson.decodeFromString<MediaAction>(json).action)
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
