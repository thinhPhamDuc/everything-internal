package com.app.internal.sync.client;

import com.app.internal.sync.config.SyncProviderProperties;
import com.app.internal.sync.mock.dto.MockFlightDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Test THẬT (không mock RestClient bằng Mockito deep-stub, dễ giả tạo sai
// hành vi timeout/concurrency/retry) - dựng 1 HttpServer JDK thật (có sẵn,
// không cần thêm dependency) mô phỏng đúng các kịch bản Task 9: OK, TREO
// (sleep vượt ngưỡng timeout - KHÔNG được retry), DIE liên tục (hết lượt
// retry vẫn lỗi - bị bỏ qua), và FLAKY (lỗi vài lần đầu rồi thành công - phải
// được retry cứu lại).
class ThirdPartyFlightFetchServiceTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger deadCallCount = new AtomicInteger(0);
    private static final AtomicInteger flakyCallCount = new AtomicInteger(0);
    private static final AtomicInteger resetCallCount = new AtomicInteger(0);

    private ExecutorService fetchExecutor;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/ok", exchange -> respondJson(exchange, sampleFlightsJson()));
        server.createContext("/slow", exchange -> {
            sleepQuietly(3_000); // vượt xa ngưỡng timeout test (1s) - giả lập TREO
            respondJson(exchange, sampleFlightsJson());
        });
        server.createContext("/dead", exchange -> {
            deadCallCount.incrementAndGet();
            exchange.sendResponseHeaders(503, -1); // luôn die - dùng để test "hết lượt retry thì bỏ qua"
        });
        server.createContext("/flaky", exchange -> {
            // 2 lần đầu die, từ lần thứ 3 trở đi thành công - dùng để test
            // "retry cứu được provider lỗi tạm thời".
            if (flakyCallCount.incrementAndGet() <= 2) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                respondJson(exchange, sampleFlightsJson());
            }
        });
        server.createContext("/reset", exchange -> {
            // Đóng kết nối NGAY, không gửi response header nào - client nhận
            // lỗi I/O (ResourceAccessException), KHÔNG PHẢI 1 HTTP response
            // có status code (khác /dead) - dùng để test "lỗi I/O không được
            // retry" (bug đã bắt được khi chạy live với provider-b thật).
            resetCallCount.incrementAndGet();
            exchange.close();
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        fetchExecutor = Executors.newFixedThreadPool(3, daemonThreadFactory());
        deadCallCount.set(0);
        flakyCallCount.set(0);
        resetCallCount.set(0);
    }

    @AfterEach
    void tearDown() {
        // shutdownNow() không hủy được lời gọi HTTP đang chặn thật sự bên
        // trong (giới hạn đã biết của CompletableFuture/blocking I/O, xem
        // RestClientConfig) - nhưng vì thread factory đặt daemon=true, JVM
        // vẫn thoát bình thường mà không phải đợi request "/slow" trả lời.
        fetchExecutor.shutdownNow();
    }

    @Test
    void fetchAll_boQuaProviderTreoVaProviderDie_vanTraVeProviderThanhCongVaChaySongSong() {
        // maxAttempts=1 (không retry) - test này chỉ tập trung xác nhận
        // concurrency/timeout, phần retry có 2 test riêng bên dưới.
        SyncProviderProperties properties = new SyncProviderProperties(
                List.of(
                        new SyncProviderProperties.Provider("OK", "http://localhost:" + port + "/ok"),
                        new SyncProviderProperties.Provider("SLOW", "http://localhost:" + port + "/slow"),
                        new SyncProviderProperties.Provider("DEAD", "http://localhost:" + port + "/dead")),
                1L, 1, 0L); // timeout 1s - đủ ngắn để chắc chắn cắt "SLOW" (sleep 3s)

        ThirdPartyFlightFetchService service =
                new ThirdPartyFlightFetchService(RestClient.create(), properties, fetchExecutor);

        long start = System.currentTimeMillis();
        List<ProviderFetchResult> results = service.fetchAll();
        long elapsedMs = System.currentTimeMillis() - start;

        assertEquals(3, results.size());
        assertEquals(1, results.stream().filter(ProviderFetchResult::success).count());
        assertTrue(results.stream().anyMatch(r -> r.providerName().equals("OK") && r.success()));
        assertTrue(results.stream().anyMatch(r -> r.providerName().equals("SLOW") && !r.success()));
        assertTrue(results.stream().anyMatch(r -> r.providerName().equals("DEAD") && !r.success()));

        // Chạy tuần tự sẽ mất >= 3s (do "SLOW" sleep 3s) - chạy song song với
        // orTimeout=1s thì tổng thời gian phải gần 1s, KHÔNG được cộng dồn.
        assertTrue(elapsedMs < 2_500,
                "fetchAll mất " + elapsedMs + "ms - có vẻ đang chạy TUẦN TỰ thay vì SONG SONG");
    }

    @Test
    void fetchAll_providerLoiTamThoi_retryCuuDuocThanhCong() {
        SyncProviderProperties properties = new SyncProviderProperties(
                List.of(new SyncProviderProperties.Provider("FLAKY", "http://localhost:" + port + "/flaky")),
                4L, 3, 50L); // 3 lần thử, cách nhau 50ms - "flaky" die đúng 2 lần đầu nên lần 3 phải qua

        ThirdPartyFlightFetchService service =
                new ThirdPartyFlightFetchService(RestClient.create(), properties, fetchExecutor);

        List<ProviderFetchResult> results = service.fetchAll();

        assertEquals(1, results.size());
        assertTrue(results.get(0).success(), "Phải thành công nhờ retry, dù 2 lần đầu die");
        assertEquals(3, flakyCallCount.get(), "Phải gọi đúng 3 lần (2 lần lỗi + 1 lần thành công)");
    }

    @Test
    void fetchAll_providerLuonDie_hetLuotRetryThiBoQua() {
        SyncProviderProperties properties = new SyncProviderProperties(
                List.of(new SyncProviderProperties.Provider("DEAD", "http://localhost:" + port + "/dead")),
                4L, 3, 50L); // 3 lần thử - "dead" luôn die nên cả 3 lần đều thất bại

        ThirdPartyFlightFetchService service =
                new ThirdPartyFlightFetchService(RestClient.create(), properties, fetchExecutor);

        List<ProviderFetchResult> results = service.fetchAll();

        assertEquals(1, results.size());
        assertFalse(results.get(0).success(), "Hết lượt retry vẫn lỗi -> phải bị đánh dấu thất bại, không throw ra ngoài");
        assertEquals(3, deadCallCount.get(), "Phải thử đúng providerFetchMaxAttempts lần, không hơn không kém");
    }

    @Test
    void fetchAll_loiKetNoiIO_khongPhaiHttpResponseLoi_khongDuocRetry() {
        SyncProviderProperties properties = new SyncProviderProperties(
                List.of(new SyncProviderProperties.Provider("RESET", "http://localhost:" + port + "/reset")),
                4L, 3, 50L); // maxAttempts=3, nhưng lỗi I/O thì KHÔNG được retry

        ThirdPartyFlightFetchService service =
                new ThirdPartyFlightFetchService(RestClient.create(), properties, fetchExecutor);

        List<ProviderFetchResult> results = service.fetchAll();

        assertEquals(1, results.size());
        assertFalse(results.get(0).success());
        // KHÔNG assert đúng "1" - JDK HttpURLConnection tự động retry 1 lần ở
        // tầng transport cho GET khi gặp lỗi kết nối (hành vi của JDK, độc
        // lập với code mình), nên request tới server thật có thể là 2. Điều
        // quan trọng cần assert là code MÌNH không retry thêm (nếu bug quay
        // lại, số lần gọi sẽ nhảy vọt lên ~4-6 do vòng lặp 3 lần tự viết mỗi
        // lần lại cộng thêm 2 lần JDK retry).
        assertTrue(resetCallCount.get() <= 2,
                "Lỗi I/O (không phải HTTP response có status) không được retry ở tầng code - số lần gọi thực tế: "
                        + resetCallCount.get());
    }

    private static void respondJson(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] sampleFlightsJson() throws IOException {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        List<MockFlightDto> flights = List.of(new MockFlightDto(
                "VN101", "Vietnam Airlines", "HAN", "SGN",
                LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2),
                "ECONOMY", new BigDecimal("1000000"), 10));
        return mapper.writeValueAsBytes(flights);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger(0);
        return runnable -> {
            Thread thread = new Thread(runnable, "test-sync-fetch-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
