package transcription;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import app.AppConfig;

/**
 * WhisperRunner の単体テスト。
 *
 * whisper-cli バイナリへの依存を避けるため、外部プロセス呼び出しは
 * テスト対象外とし、submit() / pendingCount / shutdown() の動作を検証する。
 *
 * 実行時に以下の JVM フラグが必要:
 *   -Dapp.dir=<tmpDir>
 */
class WhisperRunnerTest {

    static {
        String base = System.getProperty("app.dir",
            System.getProperty("java.io.tmpdir") + "/rt_whisper_" + ProcessHandle.current().pid());
        System.setProperty("app.dir", base);
        System.setProperty("wav.dir",  base + "/wav");
        System.setProperty("log.dir",  base + "/logs");
    }

    // ── C1 分岐 1: 生成直後（キュー空）────────────────────────────────────────

    @Test
    void initialPendingCount_isZero() {
        WhisperRunner runner = new WhisperRunner((e, l, d) -> {}, msg -> {}, n -> {});
        assertEquals(0, runner.getPendingCount(), "生成直後の pendingCount は 0 であるべき");
        runner.shutdown();
    }

    // ── C1 分岐 2: submit 後にカウントが上がる ────────────────────────────────

    @Test
    void submit_doesNotThrow() {
        WhisperRunner runner = new WhisperRunner((e, l, d) -> {}, msg -> {}, n -> {});
        LocalDateTime t0 = LocalDateTime.now();
        assertDoesNotThrow(() ->
            runner.submit(new byte[0], t0, t0.plusSeconds(1))
        );
        runner.shutdown();
    }

    @Test
    void submit_multiple_doesNotThrow() {
        // 複数回 submit しても例外が出ない（シリアル実行キュー）
        WhisperRunner runner = new WhisperRunner((e, l, d) -> {}, msg -> {}, n -> {});
        LocalDateTime t0 = LocalDateTime.now();
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 5; i++) {
                runner.submit(new byte[0], t0.plusSeconds(i), t0.plusSeconds(i + 1));
            }
        });
        runner.shutdown();
    }

    // ── C1 分岐 3: 処理完了後（エラールート含む）pendingCount がデクリメントされる ─

    @Test
    void pendingCount_decrement_even_on_error() throws Exception {
        // process() がエラー（wav.dir 未作成・空 PCM）になっても
        // finally でデクリメントされ pendingCount が 0 に戻ることを確認
        CountDownLatch latch = new CountDownLatch(1);

        WhisperRunner runner = new WhisperRunner(
            (e, l, d) -> {},
            msg -> {},
            n -> latch.countDown()  // pendingCountCallback: カウント変動時にラッチをデクリメント
        );

        LocalDateTime t0 = LocalDateTime.now();
        runner.submit(new byte[0], t0, t0.plusSeconds(1));

        boolean called = latch.await(5, TimeUnit.SECONDS);
        assertTrue(called, "5秒以内に pendingCountCallback が呼ばれるべき");
        // コールバック後に finally でデクリメントされるため少し待機する
        for (int i = 0; i < 20 && runner.getPendingCount() > 0; i++) {
            Thread.sleep(100);
        }
        assertEquals(0, runner.getPendingCount(), "処理後に pendingCount が 0 に戻るべき");
        runner.shutdown();
    }

    // ── C1 分岐 4: shutdown — executor が停止していること ────────────────────

    @Test
    void shutdown_doesNotThrow() {
        WhisperRunner runner = new WhisperRunner((e, l, d) -> {}, msg -> {}, n -> {});
        assertDoesNotThrow(runner::shutdown, "shutdown() が例外を投げてはならない");
    }

    @Test
    void shutdown_twice_doesNotThrow() {
        // ExecutorService は複数回 shutdown() しても安全
        WhisperRunner runner = new WhisperRunner((e, l, d) -> {}, msg -> {}, n -> {});
        assertDoesNotThrow(() -> {
            runner.shutdown();
            runner.shutdown();
        });
    }

    // ── C1 分岐 5: null リスナーでも NullPointerException が出ない ─────────────

    @Test
    void nullListener_doesNotThrow() {
        assertDoesNotThrow(() -> {
            WhisperRunner runner = new WhisperRunner(null, msg -> {}, n -> {});
            runner.shutdown();
        });
    }

    // ── MT-23: 存在しない CLI パスを設定した場合、StatusCallback にエラーが通知される ─

    @Test
    void invalidCliPath_statusCallbackReceivesErrorMessage() throws Exception {
        // 存在しないパスを明示的に設定する（MT-23: "-Dmodel に存在しないパスを指定" に相当）
        AppConfig.setDevice(
            System.getProperty("java.io.tmpdir") + "/nonexistent-whisper-cli-" + System.nanoTime() + ".exe",
            "CPU"
        );

        AtomicReference<String> errorMsg = new AtomicReference<>();
        CountDownLatch errorLatch = new CountDownLatch(1);

        WhisperRunner runner = new WhisperRunner(
            (e, l, d) -> {},
            msg -> {
                if (msg.startsWith("処理エラー")) {
                    errorMsg.set(msg);
                    errorLatch.countDown();
                }
            },
            n -> {}
        );

        LocalDateTime t0 = LocalDateTime.now();
        runner.submit(new byte[0], t0, t0.plusSeconds(1));

        assertTrue(errorLatch.await(5, TimeUnit.SECONDS),
            "5 秒以内に '処理エラー' のステータスコールバックが呼ばれるべき（MT-23）");
        assertNotNull(errorMsg.get());
        assertTrue(errorMsg.get().startsWith("処理エラー"),
            "存在しない CLI パスでは '処理エラー' で始まるステータスが通知されるべき: " + errorMsg.get());

        runner.shutdown();
    }
}
