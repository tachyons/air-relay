package `in`.aboobacker.airrelay.protocol

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `hotspot open wire format matches spec`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), Frame(FrameType.HOTSPOT_OPEN, ByteArray(0)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x61), out.toByteArray())
    }

    @Test
    fun `device status decodes shared vector with connectivity`() {
        val json = """{"battery":87,"charging":false,"wifiSsid":"Home","networkType":"cellular","signalLevel":3}"""
        val status = ProtocolJson.decodeFromString<DeviceStatus>(json)
        assertEquals("cellular", status.networkType)
        assertEquals(3, status.signalLevel)
    }

    @Test
    fun `device status decodes without connectivity for backward compatibility`() {
        val json = """{"battery":87,"charging":false}"""
        val status = ProtocolJson.decodeFromString<DeviceStatus>(json)
        assertEquals(null, status.networkType)
        assertEquals(null, status.signalLevel)
    }

    @Test
    fun `focus state wire format matches spec`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), Frame(FrameType.FOCUS_STATE, """{"dnd":true}""".encodeToByteArray()))
        assertEquals(0x62, out.toByteArray()[4].toInt() and 0xFF)
        val decoded = ProtocolJson.decodeFromString<FocusState>(FrameCodec.read(DataInputStream(ByteArrayInputStream(out.toByteArray()))).payload.decodeToString())
        assertEquals(true, decoded.dnd)
    }

    @Test
    fun `ringer action wire format matches spec`() {
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), Frame(FrameType.RINGER_ACTION, """{"mode":"silent"}""".encodeToByteArray()))
        assertEquals(0x63, out.toByteArray()[4].toInt() and 0xFF)
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
    fun `call state decodes shared vector with photo`() {
        val json = """{"callId":"uuid","state":"ringing","displayName":"Alice","number":"+1555","photoPng":"aWNvbg=="}"""
        val call = ProtocolJson.decodeFromString<CallState>(json)
        assertEquals("aWNvbg==", call.photoPng)
        assertEquals("Alice", call.displayName)
    }

    @Test
    fun `call state decodes without photo for backward compatibility`() {
        val json = """{"callId":"uuid","state":"active"}"""
        val call = ProtocolJson.decodeFromString<CallState>(json)
        assertEquals(null, call.photoPng)
    }

    @Test
    fun `open url wire format matches spec`() {
        val payload = """{"url":"https://example.com/article"}"""
        val out = ByteArrayOutputStream()
        FrameCodec.write(DataOutputStream(out), Frame(FrameType.OPEN_URL, payload.encodeToByteArray()))
        val bytes = out.toByteArray()
        assertEquals(0x21, bytes[4].toInt())
        val decoded = FrameCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(FrameType.OPEN_URL, decoded.type)
        val url = ProtocolJson.decodeFromString<OpenUrl>(decoded.payload.decodeToString())
        assertEquals("https://example.com/article", url.url)
    }

    @Test
    fun `hello pairing flag is optional`() {
        // Older builds don't send `pairing`; it must decode as "not pairing".
        val legacy = ProtocolJson.decodeFromString<Hello>(
            """{"protocolVersion":1,"deviceName":"Pixel","platform":"android","appVersion":"0.1.0"}""",
        )
        assertEquals(false, legacy.pairing)
        val requesting = ProtocolJson.decodeFromString<Hello>(
            """{"protocolVersion":1,"deviceName":"Pixel","platform":"android","appVersion":"0.1.0","pairing":true}""",
        )
        assertEquals(true, requesting.pairing)
    }

    @Test
    fun `skips unknown frame types and keeps reading`() {
        // A newer peer may send types we don't know yet; the length prefix
        // keeps us in sync, so skip it instead of dropping the connection.
        val out = ByteArrayOutputStream()
        val writer = DataOutputStream(out)
        writer.writeInt(2)
        writer.writeByte(0xFF)
        writer.writeByte(0x7F)
        FrameCodec.write(writer, Frame(FrameType.PING, ByteArray(0)))
        val decoded = FrameCodec.read(DataInputStream(ByteArrayInputStream(out.toByteArray())))
        assertEquals(FrameType.PING, decoded.type)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `file chunk parses header and data`() {
        val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
        val payload = FileTransferChunkTestFixtures.chunk(id, 262_144, byteArrayOf(1, 2, 3))
        val chunk = FileChunk.parse(payload)!!
        assertEquals(id, chunk.transferId)
        assertEquals(262_144L, chunk.offset)
        assertArrayEquals(byteArrayOf(1, 2, 3), chunk.data)
    }

    @Test
    fun `file chunk rejects short payload`() {
        assertNull(FileChunk.parse(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `file chunk rejects negative offset`() {
        val payload = FileTransferChunkTestFixtures.chunk(
            "0f8fad5b-d9cb-469f-a165-70867728950e",
            -1,
            byteArrayOf(9),
        )
        assertNull(FileChunk.parse(payload))
    }

    @Test
    fun `rejects oversized frame`() {
        val bytes = byteArrayOf(0x7F, 0, 0, 0, 0x01)
        assertThrows(IOException::class.java) {
            FrameCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
        }
    }
}

/** Builds FILE_CHUNK payloads the way the Mac side does. */
private object FileTransferChunkTestFixtures {
    fun chunk(id: String, offset: Long, data: ByteArray): ByteArray {
        val uuid = java.util.UUID.fromString(id)
        return java.nio.ByteBuffer.allocate(24 + data.size)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .putLong(offset)
            .put(data)
            .array()
    }
}
