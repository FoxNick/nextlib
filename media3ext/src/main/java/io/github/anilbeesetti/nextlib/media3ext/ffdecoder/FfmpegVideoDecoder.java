package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import android.util.Log;
import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Ffmpeg Video decoder.
 */
@UnstableApi
final class FfmpegVideoDecoder extends
        SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

    private static final String TAG = "FfmpegVideoDecoder";

    // LINT.IfChange
    private static final int VIDEO_DECODER_SUCCESS = 0;
    private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
    private static final int VIDEO_DECODER_ERROR_OTHER = -2;
    private static final int VIDEO_DECODER_ERROR_READ_FRAME = -3;
    // LINT.ThenChange(../../../../../../../jni/ffmpeg_jni.cc)

    private final String codecName;
    private long nativeContext;
    @Nullable
    private final byte[] extraData;
    private Format format;

    @C.VideoOutputMode
    private volatile int outputMode;

    /**
     * Creates a Ffmpeg video Decoder.
     *
     * @param numInputBuffers        Number of input buffers.
     * @param numOutputBuffers       Number of output buffers.
     * @param initialInputBufferSize The initial size of each input buffer, in bytes.
     * @param threads                Number of threads libgav1 will use to decode.
     * @throws FfmpegDecoderException Thrown if an exception occurs when initializing the
     *                                decoder.
     */
    public FfmpegVideoDecoder(int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads, Format format) throws FfmpegDecoderException {
        super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);

        if (!FfmpegLibrary.isAvailable()) {
            throw new FfmpegDecoderException("Failed to load decoder native library.");
        }
        assert format.sampleMimeType != null;
        codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format));
        extraData = getExtraData(format.sampleMimeType, format.initializationData);
        this.format = format;
        nativeContext = ffmpegInitialize(codecName, extraData, threads);
        if (nativeContext == 0) {
            throw new FfmpegDecoderException("Failed to initialize decoder.");
        }
        setInitialInputBufferSize(initialInputBufferSize);
    }

    /**
     * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
     * not required.
     */
    @Nullable
    private byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
    if (initializationData.isEmpty()) return null;

    // 特定格式的特殊处理
    switch (mimeType) {
        case MimeTypes.VIDEO_DOLBY_VISION -> {
            // Dolby Vision: combine first two CSD entries (if present)
            if (initializationData.size() >= 2 && initializationData.get(0).length > 0 && initializationData.get(1).length > 0) {
                byte[] first = initializationData.get(0);
                byte[] second = initializationData.get(1);
                byte[] combined = new byte[first.length + second.length];
                System.arraycopy(first, 0, combined, 0, first.length);
                System.arraycopy(second, 0, combined, first.length, second.length);
                return combined;
            } else if (initializationData.get(0).length > 0) {
                return initializationData.get(0);
            }
            return null;
        }
        
        // 【修改】替换废弃的 VIDEO_VC1，并加入 AV1
        case MimeTypes.VIDEO_H265, "video/wvc1", "video/x-ms-wvc1",
             MimeTypes.VIDEO_AV1, // AV1 也只需取第一段，或者拼接起来都可以。通常取第一段 Sequence Header OBU 即可
             "video/x-rv10", "video/x-rv20", "video/x-rv30", "video/x-rv40" -> {
            // For these codecs, only the first CSD is needed
            return initializationData.get(0);
        }
        
        case MimeTypes.VIDEO_H264 -> {
            // H.264: may have two CSDs (SPS and PPS) that need to be concatenated
            if (initializationData.size() >= 2) {
                // 【优化】直接复用合并方法，代码更简洁
                return mergeInitializationData(initializationData.subList(0, 2));
            } else {
                return initializationData.get(0);
            }
        }
    }

    // Generic codecs: concatenate all CSD entries
    return mergeInitializationData(initializationData);
}

