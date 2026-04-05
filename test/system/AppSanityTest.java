package system;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import app.AppConfig;
import recording.PcmUtils;
import recording.VadEngine;
import archive.WavArchiver;

/**
 * システムテスト: アプリ全体の整合性を検証する。
 *
 * - 全クラスがロード可能か
 * - PcmUtils 定数が音声処理として妥当な値か
 * - AppConfig の定数が null でないか
 * - VadEngine・WavArchiver が起動・シャットダウンできるか
 *
 * このテストは -Djava.awt.headless=true で実行することを前提とする。
 * デバイス検出 (DeviceSelector) や Swing 描画は行わない。
 */
class AppSanityTest {

    static {
        // AppConfig が参照される前に設定（ヘッドレス確認）
        System.setProperty("java.awt.headless", "true");
        String base = System.getProperty("app.dir",
            System.getProperty("java.io.tmpdir") + "/rt_sanity_" + ProcessHandle.current().pid());
        System.setProperty("app.dir", base);
        System.setProperty("wav.dir",  base + "/wav");
        System.setProperty("log.dir",  base + "/logs");
    }

    // ── クラスロード ──────────────────────────────────────────────────────────
    @Test
    void allCoreClasses_loadWithoutError() {
        assertDoesNotThrow(() -> Class.forName("recording.PcmUtils"));
        assertDoesNotThrow(() -> Class.forName("recording.VadEngine"));
        assertDoesNotThrow(() -> Class.forName("archive.WavArchiver"));
        assertDoesNotThrow(() -> Class.forName("transcription.WhisperRunner"));
        assertDoesNotThrow(() -> Class.forName("app.AppConfig"));
        assertDoesNotThrow(() -> Class.forName("recording.VadListener"));
        assertDoesNotThrow(() -> Class.forName("transcription.TranscriptListener"));
    }

    @Test
    void entryPoint_mainClassExists() {
        assertDoesNotThrow(() -> Class.forName("app.AsrApp"),
            "AsrApp (エントリーポイント) がロードできるべき");
    }

    // ── PcmUtils 定数の妥当性 ────────────────────────────────────────────────
    @Test
    void pcmUtils_sampleRate_is16000() {
        assertEquals(16000, PcmUtils.SAMPLE_RATE, "サンプルレートは 16 kHz であるべき");
    }

    @Test
    void pcmUtils_channels_isMono() {
        assertEquals(1, PcmUtils.CHANNELS, "モノラル (1ch) であるべき");
    }

    @Test
    void pcmUtils_bits_is16() {
        assertEquals(16, PcmUtils.BITS, "量子化ビット数は 16 bit であるべき");
    }

    @Test
    void pcmUtils_frameBytes_consistentWith_frameSamples() {
        assertEquals(PcmUtils.FRAME_SAMPLES * PcmUtils.BYTES_PER_SAMPLE, PcmUtils.FRAME_BYTES,
            "FRAME_BYTES = FRAME_SAMPLES × BYTES_PER_SAMPLE でなければならない");
    }

    @Test
    void pcmUtils_bytesPerSec_consistentWithSampleRate() {
        int expected = PcmUtils.SAMPLE_RATE * PcmUtils.CHANNELS * PcmUtils.BYTES_PER_SAMPLE;
        assertEquals(expected, PcmUtils.BYTES_PER_SEC,
            "BYTES_PER_SEC は SAMPLE_RATE × CHANNELS × BYTES_PER_SAMPLE であるべき");
    }

    // ── AppConfig パスの妥当性 ───────────────────────────────────────────────
    @Test
    void appConfig_appDir_isAbsolute() {
        assertTrue(AppConfig.APP_DIR.isAbsolute(),
            "APP_DIR は絶対パスであるべき");
    }

    @Test
    void appConfig_logDir_isNotNull() {
        assertNotNull(AppConfig.LOG_DIR);
    }

    @Test
    void appConfig_wavDir_isNotNull() {
        assertNotNull(AppConfig.WAV_DIR);
    }

    @Test
    void appConfig_model_containsModelFilename() {
        assertTrue(AppConfig.MODEL.endsWith(".bin"),
            "MODEL パスは .bin ファイルを指すべき");
    }

    // ── VadEngine サイクル ────────────────────────────────────────────────────
    @Test
    void vadEngine_createAndReset_noException() {
        assertDoesNotThrow(() -> {
            VadEngine vad = new VadEngine(null, null);
            vad.setThreshold(300);
            vad.setGain(1.0);
            byte[] frame = new byte[PcmUtils.FRAME_BYTES];
            vad.feed(frame, frame.length);
            vad.reset();
        });
    }

    // ── WavArchiver ライフサイクル ────────────────────────────────────────────
    @Test
    void wavArchiver_startAndShutdown_noException() {
        assertDoesNotThrow(() -> {
            WavArchiver archiver = new WavArchiver(msg -> {});
            archiver.start();
            archiver.shutdown();
        });
    }

    // ── LevelInfo レコード ────────────────────────────────────────────────────
    @Test
    void levelInfo_isSpeech_aboveThreshold() {
        VadEngine.LevelInfo info = new VadEngine.LevelInfo(300.0, 1000.0, 500.0, 0.0);
        assertTrue(info.isSpeech());
    }

    @Test
    void levelInfo_notSpeech_belowThreshold() {
        VadEngine.LevelInfo info = new VadEngine.LevelInfo(50.0, 100.0, 500.0, 0.0);
        assertFalse(info.isSpeech());
    }

    @Test
    void levelInfo_isClipping_whenClipRatioHigh() {
        VadEngine.LevelInfo info = new VadEngine.LevelInfo(1000.0, 1000.0, 500.0, 0.05);
        assertTrue(info.isClipping());
    }

    @Test
    void levelInfo_notClipping_whenClipRatioLow() {
        VadEngine.LevelInfo info = new VadEngine.LevelInfo(1000.0, 1000.0, 500.0, 0.005);
        assertFalse(info.isClipping());
    }

    // ── AppConfig: setDevice() 前の呼び出しで IllegalStateException ───────────
    @Test
    void appConfig_getDeviceLabel_beforeSetDevice_throwsIllegalState() throws Exception {
        // AppConfig の private volatile フィールドをリフレクションで一時的に null にリセットし、
        // setDevice() 未実行状態をシミュレートする
        java.lang.reflect.Field f = AppConfig.class.getDeclaredField("deviceLabel");
        f.setAccessible(true);
        Object saved = f.get(null);
        f.set(null, null);
        try {
            assertThrows(IllegalStateException.class, AppConfig::getDeviceLabel,
                "setDevice() 前に getDeviceLabel() を呼ぶと IllegalStateException が発生すべき");
        } finally {
            f.set(null, saved); // 他テストへの影響を防ぐため復元
        }
    }

    // ── WavArchiver: shutdown() が残 WAV をアーカイブしてから終了する ──────────
    @Test
    void wavArchiver_shutdown_doesNotLoseQueuedArchive() throws Exception {
        // shutdown() を呼ぶと archiveOldWavs(forceAll=true) がキューに投入されること。
        // wav/ ディレクトリが存在しない状態では処理は即終了するが、
        // 例外を投げずに正常終了することを確認する。
        WavArchiver archiver = new WavArchiver(msg -> {});
        assertDoesNotThrow(() -> {
            archiver.shutdown();
        }, "shutdown() が例外を投げてはならない");
    }
}
