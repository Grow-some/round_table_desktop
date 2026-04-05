package recording;

import java.time.LocalDateTime;

/**
 * 発話セグメント検出時のコールバックインターフェース。
 */
@FunctionalInterface
public interface VadListener {
    /**
     * @param pcm   ゲイン適用済みの 16bit LE PCM バイト列
     * @param start 発話開始時刻
     * @param end   発話終了時刻
     */
    void onSegment(byte[] pcm, LocalDateTime start, LocalDateTime end);
}
