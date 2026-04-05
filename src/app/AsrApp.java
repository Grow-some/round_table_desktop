package app;

import javax.swing.UIManager;
import javax.swing.SwingUtilities;
import device.DeviceSelector;
import ui.MainWindow;

/**
 * エントリポイント。
 * 1. Look&Feel を設定
 * 2. DeviceSelector でデバイスを選択
 * 3. MainWindow を起動
 */
public class AsrApp {
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        if (!DeviceSelector.show(null)) return; // キャンセル時は終了

        SwingUtilities.invokeLater(MainWindow::new);
    }
}