// 合并 initializationData 的工具方法
private byte[] mergeInitializationData(List<byte[]> initializationData) {
    if (initializationData == null || initializationData.isEmpty()) {
        return null;
    }

    int totalSize = 0;
    for (byte[] csd : initializationData) {
        totalSize += csd.length;
    }

    if (totalSize == 0) {
        return null;
    }

    byte[] merged = new byte[totalSize];
    int currentOffset = 0;
    for (byte[] csd : initializationData) {
        // 使用 System.arraycopy 替代 ByteBuffer，性能更优
        System.arraycopy(csd, 0, merged, currentOffset, csd.length);
        currentOffset += csd.length;
    }

    return merged;
}

    @Override
    public String getName() {
        return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
    }

    /**
     * Sets the output mode for frames rendered by the decoder.
     *
     * @param outputMode The output mode.
     */
    public void setOutputMode(@C.VideoOutputMode int outputMode) {
        this.outputMode = outputMode;
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected VideoDecoderOutputBuffer createOutputBuffer() {
        return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
        return new FfmpegDecoderException("Unexpected decode error", error);
    }

    @Nullable
    @Override
    protected FfmpegDecoderException decode(DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
        if (reset) {
            nativeContext = ffmpegReset(nativeContext);
            if (nativeContext == 0) {
                return new FfmpegDecoderException("Error resetting (see logcat).");
            }
        }

        // send packet
        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        int inputSize = inputData.limit();
        // enqueue origin data
        int sendPacketResult = ffmpegSendPacket(nativeContext, inputData, inputSize, inputBuffer.timeUs);
        if (sendPacketResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
            outputBuffer.shouldBeSkipped = true;
            return null;
        } else if (sendPacketResult == VIDEO_DECODER_ERROR_READ_FRAME) {
            // need read frame
            Log.d(TAG, "VIDEO_DECODER_ERROR_READ_FRAME: " + "timeUs=" + inputBuffer.timeUs);
        } else if (sendPacketResult == VIDEO_DECODER_ERROR_OTHER) {
            return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
        }

        // receive frame
        boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
        // We need to dequeue the decoded frame from the decoder even when the input data is
        // decode-only.
        int getFrameResult = ffmpegReceiveFrame(nativeContext, outputMode, outputBuffer, decodeOnly);
        if (getFrameResult == VIDEO_DECODER_ERROR_OTHER) {
            return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
        }

        if (getFrameResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
            outputBuffer.shouldBeSkipped = true;
        }

        if (!decodeOnly) {
            outputBuffer.format = inputBuffer.format;
        }

        return null;
    }

    @Override
    public void release() {
        super.release();
        ffmpegRelease(nativeContext);
        nativeContext = 0;
    }

    /**
     * Renders output buffer to the given surface. Must only be called when in {@link
     * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode.
     *
     * @param outputBuffer Output buffer.
     * @param surface      Output surface.
     * @throws FfmpegDecoderException Thrown if called with invalid output mode or frame
     *                                rendering fails.
     */
    public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws FfmpegDecoderException {
        if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
            throw new FfmpegDecoderException("Invalid output mode.");
        }
        if (ffmpegRenderFrame(
                nativeContext, surface,
                outputBuffer, outputBuffer.width, outputBuffer.height) == VIDEO_DECODER_ERROR_OTHER) {
            throw new FfmpegDecoderException("Buffer render error: ");
        }
    }

    private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData, int threads);

    private native long ffmpegReset(long context);

    private native void ffmpegRelease(long context);

    private native int ffmpegRenderFrame(
            long context, Surface surface, VideoDecoderOutputBuffer outputBuffer,
            int displayedWidth,
            int displayedHeight);

    /**
     * Decodes the encoded data passed.
     *
     * @param context     Decoder context.
     * @param encodedData Encoded data.
     * @param length      Length of the data buffer.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_OTHER} if an
     * error occurred.
     */
    private native int ffmpegSendPacket(long context, ByteBuffer encodedData, int length,
                                        long inputTime);

    /**
     * Gets the decoded frame.
     *
     * @param context      Decoder context.
     * @param outputBuffer Output buffer for the decoded frame.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_INVALID_DATA}
     * if successful but the frame is decode-only, {@link #VIDEO_DECODER_ERROR_OTHER} if an error
     * occurred.
     */
    private native int ffmpegReceiveFrame(
            long context, int outputMode, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

}
