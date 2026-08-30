package `in`.aboobacker.airrelay.sync

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import `in`.aboobacker.airrelay.protocol.CameraStart
import `in`.aboobacker.airrelay.protocol.FrameType
import java.nio.ByteBuffer

/**
 * Camera capture and hardware H.264 encoding pipeline.
 *
 * CameraX Preview use case -> MediaCodec input Surface -> low-latency H.264
 * -> Annex-B NALs framed as VIDEO_CONFIG (SPS/PPS) and VIDEO_FRAME messages.
 */
class CameraStreamer(
    private val context: Context,
    private val config: CameraStart,
) {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var provider: ProcessCameraProvider? = null

    fun start(lifecycleOwner: LifecycleOwner) {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height,
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            )
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo,
            ) {
                val buffer = codec.getOutputBuffer(index) ?: return
                emit(buffer, info)
                codec.releaseOutputBuffer(index, false)
            }

            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.d(TAG, "Encoder format: $format")
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Encoder error", e)
            }
        })
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        codec = encoder
        inputSurface = surface

        bindCamera(lifecycleOwner, surface)
    }

    private fun bindCamera(lifecycleOwner: LifecycleOwner, surface: Surface) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            this.provider = provider
            val preview = Preview.Builder()
                .setTargetFrameRate(android.util.Range(config.fps, config.fps))
                .build()
            preview.setSurfaceProvider { request: SurfaceRequest ->
                request.provideSurface(
                    surface,
                    ContextCompat.getMainExecutor(context),
                ) { }
            }
            val selector = if (config.lens == "front") {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview)
                Log.i(TAG, "Camera bound (${config.lens}, ${config.width}x${config.height}@${config.fps})")
            }.onFailure { Log.e(TAG, "Camera bind failed: ${it.message}") }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun emit(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            SyncService.instance?.send(FrameType.VIDEO_CONFIG, data)
            return
        }
        val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        val header = ByteBuffer.allocate(9)
            .putLong(info.presentationTimeUs)
            .put(if (isKeyframe) 1 else 0)
            .array()
        SyncService.instance?.send(FrameType.VIDEO_FRAME, header + data)
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        provider = null
        runCatching {
            codec?.stop()
            codec?.release()
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }

    companion object {
        private const val TAG = "CameraStreamer"
    }
}
