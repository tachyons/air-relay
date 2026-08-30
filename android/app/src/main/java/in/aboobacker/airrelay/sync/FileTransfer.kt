package `in`.aboobacker.airrelay.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import `in`.aboobacker.airrelay.R
import `in`.aboobacker.airrelay.protocol.FileMeta
import `in`.aboobacker.airrelay.protocol.Frame
import `in`.aboobacker.airrelay.protocol.FrameType
import `in`.aboobacker.airrelay.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * File transfer over the sync link.
 *
 * FILE_CHUNK binary payload: transferId (16 bytes) || offset (u64 BE) || data.
 * Outbound waits for FILE_ACCEPT before streaming; inbound files land in
 * Downloads/AirRelay.
 */
class FileTransfer(private val context: Context) {

    data class Outgoing(val meta: FileMeta, val uri: Uri)
    data class Incoming(
        val meta: FileMeta,
        val stream: OutputStream,
        val uri: Uri,
        var received: Long = 0,
    )

    private val pendingOutgoing = mutableMapOf<String, Outgoing>()
    private val incoming = mutableMapOf<String, Incoming>()

    fun offer(uri: Uri): FileMeta? {
        if (!FeaturePrefs(context).fileSharing) {
            notify("File sharing is off", "Turn it on in Air Relay settings to send files")
            return null
        }
        val resolver = context.contentResolver
        var name = "file"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        if (size < 0) return null
        val meta = FileMeta(
            transferId = UUID.randomUUID().toString(),
            name = name,
            size = size,
            mime = resolver.getType(uri) ?: "application/octet-stream",
        )
        pendingOutgoing[meta.transferId] = Outgoing(meta, uri)
        SyncService.instance?.send(
            FrameType.FILE_OFFER,
            ProtocolJson.encodeToString(meta).encodeToByteArray(),
        )
        return meta
    }

    fun onAccept(frame: Frame, scope: CoroutineScope) {
        val meta = ProtocolJson.decodeFromString<FileMeta>(frame.payload.decodeToString())
        val outgoing = pendingOutgoing.remove(meta.transferId) ?: return
        scope.launch(Dispatchers.IO) { stream(outgoing) }
    }

    private fun stream(outgoing: Outgoing) {
        val service = SyncService.instance ?: return
        val idBytes = uuidBytes(outgoing.meta.transferId)
        var offset = 0L
        runCatching {
            context.contentResolver.openInputStream(outgoing.uri)?.use { input ->
                val chunk = ByteArray(CHUNK_SIZE)
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    val payload = ByteBuffer.allocate(24 + read)
                        .put(idBytes)
                        .putLong(offset)
                        .put(chunk, 0, read)
                        .array()
                    service.sendBlocking(Frame(FrameType.FILE_CHUNK, payload))
                    offset += read
                }
            }
            service.send(
                FrameType.FILE_DONE,
                ProtocolJson.encodeToString(outgoing.meta).encodeToByteArray(),
            )
            Log.i(TAG, "Sent ${outgoing.meta.name} ($offset bytes)")
            notify("Sent to Mac", outgoing.meta.name)
        }.onFailure {
            Log.w(TAG, "Send failed: ${it.message}")
            notify("Couldn't send file", "${outgoing.meta.name} — check the connection and try again")
        }
    }

    fun onOffer(frame: Frame) {
        val meta = ProtocolJson.decodeFromString<FileMeta>(frame.payload.decodeToString())
        if (!FeaturePrefs(context).fileSharing) {
            Log.i(TAG, "File sharing off; ignoring offer for ${meta.name}")
            return
        }
        if (meta.size > freeSpaceBytes()) {
            Log.w(TAG, "Not enough space for ${meta.name} (${meta.size} bytes)")
            notify("Not enough space", "Free up storage to receive ${meta.name}")
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, meta.name)
            put(MediaStore.Downloads.MIME_TYPE, meta.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/AirRelay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val stream = uri?.let { resolver.openOutputStream(it) }
        if (uri == null || stream == null) {
            Log.w(TAG, "Could not create download entry for ${meta.name}")
            return
        }
        incoming[meta.transferId] = Incoming(meta, stream, uri)
        // Auto-accept: paired peer is trusted.
        SyncService.instance?.send(FrameType.FILE_ACCEPT, frame.payload)
        Log.i(TAG, "Receiving ${meta.name} -> $uri")
    }

    fun onChunk(frame: Frame) {
        val buffer = ByteBuffer.wrap(frame.payload)
        val id = uuidFrom(buffer)
        buffer.long // offset (sequential writes; used for sanity only)
        val transfer = incoming[id] ?: return
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        transfer.stream.write(data)
        transfer.received += data.size
    }

    fun onDone(frame: Frame) {
        val meta = ProtocolJson.decodeFromString<FileMeta>(frame.payload.decodeToString())
        incoming.remove(meta.transferId)?.let { transfer ->
            transfer.stream.close()
            context.contentResolver.update(
                transfer.uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            Log.i(TAG, "Received ${meta.name} (${transfer.received} bytes)")
            notifyReceived(transfer)
        }
    }

    /** Aborts in-flight transfers and removes partial downloads. */
    fun reset() {
        incoming.values.forEach { transfer ->
            runCatching { transfer.stream.close() }
            runCatching { context.contentResolver.delete(transfer.uri, null, null) }
            Log.i(TAG, "Discarded partial download ${transfer.meta.name}")
        }
        if (incoming.isNotEmpty()) {
            notify("Transfer interrupted", "The connection was lost — send the file again")
        }
        incoming.clear()
        pendingOutgoing.clear()
    }

    private fun freeSpaceBytes(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    private fun notifyReceived(transfer: Incoming) {
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(transfer.uri, transfer.meta.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pending = PendingIntent.getActivity(
            context,
            transfer.meta.transferId.hashCode(),
            open,
            PendingIntent.FLAG_IMMUTABLE,
        )
        notify("File received", transfer.meta.name, pending)
    }

    private fun notify(title: String, text: String, contentIntent: PendingIntent? = null) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "File transfers", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .setAutoCancel(true)
            .build()
        manager.notify(text.hashCode(), notification)
    }

    companion object {
        private const val TAG = "FileTransfer"
        private const val CHANNEL_ID = "file_transfers"
        const val CHUNK_SIZE = 256 * 1024

        fun uuidBytes(id: String): ByteArray {
            val uuid = UUID.fromString(id)
            return ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
        }

        fun uuidFrom(buffer: ByteBuffer): String =
            UUID(buffer.long, buffer.long).toString()
    }
}
