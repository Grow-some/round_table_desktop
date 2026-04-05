package recording;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * PcmUtils の単体テスト。javax.sound / AppConfig には依存しない。
 */
class PcmUtilsTest {

    // ── ヘルパー ───────────────────────────────────────────────────────────────
    private static byte[] pcm(short... samples) {
        ByteBuffer buf = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : samples) buf.putShort(s);
        return buf.array();
    }

    private static short readShort(byte[] pcm, int idx) {
        return ByteBuffer.wrap(pcm, idx * 2, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    // ── rms ───────────────────────────────────────────────────────────────────
    @Test
    void rms_emptyBuffer_returnsZero() {
        // len=0 の空バッファ境界: 0 除算が起きず 0.0 を返すこと
        assertEquals(0.0, PcmUtils.rms(new byte[0], 0), 0.001);
    }

    @Test
    void rms_allZero_returnsZero() {
        assertEquals(0.0, PcmUtils.rms(pcm((short) 0, (short) 0), 4), 0.001);
    }

    @Test
    void rms_constant_returnsValue() {
        byte[] data = pcm((short) 1000, (short) 1000, (short) 1000, (short) 1000);
        assertEquals(1000.0, PcmUtils.rms(data, data.length), 0.01);
    }

    @Test
    void rms_mixedSign_correctMagnitude() {
        // +1000 と -1000 の RMS は 1000
        byte[] data = pcm((short) 1000, (short) -1000, (short) 1000, (short) -1000);
        assertEquals(1000.0, PcmUtils.rms(data, data.length), 0.01);
    }

    // ── applyGain ─────────────────────────────────────────────────────────────
    @Test
    void applyGain_doubles_values() {
        byte[] data = pcm((short) 100, (short) 200);
        PcmUtils.applyGain(data, data.length, 2.0);
        assertEquals(200, readShort(data, 0));
        assertEquals(400, readShort(data, 1));
    }

    @Test
    void applyGain_clamps_positive() {
        byte[] data = pcm((short) 20000);
        PcmUtils.applyGain(data, data.length, 3.0); // 60000 → clamp to 32767
        assertEquals(32767, readShort(data, 0));
    }

    @Test
    void applyGain_clamps_negative() {
        byte[] data = pcm((short) -20000);
        PcmUtils.applyGain(data, data.length, 3.0); // -60000 → clamp to -32768
        assertEquals(-32768, readShort(data, 0));
    }

    @Test
    void applyGain_clamps_negativeMin() {
        // Short.MIN_VALUE (-32768) にゲインを掛けるとオーバーフロー → -32768 にクランプされること
        // 課題 004: 現コードの下限は -32767 のため、このテストは課題修正後に PASS する
        byte[] data = pcm(Short.MIN_VALUE);
        PcmUtils.applyGain(data, data.length, 2.0); // -65536 → clamp to -32768
        assertEquals(Short.MIN_VALUE, readShort(data, 0));
    }

    @Test
    void applyGain_unity_unchanged() {
        byte[] data = pcm((short) 5000, (short) -3000);
        PcmUtils.applyGain(data, data.length, 1.0);
        assertEquals(5000, readShort(data, 0));
        assertEquals(-3000, readShort(data, 1));
    }

    // ── concat ────────────────────────────────────────────────────────────────
    @Test
    void concat_mergesCorrectly() {
        byte[] a = {1, 2, 3};
        byte[] b = {4, 5};
        byte[] result = PcmUtils.concat(List.of(a, b));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, result);
    }

    @Test
    void concat_empty_returnsEmptyArray() {
        byte[] result = PcmUtils.concat(List.of());
        assertEquals(0, result.length);
    }

    // ── writeWav ──────────────────────────────────────────────────────────────
    @Test
    void writeWav_hasRiffHeader() throws Exception {
        Path tmp = Files.createTempFile("pcm_test", ".wav");
        try {
            byte[] pcmData = pcm((short) 0, (short) 100, (short) -100);
            PcmUtils.writeWav(tmp, pcmData);
            byte[] wav = Files.readAllBytes(tmp);

            // RIFF
            assertEquals('R', wav[0]);
            assertEquals('I', wav[1]);
            assertEquals('F', wav[2]);
            assertEquals('F', wav[3]);
            // WAVE
            assertEquals('W', wav[8]);
            assertEquals('A', wav[9]);
            assertEquals('V', wav[10]);
            assertEquals('E', wav[11]);
            // fmt chunk
            assertEquals('f', wav[12]);
            assertEquals('m', wav[13]);
            assertEquals('t', wav[14]);
            // data chunk
            assertEquals('d', wav[36]);
            assertEquals('a', wav[37]);
            assertEquals('t', wav[38]);
            assertEquals('a', wav[39]);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void writeWav_dataSizeMatchesPcm() throws Exception {
        Path tmp = Files.createTempFile("pcm_test2", ".wav");
        try {
            byte[] pcmData = pcm((short) 1, (short) 2, (short) 3, (short) 4);
            PcmUtils.writeWav(tmp, pcmData);
            byte[] wav = Files.readAllBytes(tmp);
            // Total file size = 44-byte header + pcmData.length
            assertEquals(44 + pcmData.length, wav.length);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // ── toSec ─────────────────────────────────────────────────────────────────
    @Test
    void toSec_convertsSingleHMS() {
        // 1:02:03.456 = 3600 + 120 + 3 + 0.456 = 3723.456
        assertEquals(3723.456, PcmUtils.toSec("1", "02", "03", "456"), 0.001);
    }

    @Test
    void toSec_zero_returnsZero() {
        assertEquals(0.0, PcmUtils.toSec("0", "00", "00", "000"), 0.0001);
    }

    // ── countClipped ─────────────────────────────────────────────────────────
    @Test
    void countClipped_withClipping() {
        byte[] data = pcm((short) 20000); // 20000 * 3.0 = 60000 > 32767
        assertEquals(1, PcmUtils.countClipped(data, data.length, 3.0));
    }

    @Test
    void countClipped_noClipping() {
        byte[] data = pcm((short) 1000); // 1000 * 2.0 = 2000 ≤ 32767
        assertEquals(0, PcmUtils.countClipped(data, data.length, 2.0));
    }

    @Test
    void countClipped_negativeClipping() {
        byte[] data = pcm((short) -20000); // -60000 < -32767
        assertEquals(1, PcmUtils.countClipped(data, data.length, 3.0));
    }

    // ── 定数の妥当性 ──────────────────────────────────────────────────────────
    @Test
    void constants_sampleRate_is16kHz() {
        assertEquals(16000, PcmUtils.SAMPLE_RATE);
    }

    @Test
    void constants_frameSamples_is20ms() {
        // 16000 samples/sec × 0.02 sec = 320 samples
        assertEquals(320, PcmUtils.FRAME_SAMPLES);
    }

    @Test
    void constants_frameBytes_is640() {
        assertEquals(640, PcmUtils.FRAME_BYTES);
    }
}
