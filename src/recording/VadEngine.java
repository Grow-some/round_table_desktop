package recording;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * 音量ベース VAD (Voice Activity Detection) 状態機械。
 *
 * 使い方:
 *   VadEngine vad = new VadEngine(listener, levelCallback);
 *   while (recording) {
 *       vad.setThreshold(slider.getValue());
 *       vad.setGain(gainSlider.getValue() / 10.0);
 *       vad.setSilenceMs(silenceSlider.getValue());
 *       vad.feed(frameBytes, bytesRead);
 *   }
 *   vad.reset();
 *
 * スレッドセーフ: feed() は単一の録音スレッドから呼ぶこと。
 * threshold / gain / silenceMs のセッターは任意スレッドから呼べる (volatile)。
 */
public class VadEngine {

    // ── VAD パラメータ (ms → フレーム数に変換) ────────────────────────────────
    /** 発話前パディング (ms) */
    private static final int PAD_MS        = 100;
    /** 終話判定の無音時間デフォルト値 (ms) */
    public  static final int SILENCE_MS_DEFAULT = 1200;
    /** 最短発話長 (ms) — これ未満は無視 */
    private static final int MIN_SPEECH_MS = 500;

    private static final int PAD_FRAMES =
        Math.max(1, (PAD_MS        * PcmUtils.SAMPLE_RATE) / (1000 * PcmUtils.FRAME_SAMPLES));
    private static final int MIN_FRAMES =
        Math.max(1, (MIN_SPEECH_MS * PcmUtils.SAMPLE_RATE) / (1000 * PcmUtils.FRAME_SAMPLES));

    /** 終話判定の無音継続フレーム数（setSilenceMs で動的変更可能） */
    private volatile int silenceFrames =
        Math.max(1, (SILENCE_MS_DEFAULT * PcmUtils.SAMPLE_RATE) / (1000 * PcmUtils.FRAME_SAMPLES));

    // ── コールバック ──────────────────────────────────────────────────────────
    private final VadListener         segmentListener;
    /** RMS と クリップ率 (0.0〜1.0) を受け取るレベルメーターコールバック */
    private final Consumer<LevelInfo> levelCallback;

    // ── 状態 ──────────────────────────────────────────────────────────────────
    private volatile double threshold = 300.0;
    private volatile double gain      = 2.0;

    private final List<byte[]>        speechBuf = new ArrayList<>();
    private final ArrayDeque<byte[]>  padBuf    = new ArrayDeque<>(PAD_FRAMES + 1);
    private int           silenceCount     = 0;
    private int           speechFrameCount = 0;  // 発話フレーム数のみカウント（無音除く）
    private boolean       inSpeech     = false;
    private LocalDateTime speechStart  = null;

    /**
     * @param segmentListener 発話セグメント検出時のコールバック
     * @param levelCallback   毎フレームの音量情報コールバック (null 可)
     */
    public VadEngine(VadListener segmentListener, Consumer<LevelInfo> levelCallback) {
        this.segmentListener = segmentListener;
        this.levelCallback   = levelCallback;
    }

    /** RMS 閾値を変更する (UIスライダーと同期)。 */
    public void setThreshold(double threshold) { this.threshold = threshold; }

    /** ソフトウェアゲインを変更する (UIスライダーと同期)。 */
    public void setGain(double gain) { this.gain = gain; }

    /** 終話判定の無音時間を変更する (UIコンボボックスと同期)。ms は 1 以上。 */
    public void setSilenceMs(int ms) {
        this.silenceFrames = Math.max(1, (ms * PcmUtils.SAMPLE_RATE) / (1000 * PcmUtils.FRAME_SAMPLES));
    }

    /**
     * 1フレーム分の PCM を受け取り、VAD 状態を更新する。
     * ゲイン適用はこのメソッド内でコピーに対して行う (元バッファは変更しない)。
     *
     * @param raw      マイクから読んだ生 PCM バッファ
     * @param len      有効バイト数
     */
    public void feed(byte[] raw, int len) {
        // ゲインを適用したコピーを作成
        byte[] frame = Arrays.copyOf(raw, len);
        double currentGain      = this.gain;
        double currentThreshold = this.threshold;

        PcmUtils.applyGain(frame, len, currentGain);

        double  rawRms   = PcmUtils.rms(raw, len);  // ゲイン前
        double  rms      = PcmUtils.rms(frame, len);
        boolean isSpeech = (rms >= currentThreshold);

        // レベルメーターコールバック
        if (levelCallback != null) {
            int clipped = PcmUtils.countClipped(raw, len, currentGain);
            int samples = len / 2;
            levelCallback.accept(new LevelInfo(rawRms, rms, currentThreshold,
                samples > 0 ? (double) clipped / samples : 0.0));
        }

        if (!inSpeech) {
            padBuf.addLast(Arrays.copyOf(frame, len));
            if (padBuf.size() > PAD_FRAMES) padBuf.pollFirst();

            if (isSpeech) {
                inSpeech          = true;
                silenceCount      = 0;
                speechFrameCount  = padBuf.size(); // パッドフレームを発話としてカウント
                long padNanos = (long) padBuf.size()
                    * PcmUtils.FRAME_SAMPLES * 1_000_000_000L / PcmUtils.SAMPLE_RATE;
                speechStart = LocalDateTime.now().minusNanos(padNanos);
                speechBuf.clear();
                for (byte[] pb : padBuf) speechBuf.add(pb.clone());
            }
        } else {
            speechBuf.add(Arrays.copyOf(frame, len));
            if (!isSpeech) {
                silenceCount++;
                if (silenceCount >= silenceFrames) {
                    inSpeech = false;
                    if (speechFrameCount >= MIN_FRAMES) {
                        byte[]        pcm = PcmUtils.concat(speechBuf);
                        LocalDateTime t0  = speechStart;
                        LocalDateTime t1  = LocalDateTime.now();
                        segmentListener.onSegment(pcm, t0, t1);
                    }
                    speechBuf.clear();
                    padBuf.clear();
                    silenceCount     = 0;
                    speechFrameCount = 0;
                }
            } else {
                speechFrameCount++;
                silenceCount = 0;
            }
        }
    }

    /**
     * 録音停止時に発話中のセグメントを強制フラッシュする。
     * MIN_FRAMES 以上の発話が蓄積されていればセグメントとして通知する。
     * このメソッドの後に必ず reset() を呼ぶこと。
     */
    public void flush() {
        if (inSpeech && speechFrameCount >= MIN_FRAMES && !speechBuf.isEmpty()) {
            byte[]        pcm = PcmUtils.concat(speechBuf);
            LocalDateTime t0  = speechStart;
            LocalDateTime t1  = LocalDateTime.now();
            segmentListener.onSegment(pcm, t0, t1);
        }
    }

    /** 録音停止時に状態をリセットする。 */
    public void reset() {
        speechBuf.clear();
        padBuf.clear();
        silenceCount     = 0;
        speechFrameCount = 0;
        inSpeech         = false;
        speechStart      = null;
    }

    /** 現在発話中かどうかを返す。 */
    public boolean isInSpeech() { return inSpeech; }

    // ── レベル情報 ─────────────────────────────────────────────────────────────
    public record LevelInfo(double rawRms, double rms, double threshold, double clipRatio) {
        /** 発話検出中かどうか */
        public boolean isSpeech() { return rms >= threshold; }
        /** クリッピングが発生しているか (1% 以上をクリップありとみなす) */
        public boolean isClipping() { return clipRatio > 0.01; }
    }
}
