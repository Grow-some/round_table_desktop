package ui;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;

/**
 * StatisticsWriter の単体テスト（MT-21 対応）。
 *
 * AppConfig / Swing に依存せず、tmpDir を使ったファイル I/O のみで検証する。
 */
class StatisticsWriterTest {

    @TempDir
    Path tmpDir;

    // MT-21 (基本): write() が statistics.json を正しいフォーマットで作成する
    @Test
    void write_createsFile_withCorrectJsonStructure() throws Exception {
        List<Double> samples = Arrays.asList(100.0, 200.0, 300.0);
        StatisticsWriter.write(tmpDir, samples);

        Path json = tmpDir.resolve("statistics.json");
        assertTrue(Files.exists(json), "statistics.json が作成されるべき（MT-21）");

        String content = Files.readString(json);
        assertTrue(content.startsWith("{"),       "JSON は '{' で始まるべき");
        assertTrue(content.endsWith("}\n"),       "JSON は '}\\n' で終わるべき");
        assertTrue(content.contains("\"samples\": 3"),   "samples カウントが 3 であるべき");
        assertTrue(content.contains("\"rawRms_max\""),   "rawRms_max フィールドが含まれるべき");
        assertTrue(content.contains("\"rawRms_mean\""),  "rawRms_mean フィールドが含まれるべき");
        assertTrue(content.contains("\"rawRms_median\""),"rawRms_median フィールドが含まれるべき");
    }

    // MT-21 (追記): 2 回 write() を呼ぶと 2 エントリが statistics.json に存在する
    @Test
    void write_twice_appendsTwoEntries() throws Exception {
        StatisticsWriter.write(tmpDir, Arrays.asList(100.0, 200.0));
        StatisticsWriter.write(tmpDir, Arrays.asList(300.0, 400.0));

        String content = Files.readString(tmpDir.resolve("statistics.json"));
        long sampleCount = content.lines()
                .filter(l -> l.contains("\"samples\""))
                .count();
        assertEquals(2, sampleCount,
            "2 回 write() を呼ぶと 2 つの samples エントリが出力されるべき（MT-21）");
    }

    // 空リストでは何もしない
    @Test
    void write_emptyList_doesNotCreateFile() throws Exception {
        StatisticsWriter.write(tmpDir, Collections.emptyList());
        assertFalse(Files.exists(tmpDir.resolve("statistics.json")),
            "空リストの場合は statistics.json を作成しないべき");
    }

    // 最大・平均・中央値の算出が正しい
    @Test
    void write_statisticsValues_areCorrect() throws Exception {
        // samples = [1.0, 2.0, 3.0] → max=3, mean=2, median=2
        StatisticsWriter.write(tmpDir, Arrays.asList(1.0, 2.0, 3.0));

        String content = Files.readString(tmpDir.resolve("statistics.json"));
        assertTrue(content.contains("\"rawRms_max\": 3.00"),    "max は 3.00 であるべき");
        assertTrue(content.contains("\"rawRms_mean\": 2.00"),   "mean は 2.00 であるべき");
        assertTrue(content.contains("\"rawRms_median\": 2.00"), "median は 2.00 であるべき");
    }

    // 課題019 (1): 偶数サンプル時の中央値は隣接2値の平均
    @Test
    void write_evenSamples_medianIsAverageOfMiddleTwo() throws Exception {
        // samples = [1.0, 2.0, 3.0, 4.0] → median = (2.0 + 3.0) / 2 = 2.50
        StatisticsWriter.write(tmpDir, Arrays.asList(1.0, 2.0, 3.0, 4.0));

        String content = Files.readString(tmpDir.resolve("statistics.json"));
        assertTrue(content.contains("\"rawRms_median\": 2.50"),
            "偶数サンプル時の中央値は隣接2値の平均（2.50）であるべき（課題019）");
    }

    // 課題019 (2): 既存の statistics.json が破損している場合は上書きされる
    @Test
    void write_corruptExistingJson_overwritesWithValidJson() throws Exception {
        // 不正な JSON を事前に書き込む
        Path json = tmpDir.resolve("statistics.json");
        Files.writeString(json, "CORRUPTED DATA");

        StatisticsWriter.write(tmpDir, Arrays.asList(10.0, 20.0));

        String content = Files.readString(json);
        assertTrue(content.startsWith("{"),  "破損 JSON は有効な JSON で上書きされるべき（課題019）");
        assertTrue(content.endsWith("}\n"), "有効な JSON は '}\\n' で終わるべき");
        assertTrue(content.contains("\"samples\": 2"), "samples カウントが 2 であるべき");
    }
}
