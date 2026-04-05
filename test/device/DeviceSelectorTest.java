package device;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * DeviceSelector.buildDeviceList() の単体テスト（MT-02 対応）。
 *
 * Swing / AppConfig / nvidia-smi には依存せず、tmpDir 上のファイル有無のみで
 * デバイスリストの構築ロジックを検証する。
 */
class DeviceSelectorTest {

    @TempDir
    Path tmpDir;

    private void createDummyExe(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[0]);
    }

    // MT-02 (前半): CUDA バイナリが存在しない場合は CPU オプションのみ
    @Test
    void noCudaBinary_onlyCpuOptionReturned() throws Exception {
        createDummyExe(tmpDir.resolve("bin/cpu/whisper-cli.exe"));

        List<String[]> devices = DeviceSelector.buildDeviceList(tmpDir);

        assertEquals(1, devices.size(),
            "CUDA バイナリが存在しない場合はデバイスリストに CPU エントリのみ含まれるべき（MT-02）");
        assertTrue(devices.get(0)[0].startsWith("CPU"),
            "唯一のエントリは CPU であるべき");
    }

    // MT-02 (後半): CUDA バイナリが存在する場合は GPU エントリが追加される
    @Test
    void cudaBinaryPresent_gpuOptionAdded() throws Exception {
        createDummyExe(tmpDir.resolve("bin/cpu/whisper-cli.exe"));
        createDummyExe(tmpDir.resolve("bin/cuda/whisper-cli.exe"));

        List<String[]> devices = DeviceSelector.buildDeviceList(tmpDir);

        assertTrue(devices.size() >= 2,
            "CUDA バイナリが存在する場合は GPU エントリが 1 件以上追加されるべき（MT-02）");
        assertTrue(devices.get(0)[0].startsWith("CPU"),
            "先頭エントリは CPU であるべき");
        assertTrue(devices.stream().skip(1).allMatch(d -> d[0].startsWith("GPU")),
            "2 件目以降のエントリはすべて GPU であるべき");
    }
}
