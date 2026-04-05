package ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.sound.sampled.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import app.AppConfig;
import device.DeviceSelector;
import recording.VadEngine;
import recording.PcmUtils;
import transcription.WhisperRunner;
import archive.WavArchiver;

/**
 * メインウィンドウ。UI の構築・録音トグル・VadEngine / WhisperRunner / WavArchiver の組み立てを担う。
 */
public class MainWindow {

    // ── UI ────────────────────────────────────────────────────────────────────
    private final JFrame              frame;
    private final JTextArea           textArea;
    private final JButton             btnToggle;
    private final JLabel              lblStatus;
    private final JLabel              lblVad;
    private final JComboBox<String>   cmbThreshold;
    private final JComboBox<String>   cmbGain;    private final JComboBox<String>   cmbSilence;    private final JComboBox<String>   cmbMic;
    private final JLabel              lblFooter;
    private final List<Mixer.Info>    micInfos = new ArrayList<>();

    // ── コンポーネント ────────────────────────────────────────────────────────
    private final WhisperRunner whisper;
    private final WavArchiver   archiver;
    private VadEngine           vadEngine;

    // ── 録音スレッド ──────────────────────────────────────────────────────────
    private volatile boolean running = false;
    private Thread recThread;

    // ── 音量統計 ──────────────────────────────────────────────────────────────
    private final List<Double> rawRmsList = Collections.synchronizedList(new ArrayList<>());

    public MainWindow() {
        // ── UI 部品を先に初期化 ───────────────────────────────────────────────
        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);

        // ── WhisperRunner / WavArchiver の初期化 ──────────────────────────────
        whisper = new WhisperRunner(
            (entry, logLine, date) -> SwingUtilities.invokeLater(() -> {
                textArea.append(entry);
                textArea.setCaretPosition(textArea.getDocument().getLength());
            }),
            this::setStatus,
            n -> SwingUtilities.invokeLater(() -> {
                if (running) {
                    setStatus(n > 0
                        ? "録音中...（文字起こし待機: " + n + "件）"
                        : "録音中...");
                } else if (n > 0) {
                    setStatus("文字起こし完了待ち... " + n + "件残り");
                }
            })
        );
        archiver = new WavArchiver(this::setStatus);

        // ── 残りの UI 部品 ────────────────────────────────────────────────────

        btnToggle = new JButton("▶ 録音開始");
        btnToggle.setFont(btnToggle.getFont().deriveFont(Font.BOLD, 14f));
        btnToggle.addActionListener(e -> toggle());

        cmbThreshold = new JComboBox<>(new String[]{"100", "150", "200", "300", "500"});
        cmbThreshold.setSelectedItem("300");
        cmbThreshold.setToolTipText("VAD感度: 低い値ほど小さい音も拾う");

        cmbGain = new JComboBox<>(new String[]{"1.0x", "1.5x", "2.0x", "2.5x", "3.0x", "4.0x", "5.0x"});
        cmbGain.setSelectedItem("2.0x");
        cmbGain.setToolTipText("マイクゲイン: 受音量を増幅");

        cmbSilence = new JComboBox<>(new String[]{"500ms", "800ms", "1200ms", "2000ms", "3000ms"});
        cmbSilence.setSelectedItem("1200ms");
        cmbSilence.setToolTipText("終話判定無音時間: 短いと早めに発話終了と判定");

