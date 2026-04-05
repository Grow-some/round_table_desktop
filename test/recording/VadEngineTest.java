package recording;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VadEngine の単体テスト。PcmUtils 定数に依存するが AppConfig / javax.sound には依存しない。
 */
class VadEngineTest {

    // ── ヘルパー ───────────────────────────────────────────────────────────────
    /** 全サンプルが同じ振幅の 1 フレーム（640 bytes） */
    private static byte[] loudFrame(short amplitude) {
        byte[] frame = new byte[PcmUtils.FRAME_BYTES];
        ByteBuffer buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        while (buf.hasRemaining()) buf.putShort(amplitude);
        return frame;
    }

    private static byte[] silentFrame() {
        return new byte[PcmUtils.FRAME_BYTES];
    }

    /** threshold=500 の VadEngine を作成し segmentListener と levelCallback を差し込む */
    private VadEngine makeVad(VadListener seg, java.util.function.Consumer<VadEngine.LevelInfo> lvl) {
        VadEngine vad = new VadEngine(seg, lvl);
        vad.setThreshold(500);
        return vad;
    }

    // ── 無音のみ ─────────────────────────────────────────────────────────────
    @Test
    void silence_only_doesNotFireListener() {
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);
        byte[] silent = silentFrame();
        for (int i = 0; i < 100; i++) vad.feed(silent, silent.length);
        assertFalse(fired.get(), "無音のみではセグメントが通知されてはならない");
    }

    // ── 発話→無音 でリスナー発火 ─────────────────────────────────────────────
    @Test
    void speech_followed_by_silence_firesListener() {
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);

        byte[] loud   = loudFrame((short) 5000); // RMS ≫ 500
        byte[] silent = silentFrame();

        // MIN_FRAMES = 25 を超える音声 → 30 フレーム
        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);
        // SILENCE_FRAMES = 60 を超える無音 → 65 フレーム
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertTrue(fired.get(), "発話→無音後にセグメントが通知されるべき");
    }

    // ── MIN_FRAMES 未満の短い発話は無視 ──────────────────────────────────────
    @Test
    void shortSpeech_belowMinFrames_notFired() {
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);

        byte[] loud   = loudFrame((short) 5000);
        byte[] silent = silentFrame();

        // 5 フレームだけ → MIN_FRAMES (25) 未満
        for (int i = 0; i < 5; i++)  vad.feed(loud, loud.length);
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertFalse(fired.get(), "MIN_FRAMES 未満の短い発話は無視されるべき");
    }

    // ── セグメント PCM に発話データが含まれる ─────────────────────────────────
    @Test
    void segment_pcm_isNotEmpty() {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        VadEngine vad = makeVad((pcm, t0, t1) -> captured.set(pcm), null);

        byte[] loud   = loudFrame((short) 5000);
        byte[] silent = silentFrame();
        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertNotNull(captured.get());
        assertTrue(captured.get().length > 0, "セグメント PCM データが空であってはならない");
    }

    // ── タイムスタンプの前後関係 ─────────────────────────────────────────────
    @Test
    void segment_timestamps_startBeforeEnd() {
        AtomicReference<java.time.LocalDateTime> t0Ref = new AtomicReference<>();
        AtomicReference<java.time.LocalDateTime> t1Ref = new AtomicReference<>();
        VadEngine vad = makeVad((pcm, t0, t1) -> { t0Ref.set(t0); t1Ref.set(t1); }, null);

        byte[] loud   = loudFrame((short) 5000);
        byte[] silent = silentFrame();
        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertNotNull(t0Ref.get());
        assertFalse(t0Ref.get().isAfter(t1Ref.get()), "開始時刻が終了時刻より後であってはならない");
    }

    // ── LevelInfo のコールバック ──────────────────────────────────────────────
    @Test
    void levelCallback_isFiredEveryFrame() {
        int[] count = {0};
        VadEngine vad = makeVad(null, lvl -> count[0]++);

        byte[] loud = loudFrame((short) 5000);
        for (int i = 0; i < 5; i++) vad.feed(loud, loud.length);

        assertEquals(5, count[0], "フレームごとにレベルコールバックが呼ばれるべき");
    }

    @Test
    void levelInfo_isSpeech_whenAboveThreshold() {
        AtomicReference<VadEngine.LevelInfo> last = new AtomicReference<>();
        VadEngine vad = makeVad(null, last::set);

        vad.feed(loudFrame((short) 5000), PcmUtils.FRAME_BYTES); // RMS 5000 > threshold 500
        assertNotNull(last.get());
        assertTrue(last.get().isSpeech(), "閾値を超えたフレームは発話と判定されるべき");
    }

    @Test
    void levelInfo_notSpeech_whenBelowThreshold() {
        AtomicReference<VadEngine.LevelInfo> last = new AtomicReference<>();
        VadEngine vad = makeVad(null, last::set);

        vad.feed(loudFrame((short) 100), PcmUtils.FRAME_BYTES); // RMS 100 < threshold 500
        assertFalse(last.get().isSpeech(), "閾値以下のフレームは無音と判定されるべき");
    }

    // ── ゲイン ────────────────────────────────────────────────────────────────
    @Test
    void gain_amplifies_belowThreshold_to_aboveThreshold() {
        AtomicReference<VadEngine.LevelInfo> last = new AtomicReference<>();
        VadEngine vad = new VadEngine(null, last::set);
        vad.setThreshold(8000); // threshold = 8000

        // RMS ~1000 (gain=1) → 1000 < 8000 : 発話なし
        vad.setGain(1.0);
        vad.feed(loudFrame((short) 1000), PcmUtils.FRAME_BYTES);
        assertFalse(last.get().isSpeech());

        // RMS ~1000 * 9 = 9000 (gain=9) > 8000 : 発話あり
        vad.setGain(9.0);
        vad.feed(loudFrame((short) 1000), PcmUtils.FRAME_BYTES);
        assertTrue(last.get().isSpeech());
    }

    // ── clipping 判定 ────────────────────────────────────────────────────────
    @Test
    void levelInfo_isClipping_whenGainCausesOverflow() {
        AtomicReference<VadEngine.LevelInfo> last = new AtomicReference<>();
        VadEngine vad = new VadEngine(null, last::set);
        vad.setThreshold(100);
        vad.setGain(4.0); // 20000 * 4 = 80000 → clipped

        vad.feed(loudFrame((short) 20000), PcmUtils.FRAME_BYTES);
        assertTrue(last.get().isClipping(), "大きなゲインでクリッピングが検出されるべき");
    }

    // ── reset ────────────────────────────────────────────────────────────────
    @Test
    void reset_preventsSegmentAfterReset() {
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);

        byte[] loud = loudFrame((short) 5000);
        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);

        vad.reset(); // リセット後に無音を送っても発火しないはず

        byte[] silent = silentFrame();
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertFalse(fired.get(), "reset() 後はセグメントが通知されてはならない");
    }

    @Test
    void reset_isInSpeech_returnsFalse() {
        VadEngine vad = makeVad(null, null);
        byte[] loud = loudFrame((short) 5000);
        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);
        assertTrue(vad.isInSpeech());

        vad.reset();
        assertFalse(vad.isInSpeech(), "reset() 後は発話中フラグがクリアされるべき");
    }

    // ── setSilenceMs() ─────────────────────────────────────────────────────────
    @Test
    void setSilenceMs_shortDuration_acceleratesDetection() {
        // setSilenceMs(200) → silenceFrames = 200/20 = 10 フレーム
        // デフォルト 60 フレームではなく 10 フレームの無音でセグメントが発火すること
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);
        vad.setSilenceMs(200); // 200ms → 10 フレーム

        byte[] loud   = loudFrame((short) 5000);
        byte[] silent = silentFrame();

        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);
        // 11 フレームの無音（10 フレームの threshold に達する）
        for (int i = 0; i < 11; i++) vad.feed(silent, silent.length);

        assertTrue(fired.get(), "setSilenceMs(200) 設定では 11 フレームの無音でセグメントが通知されるべき");
    }

    @Test
    void setSilenceMs_defaultValue_equalsOriginalBehavior() {
        // デフォルト SILENCE_MS_DEFAULT=1200ms=60フレームと setSilenceMs(1200) が同じ動作をすること
        AtomicBoolean firedDefault = new AtomicBoolean(false);
        VadEngine vad1 = makeVad((pcm, t0, t1) -> firedDefault.set(true), null);
        // setSilenceMs() 未呼び出し = デフォルト

        AtomicBoolean firedSet = new AtomicBoolean(false);
        VadEngine vad2 = makeVad((pcm, t0, t1) -> firedSet.set(true), null);
        vad2.setSilenceMs(VadEngine.SILENCE_MS_DEFAULT);

        byte[] loud = loudFrame((short) 5000); byte[] silent = silentFrame();
        for (int i = 0; i < 30; i++) { vad1.feed(loud, loud.length); vad2.feed(loud, loud.length); }
        for (int i = 0; i < 60; i++) { vad1.feed(silent, silent.length); vad2.feed(silent, silent.length); }

        assertEquals(firedDefault.get(), firedSet.get(),
            "setSilenceMs(SILENCE_MS_DEFAULT) はデフォルトと同じ挙動になるべき");
    }

    // ── 境界値: PAD_FRAMES / MIN_FRAMES / SILENCE_FRAMES(デフォルト) ──────────
    // PAD_MS=100ms / SILENCE_MS_DEFAULT=1200ms / MIN_SPEECH_MS=500ms、フレーム長=20ms から算出:
    //   PAD_FRAMES     = 100 / 20 = 5
    //   SILENCE_FRAMES(default) = 1200 / 20 = 60
    //   MIN_FRAMES     = 500 / 20 = 25

    @Test
    void padBuf_atPadFramesBoundary_includesPreSpeechFrames() {
        // PAD_FRAMES=5 フレームの無音を先行投入後に発話開始すると、
        // セグメント PCM にパディング分のデータが含まれること
        AtomicReference<byte[]> captured = new AtomicReference<>();
        VadEngine vad = makeVad((pcm, t0, t1) -> captured.set(pcm), null);

        byte[] silent = silentFrame();
        byte[] loud   = loudFrame((short) 5000);

        for (int i = 0; i < 5; i++) vad.feed(silent, silent.length); // PAD_FRAMES ぴったり
        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertNotNull(captured.get(), "セグメントが通知されるべき");
        // パディング 5 フレーム分 + 発話 30 フレーム以上のサイズを期待
        int expectedMinBytes = (5 + 30) * PcmUtils.FRAME_BYTES;
        assertTrue(captured.get().length >= expectedMinBytes,
            "パディングフレームがセグメントに含まれるため PCM サイズは " + expectedMinBytes + " bytes 以上であるべき");
    }

    @Test
    void minFrames_exactly25_fires() {
        // MIN_FRAMES=25 ちょうどの発話フレーム数でセグメントが発火すること
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);

        byte[] loud   = loudFrame((short) 5000);
        byte[] silent = silentFrame();

        for (int i = 0; i < 25; i++) vad.feed(loud, loud.length);  // MIN_FRAMES ぴったり
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertTrue(fired.get(), "MIN_FRAMES ちょうどの発話でセグメントが通知されるべき");
    }

    @Test
    void minFrames_24frames_doesNotFire() {
        // MIN_FRAMES-1=24 フレームの発話ではセグメントが発火しないこと
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);

        byte[] loud   = loudFrame((short) 5000);
        byte[] silent = silentFrame();

        for (int i = 0; i < 24; i++) vad.feed(loud, loud.length);  // MIN_FRAMES - 1
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertFalse(fired.get(), "MIN_FRAMES - 1 フレームの発話はセグメントが通知されてはならない");
    }

    @Test
    void silenceFrames_exactly60_fires() {
        // SILENCE_FRAMES=60 ちょうどの無音でセグメントが発火すること
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);

        byte[] loud   = loudFrame((short) 5000);
        byte[] silent = silentFrame();

        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);
        for (int i = 0; i < 60; i++) vad.feed(silent, silent.length); // SILENCE_FRAMES ぴったり

        assertTrue(fired.get(), "SILENCE_FRAMES ちょうどの無音でセグメントが通知されるべき");
    }

    @Test
    void silenceFrames_59frames_doesNotFire() {
        // SILENCE_FRAMES-1=59 フレームの無音ではまだ発火しないこと
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);

        byte[] loud   = loudFrame((short) 5000);
        byte[] silent = silentFrame();

        for (int i = 0; i < 30; i++) vad.feed(loud, loud.length);
        for (int i = 0; i < 59; i++) vad.feed(silent, silent.length); // SILENCE_FRAMES - 1

        assertFalse(fired.get(), "SILENCE_FRAMES - 1 フレームの無音ではセグメントが通知されてはならない");
    }

    // ── MT-16: VAD 感度（高閾値）設定では低振幅の発話が検出されない ──────────
    @Test
    void vadSensitivity_highThreshold_doesNotDetectLowAmplitudeSpeech() {
        // threshold=500 に対して amplitude=200（閾値未満）のフレームを
        // MIN_FRAMES を超える 30 フレーム投入しても発話セグメントが通知されないこと（MT-16）
        AtomicBoolean fired = new AtomicBoolean(false);
        VadEngine vad = makeVad((pcm, t0, t1) -> fired.set(true), null);

        byte[] softFrame = loudFrame((short) 200); // RMS ~200 < threshold 500
        byte[] silent    = silentFrame();
        for (int i = 0; i < 30; i++) vad.feed(softFrame, softFrame.length);
        for (int i = 0; i < 65; i++) vad.feed(silent, silent.length);

        assertFalse(fired.get(), "高閾値（500）設定では振幅 200 の低音量は発話として検出されないこと（MT-16）");
    }

    // ── デフォルトゲイン = 2.0x（設定値エンティティ 5.1） ───────────────────
    @Test
    void defaultGain_is2x_rmsIsDoubled() {
        // setGain() を呼ばない場合、ゲインはデフォルト 2.0x であること
        // gainedRms ≈ rawRms * 2 を LevelInfo で検証する
        AtomicReference<VadEngine.LevelInfo> last = new AtomicReference<>();
        VadEngine vad = new VadEngine(null, last::set); // setGain() なし
        vad.setThreshold(1); // 発話判定ではなく rms を確認したいだけ

        byte[] frame = loudFrame((short) 1000); // rawRms ≈ 1000
        vad.feed(frame, frame.length);

        assertNotNull(last.get());
        // gain=2.0 なら rms ≈ 2000（±1%の誤差を許容）
        double ratio = last.get().rms() / last.get().rawRms();
        assertEquals(2.0, ratio, 0.02, "デフォルトゲインは 2.0x であるべき（設定値エンティティ 5.1）");
    }
}
