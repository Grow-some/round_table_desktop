package archive;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import app.AppConfig;

/**
 * WAV_DIR 内の古い .wav ファイルを 30分ウィンドウごとの ZIP に圧縮して削除する。
 * 起動時に即実行し、以降は 30分境界（:00 / :30）に合わせて定期実行する。
 */
public class WavArchiver {

    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter ZIP_FMT  = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private final WavArchiver.StatusCallback statusCallback;
    private final ScheduledExecutorService   scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "archiver");
            t.setDaemon(true);
            return t;
        });

    @FunctionalInterface
    public interface StatusCallback {
        void onStatus(String message);
    }

    public WavArchiver(StatusCallback statusCallback) {
        this.statusCallback = statusCallback;
    }

    /** 起動時に即実行 + 次の :00/:30 から 30分間隔でスケジュール開始。 */
    public void start() {
        scheduler.submit(() -> archiveOldWavs(true)); // 起動時は全ファイル対象

        LocalDateTime now     = LocalDateTime.now();
        int           nextMin = (now.getMinute() < 30) ? 30 : 60;
        LocalDateTime next    = now.withSecond(0).withNano(0).withMinute(nextMin % 60);
        if (nextMin == 60) next = next.plusHours(1);
        long delaySec = java.time.Duration.between(now, next).getSeconds();
        scheduler.scheduleAtFixedRate(() -> archiveOldWavs(false), delaySec, 30 * 60L, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.submit(() -> archiveOldWavs(true));
        scheduler.shutdown();
    }

    /** 録音停止時など任意のタイミングで即時アーカイブを実行する（全WAV対象）。 */
    public void archiveNow() {
        scheduler.submit(() -> archiveOldWavs(true));
    }

    private void archiveOldWavs(boolean forceAll) {
        try {
            if (!Files.isDirectory(AppConfig.WAV_DIR)) return;

            // forceAll=true のときは全WAV対象。falseのときは現在の30分窓開始より前のみ。
            LocalDateTime boundary;
            if (forceAll) {
                boundary = LocalDateTime.now().plusSeconds(5);
            } else {
                LocalDateTime now = LocalDateTime.now();
                int floorMin = (now.getMinute() < 30) ? 0 : 30;
                boundary = now.withMinute(floorMin).withSecond(0).withNano(0);
            }

            Map<String, List<Path>> groups = new TreeMap<>();
            try (var stream = Files.list(AppConfig.WAV_DIR)) {
                stream.filter(p -> p.toString().endsWith(".wav"))
                      .forEach(p -> {
                          String name = p.getFileName().toString();
                          if (name.length() < 15) return;
                          try {
                              LocalDateTime ts = LocalDateTime.parse(name.substring(0, 15), FILE_FMT);
                              if (ts.isBefore(boundary)) {
                                  int wMin = (ts.getMinute() < 30) ? 0 : 30;
                                  LocalDateTime wKey = ts.withMinute(wMin).withSecond(0).withNano(0);
                                  groups.computeIfAbsent(ZIP_FMT.format(wKey), k -> new ArrayList<>()).add(p);
                              }
                          } catch (Exception ignored) {}
                      });
            }

            if (groups.isEmpty()) return;

            int total = 0;
            for (Map.Entry<String, List<Path>> entry : groups.entrySet()) {
                Path       zipPath = AppConfig.WAV_DIR.resolve("wav_" + entry.getKey() + ".zip");
                List<Path> wavs    = entry.getValue();

                // 既存 ZIP があれば既存エントリを保持したまま追記する
                Path tmpPath = AppConfig.WAV_DIR.resolve("wav_" + entry.getKey() + ".tmp.zip");
                try {
                    try (ZipOutputStream zos = new ZipOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(tmpPath)))) {
                        // 既存エントリをコピー
                        if (Files.exists(zipPath)) {
                            try (java.util.zip.ZipFile existing = new java.util.zip.ZipFile(zipPath.toFile())) {
                                java.util.Enumeration<? extends ZipEntry> es = existing.entries();
                                while (es.hasMoreElements()) {
                                    ZipEntry ze = es.nextElement();
                                    zos.putNextEntry(new ZipEntry(ze.getName()));
                                    try (InputStream is = existing.getInputStream(ze)) { is.transferTo(zos); }
                                    zos.closeEntry();
                                }
                            }
                        }
                        // 新しい WAV を追加
                        for (Path wav : wavs) {
                            zos.putNextEntry(new ZipEntry(wav.getFileName().toString()));
                            Files.copy(wav, zos);
                            zos.closeEntry();
                        }
                    }
                    // tmp → 本番 ZIP に置き換え
                    Files.deleteIfExists(zipPath);
                    Files.move(tmpPath, zipPath);

                    for (Path wav : wavs) {
                        try { Files.deleteIfExists(wav); } catch (Exception ignored) {}
                    }
                    total += wavs.size();
                } catch (Exception e) {
                    // 書き込み失敗時は .tmp.zip を削除して既存 ZIP を保全する (課題005)
                    try { Files.deleteIfExists(tmpPath); } catch (Exception ignored) {}
                    statusCallback.onStatus("アーカイブエラー (" + entry.getKey() + "): " + e.getMessage());
                }
            }
            statusCallback.onStatus("アーカイブ完了: " + total + " 件圧縮");

        } catch (Exception e) {
            statusCallback.onStatus("アーカイブエラー: " + e.getMessage());
        }
    }
}