        cmbThreshold.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && vadEngine != null)
                vadEngine.setThreshold(thresholdValue());
        });
        cmbGain.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && vadEngine != null)
                vadEngine.setGain(gainValue());
        });
        cmbSilence.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && vadEngine != null)
                vadEngine.setSilenceMs(silenceValue());
        });

        // マイクデバイス一覧を列挙
        cmbMic = new JComboBox<>();
        cmbMic.setToolTipText("使用するマイクデバイスを選択");
        refreshMicList(null);

        JButton btnRefreshMic = new JButton("↺");
        btnRefreshMic.setToolTipText("マイクリストを更新");

        lblVad = new JLabel("●");
        lblVad.setFont(lblVad.getFont().deriveFont(Font.BOLD, 18f));
        lblVad.setForeground(Color.GRAY);
        lblVad.setToolTipText("赤=発話検出中 / オレンジ=クリッピング(音割れ)");

        lblStatus = new JLabel("停止中");
        lblStatus.setFont(lblStatus.getFont().deriveFont(13f));

        JButton btnLog    = new JButton("ログ");
        btnLog.addActionListener(e -> openDir(AppConfig.LOG_DIR));
        JButton btnWav    = new JButton("WAV");
        btnWav.addActionListener(e -> openDir(AppConfig.WAV_DIR));
        JButton btnClear  = new JButton("クリア");
        btnClear.addActionListener(e -> textArea.setText(""));
        JButton btnDevice = new JButton("デバイス変更");
        btnDevice.setToolTipText("録音停止中にデバイスを切り替える");

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        top.add(btnToggle);
        top.add(lblVad);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(new JLabel("VAD感度:"));
        top.add(cmbThreshold);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(new JLabel("終話無音:"));
        top.add(cmbSilence);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(new JLabel("ゲイン:"));
        top.add(cmbGain);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(new JLabel("マイク:"));
        top.add(cmbMic);
        top.add(btnRefreshMic);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(btnLog);
        top.add(btnWav);
        top.add(btnClear);
        top.add(btnDevice);
        top.add(Box.createHorizontalStrut(8));
        top.add(lblStatus);

        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setBorder(BorderFactory.createTitledBorder(
            "文字起こし結果 — yyyy-MM-dd HH:mm:ss - HH:mm:ss テキスト"
        ));

        lblFooter = new JLabel(
            "  model: whisper.cpp large-v3  |  vad: silero-v6.2.0  |  device: "
            + AppConfig.getDeviceLabel()
        );
        lblFooter.setFont(lblFooter.getFont().deriveFont(11f));
        lblFooter.setForeground(Color.GRAY);

        frame = new JFrame("ASR App  [whisper.cpp large-v3 / " + AppConfig.getDeviceLabel() + "]");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                whisper.shutdown();
                archiver.shutdown();
                frame.dispose();
            }
        });
        frame.setSize(1100, 680);
        frame.setLocationRelativeTo(null);
        frame.add(top,      BorderLayout.NORTH);
        frame.add(scroll,   BorderLayout.CENTER);
        frame.add(lblFooter, BorderLayout.SOUTH);
        frame.setVisible(true);

        // frame / lblFooter / btnRefreshMic 確定後にリスナー登録
        btnRefreshMic.addActionListener(e -> {
            if (!running) refreshMicList((String) cmbMic.getSelectedItem());
        });
        btnDevice.addActionListener(e -> {
            if (running) {
                JOptionPane.showMessageDialog(frame,
                    "録音中は変更できません。先に録音を停止してください。",
                    "デバイス変更", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (DeviceSelector.show(frame)) {
                String lbl = AppConfig.getDeviceLabel();
                frame.setTitle("ASR App  [whisper.cpp large-v3 / " + lbl + "]");
                lblFooter.setText("  model: whisper.cpp large-v3  |  vad: silero-v6.2.0  |  device: " + lbl);
            }
        });

        archiver.start();
    }

    // ── 録音トグル ─────────────────────────────────────────────────────────────
    private void toggle() {
        if (!running) {
            running = true;
            btnToggle.setText("■ 停止");
            cmbThreshold.setEnabled(false);
            cmbGain.setEnabled(false);
            cmbSilence.setEnabled(false);
            cmbMic.setEnabled(false);
            setStatus("録音中...");
            final int micIdx = cmbMic.getSelectedIndex(); // EDT でキャプチャ (課題001)
            recThread = new Thread(() -> recordLoop(micIdx), "record");
            recThread.setDaemon(true);
            recThread.start();
        } else {
            running = false;
            btnToggle.setText("▶ 録音開始");
            btnToggle.setEnabled(false); // stop-helper 完了まで再起動不可
            setVadColor(false);
            setStatus("停止処理中...");
            // stop-helper: recThread.join() で flush() 完了を同期保証してからポーリング開始
            Thread stopHelper = new Thread(() -> {
                try {
                    if (recThread != null) recThread.join();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                SwingUtilities.invokeLater(this::startPendingCountPolling);
            }, "stop-helper");
            stopHelper.setDaemon(true);
            stopHelper.start();
        }
    }

    /** flush 完了後に EDT 上でポーリングを開始し、pendingCount が 0 になったら後片付けする。 */
    private void startPendingCountPolling() {
        int initial = whisper.getPendingCount();
        if (initial > 0) {
            setStatus("文字起こし完了待ち... " + initial + "件残り");
            javax.swing.Timer t = new javax.swing.Timer(500, null);
            t.addActionListener(ev -> {
                int n = whisper.getPendingCount();
                if (n > 0) {
                    setStatus("文字起こし完了待ち... " + n + "件残り");
                } else {
                    ((javax.swing.Timer) ev.getSource()).stop();
                    finishStop();
                }
            });
            t.start();
        } else {
            finishStop();
        }
    }

    /** 文字起こし完了待ち後に実行する最終後片付け処理。 */
    private void finishStop() {
        saveStatistics();
        archiver.archiveNow();
        cmbThreshold.setEnabled(true);
        cmbGain.setEnabled(true);
        cmbSilence.setEnabled(true);
        cmbMic.setEnabled(true);
        btnToggle.setEnabled(true);
        setStatus("停止中");
    }

    // ── 録音ループ ─────────────────────────────────────────────────────────────
    private void recordLoop(int micIdx) {
        AudioFormat fmt = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            PcmUtils.SAMPLE_RATE, PcmUtils.BITS, PcmUtils.CHANNELS,
            PcmUtils.CHANNELS * PcmUtils.BYTES_PER_SAMPLE,
            PcmUtils.SAMPLE_RATE, false
        );
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        if (!AudioSystem.isLineSupported(info)) {
            err("16 kHz mono マイクが使えません。");
            SwingUtilities.invokeLater(this::toggle);
            return;
        }
        TargetDataLine mic;
        try {
            int idx = micIdx; // EDT でキャプチャ済みのインデックスを使用 (課題001)
            if (!micInfos.isEmpty() && idx >= 0 && idx < micInfos.size()) {
                Mixer mixer = AudioSystem.getMixer(micInfos.get(idx));
                mic = (TargetDataLine) mixer.getLine(info);
            } else {
                mic = (TargetDataLine) AudioSystem.getLine(info);
            }
            mic.open(fmt);
        } catch (LineUnavailableException ex) {
            err("マイクを開けません: " + ex.getMessage());
            SwingUtilities.invokeLater(this::toggle);
            return;
        }
        mic.start();

        vadEngine = new VadEngine(
            (pcm, t0, t1) -> whisper.submit(pcm, t0, t1),
            lvl -> {
                rawRmsList.add(lvl.rawRms());
                setVadColor(lvl.isSpeech());
                setClipIndicator(lvl.isClipping());
            }
        );
        vadEngine.setThreshold(thresholdValue());
        vadEngine.setGain(gainValue());
        vadEngine.setSilenceMs(silenceValue());

        byte[] frame = new byte[PcmUtils.FRAME_BYTES];
        try {
            while (running) {
                int read = 0;
                while (read < PcmUtils.FRAME_BYTES) {
                    int n = mic.read(frame, read, PcmUtils.FRAME_BYTES - read);
                    if (n > 0) read += n;
                }
                vadEngine.feed(frame, read);
            }
        } finally {
            mic.stop();
            mic.close();
            vadEngine.flush();
            vadEngine.reset();
        }
    }

    // ── UI ヘルパー ────────────────────────────────────────────────────────────
    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> lblStatus.setText(text));
    }

    private void setVadColor(boolean speech) {
        SwingUtilities.invokeLater(() ->
            lblVad.setForeground(speech ? Color.RED : Color.GRAY));
    }

    private void setClipIndicator(boolean clipping) {
        if (clipping) SwingUtilities.invokeLater(() -> lblVad.setForeground(Color.ORANGE));
    }

    private void err(String msg) {
        SwingUtilities.invokeLater(() ->
            JOptionPane.showMessageDialog(frame, msg, "エラー", JOptionPane.ERROR_MESSAGE));
    }

    private void saveStatistics() {
        List<Double> snapshot;
        synchronized (rawRmsList) {
            if (rawRmsList.isEmpty()) return;
            snapshot = new ArrayList<>(rawRmsList);
            rawRmsList.clear();
        }
        try {
            StatisticsWriter.write(AppConfig.LOG_DIR, snapshot);
        } catch (Exception e) {
            setStatus("統計保存エラー: " + e.getMessage());
        }
    }

    /** マイクデバイス一覧を再列挙してコンボを更新する。prevSelection は選択維持用（null可）。 */
    private void refreshMicList(String prevSelection) {
        AudioFormat   fmt      = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            PcmUtils.SAMPLE_RATE, PcmUtils.BITS, PcmUtils.CHANNELS,
            PcmUtils.CHANNELS * PcmUtils.BYTES_PER_SAMPLE, PcmUtils.SAMPLE_RATE, false);
        DataLine.Info lineInfo = new DataLine.Info(TargetDataLine.class, fmt);
        micInfos.clear();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            try {
                if (AudioSystem.getMixer(mi).isLineSupported(lineInfo)) micInfos.add(mi);
            } catch (Exception ignored) {}
        }
        cmbMic.removeAllItems();
        if (micInfos.isEmpty()) {
            cmbMic.addItem("(デフォルト)");
        } else {
            for (Mixer.Info mi : micInfos) cmbMic.addItem(mi.getName());
            if (prevSelection != null) {
                for (int i = 0; i < cmbMic.getItemCount(); i++) {
                    if (cmbMic.getItemAt(i).equals(prevSelection)) { cmbMic.setSelectedIndex(i); break; }
                }
            }
        }
    }

    private void openDir(Path dir) {
        try { Files.createDirectories(dir); Desktop.getDesktop().open(dir.toFile()); }
        catch (Exception e) { err("フォルダを開けません: " + e.getMessage()); }
    }

    private int thresholdValue() {
        return Integer.parseInt((String) cmbThreshold.getSelectedItem());
    }

    private double gainValue() {
        String s = (String) cmbGain.getSelectedItem();
        return Double.parseDouble(s.replace("x", ""));
    }

    private int silenceValue() {
        String s = (String) cmbSilence.getSelectedItem();
        return Integer.parseInt(s.replace("ms", ""));
    }
}
