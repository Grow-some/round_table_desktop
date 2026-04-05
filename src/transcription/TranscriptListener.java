package transcription;

import java.time.LocalDateTime;

/**
 * 文字起こし結果 (1エントリ) を通知するコールバックインターフェース。
 */
@FunctionalInterface
public interface TranscriptListener {
    /**
     * @param entry  表示・保存用の文字起こしテキスト (末尾 \n 付き)
     * @param logLine ログファイルに追記する1行 (表示用と同じ内容)
     * @param date    ログファイル名のベース日付 (yyyy-MM-dd)
     */
    void onTranscript(String entry, String logLine, LocalDateTime date);
}
