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

    // 1. Raw audio -> PCM
    if (MimeTypes.AUDIO_RAW.equals(mimeType)) {
      int encoding = format.pcmEncoding;
      return switch (encoding) {
        case 3 -> "pcm_u8";
        case 4 -> "pcm_f32le";
        case 21 -> "pcm_s24le";
        case 22 -> "pcm_s32le";
        case 0x10000000 -> "pcm_s16be";
        case 0x50000000 -> "pcm_s24be";
        case 0x60000000 -> "pcm_s32be";
        case 0x70000000 -> "pcm_f64le";
        default -> "pcm_s16le";
      };
    }

    // 2. Dolby Vision special handling
    if (MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType)) {
      String codecPrivate = format.codecs;
      if (codecPrivate == null) return "hevc";
      if (codecPrivate.startsWith("dvav")) return "h264";
      if (codecPrivate.startsWith("dav1")) return null;
      return "hevc";
    }

    // 3. All other MIME mappings
    return switch (mimeType) {
      // Audio
      case MimeTypes.AUDIO_AAC -> "aac";
      case MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> "mp3";
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
      // Non‑standard audio
      case "audio/vnd.dsd-lsbf-planar" -> "dsd_lsbf_planar";
      case "audio/atrac3" -> "atrac3";
      case "audio/x-adpcm-ms" -> "adpcm_ms";
      case "audio/x-adpcm-ima-wav" -> "adpcm_ima_wav";
      case "audio/vnd.dsd" -> "dsd_msbf";
      case "audio/vnd.dst" -> "dst";
      case "audio/x-ms-wmalossless" -> "wmalossless";
      case "audio/x-ralf" -> "ralf";
      case "audio/x-sipr" -> "sipr";
      case "audio/x-ms-wmapro" -> "wmapro";
      case "audio/x-ms-wmavoice" -> "wmavoice";
      case "audio/vnd.dsd-msbf-planar" -> "dsd_msbf_planar";
      case "audio/x-ms-wmav1" -> "wmav1";
      case "audio/x-ms-wmav2" -> "wmav2";
      case "audio/av3a" -> "libarcdav3a";
      case "audio/cook" -> "cook";
      case "audio/atrac3p" -> "atrac3p";

      // Video
      case MimeTypes.VIDEO_H264 -> "h264";
      case MimeTypes.VIDEO_H265 -> "hevc";
      case MimeTypes.VIDEO_MPEG -> "mpeg1video";
      case MimeTypes.VIDEO_MPEG2 -> "mpeg2video";
      case MimeTypes.VIDEO_VP8 -> "libvpx";
      case MimeTypes.VIDEO_VP9 -> "libvpx-vp9";
      case MimeTypes.VIDEO_AV1 -> "libdav1d";
      // Non‑standard video
      case "video/x-ms-wmv" -> "wmv3";
      case "video/mp41" -> "msmpeg4v1";
      case "video/mp42" -> "msmpeg4v2";
      case "video/mp43" -> "msmpeg4";
      case "video/wvc1" -> "vc1";
      case "video/x-ms-wmv1" -> "wmv1";
      case "video/x-ms-wmv2" -> "wmv2";
      case "video/prores" -> "prores";
      case "video/x-rv10" -> "rv10";
      case "video/x-rv20" -> "rv20";
      case "video/x-rv30" -> "rv30";
      case "video/x-rv40" -> "rv40";
      case "video/mp4v-es" -> "mpeg4";
      case "video/mjpeg" -> "mjpeg";

      default -> null;
    };
  }

  private static native String ffmpegGetVersion();

  private static native int ffmpegGetInputBufferPaddingSize();

  private static native boolean ffmpegHasDecoder(String codecName);
}
