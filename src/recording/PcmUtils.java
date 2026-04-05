package recording;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 16bit Little-Endian PCM に対する純粋静的ユーティリティ。
 * 外部依存なし。
 */
public final class PcmUtils {

    private PcmUtils() {}

    // ── 音声設定定数 ──────────────────────────────────────────────────────────
    public static final int SAMPLE_RATE      = 16000;
    public static final int CHANNELS         = 1;
    public static final int BITS             = 16;
    public static final int BYTES_PER_SAMPLE = BITS / 8;
    public static final int BYTES_PER_SEC    = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE;
    /** 1フレーム = 20ms */
    public static final int FRAME_SAMPLES    = SAMPLE_RATE / 50;
    public static final int FRAME_BYTES      = FRAME_SAMPLES * BYTES_PER_SAMPLE;

    /**
     * バッファの RMS (0〜32768 スケール) を返す。
     * サンプル数が 0 の場合は 0.0 を返す。
     */
    public static double rms(byte[] buf, int len) {
        long sum = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            short s = (short)(((buf[i + 1] & 0xFF) << 8) | (buf[i] & 0xFF));
            sum += (long) s * s;
        }
        int samples = len / 2;
        return samples > 0 ? Math.sqrt((double) sum / samples) : 0.0;
    }

    /**
     * バッファをインプレースでゲイン増幅する。±32767 でクランプ。
     * gain == 1.0 の場合は何もしない。
     */
    public static void applyGain(byte[] buf, int len, double gain) {
        if (gain == 1.0) return;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short)(((buf[i + 1] & 0xFF) << 8) | (buf[i] & 0xFF));
            s = (int) Math.max(-32768, Math.min(32767, s * gain));
            buf[i]     = (byte)(s & 0xFF);
            buf[i + 1] = (byte)((s >> 8) & 0xFF);
        }
    }

    /**
     * バッファリストを 1 つの byte[] に連結する。
     */
    public static byte[] concat(List<byte[]> bufs) {
        int total = bufs.stream().mapToInt(b -> b.length).sum();
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] b : bufs) {
            System.arraycopy(b, 0, out, pos, b.length);
            pos += b.length;
        }
        return out;
    }

    /**
     * PCM バイト列を 16bit mono WAV ファイルとして書き込む。
     */
    public static void writeWav(Path path, byte[] pcm) throws IOException {
        int dataLen = pcm.length;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[]{'R','I','F','F'});  b.putInt(36 + dataLen);
        b.put(new byte[]{'W','A','V','E'});
        b.put(new byte[]{'f','m','t',' '});  b.putInt(16);
        b.putShort((short) 1);               b.putShort((short) CHANNELS);
        b.putInt(SAMPLE_RATE);               b.putInt(BYTES_PER_SEC);
        b.putShort((short)(CHANNELS * BYTES_PER_SAMPLE));
        b.putShort((short) BITS);
        b.put(new byte[]{'d','a','t','a'});  b.putInt(dataLen);
        b.put(pcm);
        Files.write(path, b.array());
    }

    /**
     * whisper.cpp タイムスタンプ (h, m, s, ms 文字列) を秒に変換する。
     */
    public static double toSec(String h, String m, String s, String ms) {
        return Integer.parseInt(h) * 3600.0
             + Integer.parseInt(m) * 60.0
             + Integer.parseInt(s)
             + Integer.parseInt(ms) / 1000.0;
    }

    /**
     * ゲインをかけたときに何サンプルがクリップされるか返す（0 ならクリップなし）。
     * レベルメーター表示などに使う。
     */
    public static int countClipped(byte[] buf, int len, double gain) {
        int count = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int s = (short)(((buf[i + 1] & 0xFF) << 8) | (buf[i] & 0xFF));
            double amplified = s * gain;
            if (amplified > 32767 || amplified < -32768) count++;
        }
        return count;
    }
}
