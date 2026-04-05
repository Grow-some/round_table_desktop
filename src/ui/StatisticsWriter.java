package ui;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 音量統計を statistics.json に書き出すユーティリティ（MT-21 対応）。
 * MainWindow.saveStatistics() から抽出したロジックを保持する。
 */
public final class StatisticsWriter {

    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private StatisticsWriter() {}

    /**
     * 音量 RMS サンプルリストを statistics.json に追記する。
     *
     * @param logDir  ログ保存先ディレクトリ
     * @param samples rawRms サンプル列（空の場合は何もしない）
     * @throws Exception ファイル書き込みに失敗した場合
     */
    public static void write(Path logDir, List<Double> samples) throws Exception {
        if (samples.isEmpty()) return;

        double max    = samples.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double mean   = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double median = (sorted.length % 2 == 0)
                ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0
                : sorted[sorted.length / 2];

        String key   = LocalDateTime.now().format(DT_FMT);
        String entry = String.format(
            "  \"%s\": {%n    \"samples\": %d,%n" +
            "    \"rawRms_max\": %.2f,%n    \"rawRms_mean\": %.2f,%n    \"rawRms_median\": %.2f%n  }",
            key, samples.size(), max, mean, median
        );

        Path path = logDir.resolve("statistics.json");
        Files.createDirectories(logDir);
        String existing = Files.exists(path) ? Files.readString(path).strip() : "";
        String newContent;
        if (existing.isEmpty() || !existing.startsWith("{") || !existing.endsWith("}")) {
            newContent = "{\n" + entry + "\n}\n";
        } else {
            String inner  = existing.substring(1, existing.lastIndexOf('}')).strip();
            String prefix = existing.substring(0, existing.lastIndexOf('}')).stripTrailing();
            newContent = prefix + (inner.isEmpty() ? "" : ",") + "\n" + entry + "\n}\n";
        }
        Path tmp = path.resolveSibling("statistics.json.tmp");
        Files.writeString(tmp, newContent,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, path,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
