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

    private static final int VIDEO_DECODER_SUCCESS = 0;
    private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
    private static final int VIDEO_DECODER_ERROR_OTHER = -2;
    private static final int VIDEO_DECODER_ERROR_READ_FRAME = -3;

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

        switch (mimeType) {
            case MimeTypes.VIDEO_DOLBY_VISION -> {
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
            case MimeTypes.VIDEO_H265, MimeTypes.VIDEO_VC1, "audio/rv40" -> {
                return initializationData.get(0);
            }
            case MimeTypes.VIDEO_H264 -> {
                if (initializationData.size() >= 2) {
                    byte[] sps = initializationData.get(0);
                    byte[] pps = initializationData.get(1);
                    byte[] combined = new byte[sps.length + pps.length];
                    System.arraycopy(sps, 0, combined, 0, sps.length);
                    System.arraycopy(pps, 0, combined, sps.length, pps.length);
                    return combined;
                } else {
                    return initializationData.get(0);
                }
            }
        }

        return mergeInitializationData(initializationData);
    }

    /**
     * Merges all initialization data entries into a single byte array.
     */
    @Nullable
    private byte[] mergeInitializationData(List<byte[]> initializationData) {
        if (initializationData.isEmpty()) return null;
        int totalSize = 0;
        for (byte[] data : initializationData) {
            totalSize += data.length;
        }
        if (totalSize == 0) return null;
        byte[] merged = new byte[totalSize];
        int offset = 0;
        for (byte[] data : initializationData) {
            System.arraycopy(data, 0, merged, offset, data.length);
            offset += data.length;
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

        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        int inputSize = inputData.limit();
        int sendPacketResult = ffmpegSendPacket(nativeContext, inputData, inputSize, inputBuffer.timeUs);
        if (sendPacketResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
            outputBuffer.shouldBeSkipped = true;
            return null;
        } else if (sendPacketResult == VIDEO_DECODER_ERROR_READ_FRAME) {
            Log.d(TAG, "VIDEO_DECODER_ERROR_READ_FRAME: " + "timeUs=" + inputBuffer.timeUs);
        } else if (sendPacketResult == VIDEO_DECODER_ERROR_OTHER) {
            return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
        }

        boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
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

    private native int ffmpegSendPacket(long context, ByteBuffer encodedData, int length,
                                        long inputTime);

    private native int ffmpegReceiveFrame(
            long context, int outputMode, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

}
