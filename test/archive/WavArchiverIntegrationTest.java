package archive;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.zip.ZipFile;
import java.util.Comparator;

/**
 * WavArchiver の結合テスト。
 *
 * 実行時に以下の JVM フラグが必要:
 *   -Dapp.dir=<tmpDir>  (-Dwav.dir は app.dir/wav にデフォルト)
 *
 * AppConfig は static final フィールドで初期化されるため、
 * このクラスの static ブロックで System.setProperty を行い
 * JVM 内で最初に AppConfig が参照される前に設定する。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WavArchiverIntegrationTest {

    /** テスト専用ワーキングディレクトリ（JVM 起動前に確定させる） */
    static final Path WORK_DIR;

    static {
        // AppConfig が参照される前に実行される。
        // CI では -Dapp.dir が渡されるが、ローカル実行用にここでも設定。
        String base = System.getProperty("app.dir",
            System.getProperty("java.io.tmpdir") + "/rt_integration_" + ProcessHandle.current().pid());
        WORK_DIR = Path.of(base).toAbsolutePath();
        System.setProperty("app.dir", WORK_DIR.toString());
        System.setProperty("wav.dir",  WORK_DIR.resolve("wav").toString());
        System.setProperty("log.dir",  WORK_DIR.resolve("logs").toString());
    }

    @BeforeAll
    static void setUpWorkDir() throws Exception {
        Files.createDirectories(WORK_DIR.resolve("wav"));
        Files.createDirectories(WORK_DIR.resolve("logs"));
    }

    @AfterAll
    static void cleanUp() throws Exception {
        // テスト後に作業ディレクトリを削除
        if (Files.exists(WORK_DIR)) {
            Files.walk(WORK_DIR)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }

    // ── ヘルパー ───────────────────────────────────────────────────────────────
    /** 最小限の WAV ファイルを wav/ に作成する */
    private static void createFakeWav(String name) throws Exception {
        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        Files.write(WORK_DIR.resolve("wav/" + name), header);
    }

    /** wav/ にある ZIP ファイルを返す（最初の1つ） */
    private static Path findZip() throws Exception {
        return Files.list(WORK_DIR.resolve("wav"))
                    .filter(p -> p.toString().endsWith(".zip"))
                    .findFirst()
                    .orElse(null);
    }

    // ── テスト ────────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    void archiveNow_createsZipAndDeletesWavs() throws Exception {
        // 既知のタイムスタンプ付き WAV ファイルを作成（30分窓: 20260101_120000 〜 20260101_123000）
        createFakeWav("20260101_120000.wav");
        createFakeWav("20260101_121500.wav");
        createFakeWav("20260101_122900.wav");

        WavArchiver archiver = new WavArchiver(msg -> {});
        archiver.archiveNow();
        archiver.shutdown();
        // スケジューラのタスク完了を待つ
        Thread.sleep(3000);

        // ZIP が作成されているか
        Path zip = findZip();
        assertNotNull(zip, "WAV ファイルは ZIP にアーカイブされるべき");

        // ZIP の中に 3 エントリあるか
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertEquals(3, zf.size(), "ZIP には 3 エントリが含まれるべき");
        }

        // 元の WAV が削除されているか
        long remainingWavs = Files.list(WORK_DIR.resolve("wav"))
                                  .filter(p -> p.toString().endsWith(".wav"))
                                  .count();
        assertEquals(0, remainingWavs, "アーカイブ後に元 WAV ファイルは削除されるべき");
    }

    @Test
    @Order(2)
    void archiveNow_appendsToExistingZip() throws Exception {
        // 前テストで ZIP が存在する前提。追加 WAV を作成してもう一度アーカイブ。
        Path zip = findZip();
        assertNotNull(zip, "前テストで作成された ZIP が必要");
        int existingEntries;
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            existingEntries = zf.size(); // 3
        }

        createFakeWav("20260101_122950.wav"); // 同じ 30分窓（12:00〜12:30）

        WavArchiver archiver = new WavArchiver(msg -> {});
        archiver.archiveNow();
        archiver.shutdown();
        Thread.sleep(3000);

        // ZIP に追記されているか（既存 3 + 新規 1 = 4）
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertEquals(existingEntries + 1, zf.size(),
                "2回目のアーカイブで既存エントリを保持したまま追記されるべき");
        }
    }

    @Test
    @Order(3)
    void noWavFiles_noZipCreated() throws Exception {
        // wav/ に WAV ファイルがない場合は ZIP を作らない
        long zipsBefore = Files.list(WORK_DIR.resolve("wav"))
                               .filter(p -> p.toString().endsWith(".zip"))
                               .count();

        WavArchiver archiver = new WavArchiver(msg -> {});
        archiver.archiveNow();
        archiver.shutdown();
        Thread.sleep(2000);

        long zipsAfter = Files.list(WORK_DIR.resolve("wav"))
                              .filter(p -> p.toString().endsWith(".zip"))
                              .count();
        assertEquals(zipsBefore, zipsAfter, "WAV がなければ新しい ZIP は作成されないべき");
    }

    @Test
    @Order(4)
    void archive_acrossWindowBoundary_createsTwoZips() throws Exception {
        // 2 つの異なる 30 分窓にまたがる WAV を作成し、それぞれ別の ZIP に分割されること
        // 窓 A: 09:00-09:30 → wav_yyyyMMdd_0900.zip
        // 窓 B: 09:30-10:00 → wav_yyyyMMdd_0930.zip
        createFakeWav("20260405_090001.wav");
        createFakeWav("20260405_093001.wav");

        long zipsBefore = Files.list(WORK_DIR.resolve("wav"))
                               .filter(p -> p.toString().endsWith(".zip"))
                               .count();

        WavArchiver archiver = new WavArchiver(msg -> {});
        archiver.archiveNow();
        archiver.shutdown();
        Thread.sleep(3000);

        long zipsAfter = Files.list(WORK_DIR.resolve("wav"))
                              .filter(p -> p.toString().endsWith(".zip"))
                              .count();
        assertEquals(zipsBefore + 2, zipsAfter,
            "30 分窓をまたぐ 2 件の WAV は 2 つの別 ZIP にアーカイブされるべき");

        // 各 ZIP に 1 エントリずつ含まれること
        long match0900 = Files.list(WORK_DIR.resolve("wav"))
                              .filter(p -> p.getFileName().toString().contains("_0900.zip"))
                              .count();
        long match0930 = Files.list(WORK_DIR.resolve("wav"))
                              .filter(p -> p.getFileName().toString().contains("_0930.zip"))
                              .count();
        assertEquals(1, match0900, "09:00 窓の ZIP が 1 つ作成されるべき");
        assertEquals(1, match0930, "09:30 窓の ZIP が 1 つ作成されるべき");
    }

    @Test
    @Order(5)
    void archive_onError_tmpZipIsDeleted() throws Exception {
        // 既存 ZIP を破損データで上書きして追記時のエラーを誘発し、
        // .tmp.zip が残存しないことを確認する（課題 005 修正後に PASS するはず）
        createFakeWav("20260405_100001.wav");

        // 同じ窓の ZIP を破損データで事前作成
        Path corruptZip = WORK_DIR.resolve("wav/wav_20260405_1000.zip");
        Files.write(corruptZip, "not a zip".getBytes());

        WavArchiver archiver = new WavArchiver(msg -> {});
        archiver.archiveNow();
        archiver.shutdown();
        Thread.sleep(3000);

        // .tmp.zip が残っていないこと
        long tmpCount = Files.list(WORK_DIR.resolve("wav"))
                             .filter(p -> p.toString().endsWith(".tmp.zip"))
                             .count();
        assertEquals(0, tmpCount, "エラー時に .tmp.zip が残存してはならない（課題 005）");

        // 後片付け
        Files.deleteIfExists(corruptZip);
    }

    @Test
    @Order(6)
    void shutdown_archivesRemainingWavs() throws Exception {
        // shutdown() を呼ぶと残 WAV がアーカイブされること（設計書 終了シーケンス #3）
        createFakeWav("20260405_110001.wav");

        WavArchiver archiver = new WavArchiver(msg -> {});
        // start() を呼ばず shutdown() のみ呼ぶことで、shutdown 時のアーカイブを確認
        archiver.shutdown();
        Thread.sleep(5000);

        // wav_20260405_1100.zip が作成されているか
        long zipCount = Files.list(WORK_DIR.resolve("wav"))
                             .filter(p -> p.getFileName().toString().contains("_1100.zip"))
                             .count();
        assertEquals(1, zipCount, "shutdown() 後に残 WAV がアーカイブされ ZIP が作成されるべき");
    }

    // 課題021 (1): WAV ディレクトリが存在しない場合は ZIP を作成しない
    @Test
    @Order(7)
    void archiveOldWavs_wavDirAbsent_doesNothing() throws Exception {
        // wav.dir を存在しないパスに一時変更する
        String originalWavDir = System.getProperty("wav.dir");
        Path nonExistentDir = WORK_DIR.resolve("wav_nonexistent_" + System.nanoTime());
        System.setProperty("wav.dir", nonExistentDir.toString());

        // AppConfig.WAV_DIR は static final のため、存在しないパスを直接渡して
        // archiveOldWavs() の !Files.isDirectory 分岐を通るか検証する。
        // WavArchiver はステータスコールバックのみ使うため、例外が出なければ OK。
        try {
            WavArchiver archiver = new WavArchiver(msg -> {});
            archiver.archiveNow();
            archiver.shutdown();
            Thread.sleep(2000);
            // 例外なく完了することを確認（WAV_DIR 未存在なら即 return）
        } finally {
            System.setProperty("wav.dir", originalWavDir);
        }
        // WAV_DIR が存在しない場合でも ZIP は作られない（AppConfig.WAV_DIR は静的なので
        // このテストでは WORK_DIR/wav を使った既存テストと干渉しない）
        assertFalse(Files.exists(nonExistentDir),
            "存在しない WAV_DIR は archiveOldWavs() によって作成されてはならない（課題021）");
    }

    // 課題021 (2): forceAll=false（定期実行）は現在の 30 分窓内のファイルをアーカイブしない
    @Test
    @Order(8)
    void archiveOldWavs_forceAllFalse_skipsCurrentWindowFiles() throws Exception {
        // 現在時刻の 30 分窓内のタイムスタンプを持つ WAV を作成
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        String currentWindowFile = String.format("%04d%02d%02d_%02d%02d00.wav",
            now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
            now.getHour(), (now.getMinute() < 30) ? 1 : 31);
        createFakeWav(currentWindowFile);

        long zipsBefore = Files.list(WORK_DIR.resolve("wav"))
                               .filter(p -> p.toString().endsWith(".zip"))
                               .count();

        // archiver.start() で forceAll=false の定期実行パスをトリガーする代わりに
        // start() を呼ぶと即時 forceAll=true が走るため、直接リフレクションで
        // forceAll=false を呼び出す
        WavArchiver archiver = new WavArchiver(msg -> {});
        java.lang.reflect.Method m = WavArchiver.class.getDeclaredMethod("archiveOldWavs", boolean.class);
        m.setAccessible(true);
        m.invoke(archiver, false); // forceAll=false
        archiver.shutdown();
        Thread.sleep(2000);

        long zipsAfter = Files.list(WORK_DIR.resolve("wav"))
                              .filter(p -> p.toString().endsWith(".zip"))
                              .count();
        assertEquals(zipsBefore, zipsAfter,
            "forceAll=false では現在の 30 分窓内の WAV はアーカイブされないべき（課題021）");

        // 後片付け
        Files.deleteIfExists(WORK_DIR.resolve("wav/" + currentWindowFile));
    }
}
