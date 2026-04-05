package transcription;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.regex.*;
import app.AppConfig;
import recording.PcmUtils;

/**
 * whisper.cpp CLI を呼び出して PCM セグメントを文字起こしするクラス。
 *
 * WAV 保存 → whisper-cli 実行 → タイムスタンプ変換 → ログ追記 → コールバック通知
 * の一連の処理を 1 スレッドのシングルスレッドエグゼキュータで直列実行する。
 */
public class WhisperRunner {

    private static final DateTimeFormatter DT_FMT   = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final Pattern SEG_PAT = Pattern.compile(
        "^\\[(\\d+):(\\d{2}):(\\d{2})\\.(\\d{3})\\s*-->\\s*" +
        "(\\d+):(\\d{2}):(\\d{2})\\.(\\d{3})\\]\\s*(.*)"
    );

    private final TranscriptListener listener;
    private final StatusCallback      statusCallback;
    private final IntConsumer         pendingCountCallback;
    private final AtomicInteger       pendingCount = new AtomicInteger(0);
    private final ExecutorService     executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "whisper");
        t.setDaemon(true);
        return t;
    });

    /** ステータス文字列を通知する汎用コールバック。 */
    @FunctionalInterface
    public interface StatusCallback {
        void onStatus(String message);
    }

    /**
     * @param listener             文字起こし結果のコールバック
     * @param statusCallback       エラー・タイムアウトなどのメッセージ通知コールバック
     * @param pendingCountCallback 処理中キュー件数変動時のコールバック (int: 現在の件数)
     */
    public WhisperRunner(TranscriptListener listener, StatusCallback statusCallback,
                         IntConsumer pendingCountCallback) {
        this.listener              = listener;
        this.statusCallback        = statusCallback;
        this.pendingCountCallback  = pendingCountCallback;
    }

    /**
     * セグメントの処理をキューに投入する。
     * 呼び出しはブロックしない。
     */
    public void submit(byte[] pcm, LocalDateTime t0, LocalDateTime t1) {
        int n = pendingCount.incrementAndGet();
        pendingCountCallback.accept(n);
        executor.submit(() -> {
            try { process(pcm, t0, t1); }
            finally {
                int remaining = pendingCount.decrementAndGet();
                pendingCountCallback.accept(remaining);
            }
        });
    }

    /** 文字起こし処理中のキュー数を返す。 */
    public int getPendingCount() { return pendingCount.get(); }

    private void process(byte[] pcm, LocalDateTime t0, LocalDateTime t1) {
        String baseName = FILE_FMT.format(t0);
        Path   wavPath  = AppConfig.WAV_DIR.resolve(baseName + ".wav");

        try {
            Files.createDirectories(AppConfig.WAV_DIR);
            Files.createDirectories(AppConfig.LOG_DIR);
            PcmUtils.writeWav(wavPath, pcm);

            ProcessBuilder pb = new ProcessBuilder(
                AppConfig.getWhisperCli(),
                "-m",  AppConfig.MODEL,
                "-f",  wavPath.toAbsolutePath().toString(),
                "-l",  "ja",
                "--vad", "-vm", AppConfig.VAD_MODEL,
                "-t",  "4"
            );
            pb.directory(new File(AppConfig.getWhisperCli()).getParentFile());
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            String output;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                output = br.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
            boolean finished = proc.waitFor(180, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly(); // タイムアウト時はプロセスを強制終了 (課題002)
                statusCallback.onStatus("タイムアウト (" + baseName + "): 処理をスキップします");
                return;
            }
            if (proc.exitValue() != 0) {
                statusCallback.onStatus("処理エラー (終了コード " + proc.exitValue() + "): " + baseName);
                return;
            }

            // タイムスタンプを録音開始時刻ベースの絶対時刻に変換
            StringBuilder sb    = new StringBuilder();
            boolean       found = false;
            for (String line : output.split("\n")) {
                Matcher m = SEG_PAT.matcher(line.trim());
                if (!m.matches()) continue;
                double startSec = PcmUtils.toSec(m.group(1), m.group(2), m.group(3), m.group(4));
                double endSec   = PcmUtils.toSec(m.group(5), m.group(6), m.group(7), m.group(8));
                String text     = m.group(9).trim();
                if (text.isEmpty()) continue;

                LocalDateTime absStart = t0.plusNanos((long)(startSec * 1_000_000_000L));
                LocalDateTime absEnd   = t0.plusNanos((long)(endSec   * 1_000_000_000L));
                sb.append(DT_FMT.format(absStart))
                  .append(" - ")
                  .append(TIME_FMT.format(absEnd))
                  .append(" ")
                  .append(text.replace('\n', ' ').trim())
                  .append('\n');
                found = true;
            }

            if (!found) { statusCallback.onStatus("無音スキップ ← " + baseName); return; }

            String entry = sb.toString();
            Path logFile = AppConfig.LOG_DIR.resolve(DATE_FMT.format(t0) + ".txt");
            Files.writeString(logFile, entry,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            listener.onTranscript(entry, entry, t0);

        } catch (Exception e) {
            statusCallback.onStatus("処理エラー (" + baseName + "): " + e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
