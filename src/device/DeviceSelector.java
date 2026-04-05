package device;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import app.AppConfig;

/**
 * 起動時にデバイス（CPU / GPU）を選択するダイアログ。
 * 選択結果は AppConfig.setDevice() に反映される。
 */
public class DeviceSelector {

    private DeviceSelector() {}

    /**
     * ダイアログを表示し、選択されたデバイスを AppConfig に設定する。
     * 再呼び出し対応。現在の設定があれば初期選択に反映する。
     * @param parentComponent 親ウィンドウ（null 可）
     * @return true=選択済み / false=キャンセル
     */
    public static boolean show(Component parentComponent) {
        List<String[]> devices = buildDeviceList(AppConfig.APP_DIR);
        String[] labels = devices.stream().map(d -> d[0]).toArray(String[]::new);

        // デフォルトは CPU (index 0)。再呼び出し時は現在の設定を維持する
        int defaultIdx = 0;
        try {
            String currentCli = AppConfig.getWhisperCli();
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i)[1].equals(currentCli)) { defaultIdx = i; break; }
            }
        } catch (IllegalStateException ignored) {}

        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JLabel title = new JLabel("起動するデバイスを選択してください:");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(title, BorderLayout.NORTH);

        JList<String> list = new JList<>(labels);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(defaultIdx);
        list.setFont(list.getFont().deriveFont(13f));
        list.setVisibleRowCount(Math.min(labels.length + 1, 8));
        panel.add(new JScrollPane(list), BorderLayout.CENTER);

        // ダブルクリックで確定
        int[] dblClick = {JOptionPane.CANCEL_OPTION};
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    dblClick[0] = JOptionPane.OK_OPTION;
                    SwingUtilities.getWindowAncestor(list).dispose();
                }
            }
        });

        int option = JOptionPane.showConfirmDialog(
            parentComponent, panel, "ASR App — デバイス選択",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (option != JOptionPane.OK_OPTION && dblClick[0] != JOptionPane.OK_OPTION) {
            return false;
        }

        int idx = list.getSelectedIndex();
        if (idx < 0) idx = 0;
        String[] chosen = devices.get(idx);
        AppConfig.setDevice(chosen[1], chosen[0].startsWith("GPU") ? "GPU" : "CPU");
        return true;
    }

    private static List<String[]> buildDeviceList() {
        return buildDeviceList(AppConfig.APP_DIR);
    }

    static List<String[]> buildDeviceList(Path appDir) {
        List<String[]> devices = new ArrayList<>();

        String cpuCli = appDir.resolve("bin/cpu/whisper-cli.exe").toString();
        devices.add(new String[]{"CPU (常に利用可能)", cpuCli});

        Path cudaCli = appDir.resolve("bin/cuda/whisper-cli.exe");
        if (Files.exists(cudaCli)) {
            List<String> gpuNames = detectGpus();
            if (!gpuNames.isEmpty()) {
                for (String name : gpuNames) {
                    devices.add(new String[]{"GPU: " + name, cudaCli.toString()});
                }
            } else {
                devices.add(new String[]{"GPU (CUDA) ※ドライバ要確認", cudaCli.toString()});
            }
        }
        return devices;
    }

    /** nvidia-smi で GPU 名一覧を取得する。失敗時は空リストを返す。 */
    private static List<String> detectGpus() {
        List<String> names = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(
                "nvidia-smi", "--query-gpu=name", "--format=csv,noheader")
                .redirectErrorStream(true).start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                br.lines().map(String::trim).filter(s -> !s.isEmpty()).forEach(names::add);
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return names;
    }
}
