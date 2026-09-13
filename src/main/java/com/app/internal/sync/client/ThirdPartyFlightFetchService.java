package com.app.internal.sync.client;

import com.app.internal.sync.config.SyncProviderProperties;
import com.app.internal.sync.mock.dto.MockFlightDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

// Task 9: fetch đồng thời TẤT CẢ provider trong app.sync.providers, mỗi
// provider có timeout riêng - 1 provider treo/die KHÔNG được làm chậm/chặn
// các provider còn lại (đúng lý do task này tồn tại, xem GIAO_AN.md Task 9).
//
// Cố ý dùng CompletableFuture (không đổi sang WebFlux/reactive) - RestClient
// vẫn là lời gọi BLOCKING, nhưng mỗi lời gọi chạy trên 1 thread riêng của
// syncProviderFetchExecutor nên "song song" ở mức thread, không phải
// non-blocking I/O - đủ dùng cho vài provider, không cần đổi cả stack HTTP.
@Slf4j
@Service
public class ThirdPartyFlightFetchService {

    private final RestClient restClient;
    private final SyncProviderProperties properties;
    private final ExecutorService fetchExecutor;

    public ThirdPartyFlightFetchService(
            RestClient restClient,
            SyncProviderProperties properties,
            @Qualifier("syncProviderFetchExecutor") ExecutorService fetchExecutor) {
        this.restClient = restClient;
        this.properties = properties;
        this.fetchExecutor = fetchExecutor;
    }

    public List<ProviderFetchResult> fetchAll() {
        List<CompletableFuture<ProviderFetchResult>> futures = properties.providers().stream()
                .map(this::fetchOneAsync)
                .toList();

        // allOf().join() đợi TẤT CẢ future hoàn tất - vì mỗi future đã tự có
        // orTimeout() riêng, "hoàn tất" ở đây nghĩa là "xong hoặc đã hết hạn",
        // KHÔNG BAO GIỜ đợi vô hạn dù 1/nhiều provider treo thật sự.
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    private CompletableFuture<ProviderFetchResult> fetchOneAsync(SyncProviderProperties.Provider provider) {
        return CompletableFuture
                .supplyAsync(() -> fetchOne(provider), fetchExecutor)
                .orTimeout(properties.providerFetchTimeoutSeconds(), TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    String reason = rootMessage(ex);
                    log.warn("[Sync] Provider {} fetch thất bại/timeout, bỏ qua: {}", provider.name(), reason);
                    return ProviderFetchResult.failure(provider.name(), reason);
                });
    }

    // Retry khi provider "die" ĐÚNG NGHĨA: server có phản hồi nhưng là lỗi
    // (4xx/5xx - RestClientResponseException) - hết providerFetchMaxAttempts
    // lần vẫn lỗi thì ném lại lỗi gần nhất, để .exceptionally() ở
    // fetchOneAsync() xử lý như 1 provider bị bỏ qua.
    //
    // CỐ Ý KHÔNG retry lỗi I/O (ResourceAccessException - connection
    // refused/read timeout, tức case "treo") - lần đầu chạy live đã bắt được
    // đúng vấn đề nếu retry cả timeout: orTimeout() ở fetchOneAsync() thường
    // ĐÃ bỏ cuộc trước khi vòng lặp bên dưới kịp thử lần 2, nhưng nếu chính
    // socket timeout (6s, xem RestClientConfig) xảy ra TRƯỚC orTimeout (4s)
    // thì có, và retry thêm 2 lần x 6s sẽ lãng phí ~12s chạy nền vô ích cho 1
    // kết quả không ai còn đợi (future ngoài đã trả lời "bỏ qua" từ lâu).
    private ProviderFetchResult fetchOne(SyncProviderProperties.Provider provider) {
        int maxAttempts = Math.max(1, properties.providerFetchMaxAttempts());
        RestClientResponseException lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                List<MockFlightDto> flights = restClient.get()
                        .uri(provider.url())
                        .retrieve()
                        .body(new ParameterizedTypeReference<List<MockFlightDto>>() {
                        });
                if (attempt > 1) {
                    log.info("[Sync] Provider {} thành công ở lần thử {}/{} (sau {} lần lỗi)",
                            provider.name(), attempt, maxAttempts, attempt - 1);
                }
                return ProviderFetchResult.success(provider.name(), flights);
            } catch (RestClientResponseException ex) {
                lastError = ex;
                boolean hasMoreAttempts = attempt < maxAttempts;
                log.warn("[Sync] Provider {} lỗi ở lần thử {}/{}: {}{}",
                        provider.name(), attempt, maxAttempts, ex.getMessage(),
                        hasMoreAttempts ? " - thử lại sau " + properties.providerRetryDelayMs() + "ms" : " - hết lượt thử");
                if (hasMoreAttempts && !sleepQuietly(properties.providerRetryDelayMs())) {
                    break; // bị interrupt (VD executor.shutdownNow()) - dừng retry ngay, không cố thêm
                }
            }
        }
        throw lastError;
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // orTimeout()/exceptionally() luôn bọc lỗi gốc trong
    // CompletionException/TimeoutException - lấy cause thật để log message
    // hữu ích thay vì "java.util.concurrent.CompletionException" chung chung.
    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
