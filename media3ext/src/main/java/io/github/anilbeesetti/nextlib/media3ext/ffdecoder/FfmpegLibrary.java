package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;

/** Configures and queries the underlying native library. */
@UnstableApi
public final class FfmpegLibrary {

  static {
    MediaLibraryInfo.registerModule("media3.decoder.ffmpeg");
  }

  private static final String TAG = "FfmpegLibrary";

  private static final LibraryLoader LOADER =
      new LibraryLoader("media3ext") {
        @Override
        protected void loadLibrary(String name) {
          System.loadLibrary(name);
        }
      };


  private static int inputBufferPaddingSize = C.LENGTH_UNSET;

  private FfmpegLibrary() {}

  /**
   * Override the names of the FFmpeg native libraries. If an application wishes to call this
   * method, it must do so before calling any other method defined by this class, and before
   * instantiating a {@link FfmpegAudioRenderer} instance.
   *
   * @param libraries The names of the FFmpeg native libraries.
   */
  public static void setLibraries(String... libraries) {
    LOADER.setLibraries(libraries);
  }

  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }

  /** Returns the version of the underlying library if available, or null otherwise. */
  @Nullable
  public static String getVersion() {
    if (!isAvailable()) {
      return null;
    }
    return ffmpegGetVersion();
  }

  /**
   * Returns the required amount of padding for input buffers in bytes, or {@link C#LENGTH_UNSET} if
   * the underlying library is not available.
   */
  public static int getInputBufferPaddingSize() {
    if (!isAvailable()) {
      return C.LENGTH_UNSET;
    }
    if (inputBufferPaddingSize == C.LENGTH_UNSET) {
      inputBufferPaddingSize = ffmpegGetInputBufferPaddingSize();
    }
    return inputBufferPaddingSize;
  }

  public static boolean supportsFormat(Format format) {
    if (!isAvailable()) {
      return false;
    }
    @Nullable String codecName = getCodecName(format);
    if (codecName == null) {
      return false;
    }
    if (!ffmpegHasDecoder(codecName)) {
      Log.w(TAG, "No " + codecName + " decoder available. Check the FFmpeg build configuration.");
      return false;
    }
    return true;
  }

  @Nullable
  public static String getCodecName(Format format) {
    String mimeType = format.sampleMimeType;
    // Dolby Vision special handling - map to base codec (H264/HEVC/AV1)
    // Dolby Vision metadata is handled by ExoPlayer layer, FFmpeg just decodes the base stream
    if (MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType)) {
      String codecPrivate = format.codecs;
      if (codecPrivate == null) return "hevc";
      if (codecPrivate.startsWith("dvav") || codecPrivate.startsWith("dva1")) return "h264";
      if (codecPrivate.startsWith("dav1") || codecPrivate.startsWith("dvav")) return "libdav1d";
      return "hevc";
    }

    // All other MIME mappings (only decoders enabled in ENABLED_DECODERS)
    return switch (mimeType) {
      // Audio (15 standard decoders)
      case MimeTypes.AUDIO_AAC -> "aac";
      case MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> "mp3";
      case "audio/mp2" -> "mp2";
      case MimeTypes.AUDIO_AC3 -> "ac3";
      case MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3";
      case MimeTypes.AUDIO_TRUEHD -> "truehd";
      case MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X -> "dca";
      case MimeTypes.AUDIO_VORBIS -> "vorbis";
      case MimeTypes.AUDIO_OPUS -> "opus";
      case MimeTypes.AUDIO_AMR_NB -> "amrnb";
      case MimeTypes.AUDIO_AMR_WB -> "amrwb";
      case MimeTypes.AUDIO_FLAC -> "flac";
      case MimeTypes.AUDIO_ALAC -> "alac";
      case MimeTypes.AUDIO_MLAW -> "pcm_mulaw";
      case MimeTypes.AUDIO_ALAW -> "pcm_alaw";
      // Non-standard audio (13 decoders)
      case "audio/atrac3" -> "atrac3";
      case "audio/x-adpcm-ms" -> "adpcm_ms";
      case "audio/x-adpcm-ima-wav" -> "adpcm_ima_wav";
      case "audio/x-ms-wmalossless" -> "wmalossless";
      case "audio/x-ms-wmapro" -> "wmapro";
      case "audio/cook" -> "cook";
      case "audio/atrac3p" -> "atrac3p";
      case "audio/x-monkeys-audio" -> "ape";
      case "audio/x-wavpack" -> "wavpack";
      case "audio/x-tta" -> "tta";
      case "audio/x-nellymoser" -> "nellymoser";

      // Video (20 decoders)
      case MimeTypes.VIDEO_H264 -> "h264";
      case MimeTypes.VIDEO_H265 -> "hevc";
      case MimeTypes.VIDEO_MPEG -> "mpegvideo";
      case MimeTypes.VIDEO_MPEG2 -> "mpeg2video";
      case "video/mpeg1" -> "mpegvideo";
      case MimeTypes.VIDEO_VP8 -> "libvpx_vp8";
      case MimeTypes.VIDEO_VP9 -> "libvpx_vp9";
      case MimeTypes.VIDEO_AV1 -> "libdav1d";
      // Non-standard video (9 decoders)
      case "video/x-ms-wmv" -> "wmv3";
      case MimeTypes.VIDEO_VC1 -> "vc1";
      case "video/x-ms-wmv2" -> "wmv2";
      case "video/prores" -> "prores";
      case MimeTypes.VIDEO_RV40 -> "rv40";
      case MimeTypes.VIDEO_MP4V -> "mpeg4";
      case MimeTypes.VIDEO_MJPEG -> "mjpeg";
      case "video/ogg-theora" -> "theora";
      case "video/x-ffv1" -> "ffv1";
      case "video/x-dnxhd" -> "dnxhd";
      case MimeTypes.VIDEO_H263 -> "h263";

      default -> null;
    };
  }

  private static native String ffmpegGetVersion();

  private static native int ffmpegGetInputBufferPaddingSize();

  private static native boolean ffmpegHasDecoder(String codecName);
}
