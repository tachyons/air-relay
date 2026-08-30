package `in`.aboobacker.airrelay.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

enum class FrameType(val code: Int) {
    HELLO(0x01),
    PING(0x02),
    PONG(0x03),
    NOTIFICATION(0x10),
    NOTIF_DISMISS(0x11),
    NOTIF_REPLY(0x12),
    NOTIF_ACTION(0x13),
    CLIPBOARD_TEXT(0x20),
    OPEN_URL(0x21),
    CALL_STATE(0x30),
    CALL_ACTION(0x31),
    FILE_OFFER(0x40),
    FILE_ACCEPT(0x41),
    FILE_CHUNK(0x42),
    FILE_DONE(0x43),
    CAMERA_START(0x50),
    CAMERA_STOP(0x51),
    VIDEO_CONFIG(0x52),
    VIDEO_FRAME(0x53),
    DEVICE_STATUS(0x60);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun from(code: Int): FrameType? = byCode[code]
    }
}

data class Frame(val type: FrameType, val payload: ByteArray) {
    override fun equals(other: Any?) =
        other is Frame && other.type == type && other.payload.contentEquals(payload)

    override fun hashCode() = 31 * type.hashCode() + payload.contentHashCode()
}

object FrameCodec {
    const val MAX_FRAME_SIZE = 16 * 1024 * 1024

    fun write(out: DataOutputStream, frame: Frame) {
        out.writeInt(1 + frame.payload.size)
        out.writeByte(frame.type.code)
        out.write(frame.payload)
        out.flush()
    }

    fun read(input: DataInputStream): Frame {
        val length = input.readInt()
        if (length < 1 || length > MAX_FRAME_SIZE) throw IOException("Invalid frame length: $length")
        val typeCode = input.readUnsignedByte()
        val type = FrameType.from(typeCode) ?: throw IOException("Unknown frame type: $typeCode")
        val payload = ByteArray(length - 1)
        input.readFully(payload)
        return Frame(type, payload)
    }
}
