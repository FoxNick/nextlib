package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

@UnstableApi
class FFmpegOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        // Add FFmpeg video renderer
        out.add(
            FfmpegVideoRenderer(
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            )
        )
        Log.i(TAG, "Loaded FfmpegVideoRenderer.")

        // Try to load AV1 decoder extension renderer (Libgav1VideoRenderer)
        try {
            val clazz = Class.forName("androidx.media3.decoder.av1.Libgav1VideoRenderer")
            val constructor = clazz.getConstructor(
                Long::class.javaPrimitiveType,
                Handler::class.java,
                VideoRendererEventListener::class.java,
                Int::class.javaPrimitiveType
            )
            val renderer = constructor.newInstance(
                allowedVideoJoiningTimeMs,
                eventHandler,
                eventListener,
                MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY
            ) as Renderer
            out.add(renderer)
            Log.i(TAG, "Loaded Libgav1VideoRenderer (AV1 decoder extension).")
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "AV1 decoder extension not found, skipping.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to instantiate AV1 decoder extension: ${e.message}")
        }
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // Add FFmpeg audio renderer
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        Log.i(TAG, "Loaded FfmpegAudioRenderer.")

        // Try to load MPEG-H decoder extension renderer (MpeghAudioRenderer)
        try {
            val clazz = Class.forName("androidx.media3.decoder.mpegh.MpeghAudioRenderer")
            val constructor = clazz.getConstructor(
                Handler::class.java,
                AudioRendererEventListener::class.java,
                AudioSink::class.java
            )
            val renderer = constructor.newInstance(
                eventHandler,
                eventListener,
                audioSink
            ) as Renderer
            out.add(renderer)
            Log.i(TAG, "Loaded MpeghAudioRenderer (MPEG-H decoder extension).")
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "MPEG-H decoder extension not found, skipping.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to instantiate MPEG-H decoder extension: ${e.message}")
        }
    }

    companion object {
        const val TAG = "FFmpegOnlyRenderersFactory"
    }
}
