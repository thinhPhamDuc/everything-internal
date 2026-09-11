package com.app.internal.search.cache;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.SeatClass;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

// Task 6 Phase B (xem TASK6_SEARCH_REDIS_CACHE.md) - mọi tương tác Redis của
// luồng search nằm gọn trong 1 class này: build key, đọc/ghi/xoá cache, tính
// TTL theo bậc. FlightSearchService dùng get()/put() ở loadMatches();
// InventoryService dùng evict() sau khi entity đổi (afterCommit).
//
// NGUYÊN TẮC BẮT BUỘC: mọi lời gọi Redis ở đây PHẢI tự bọc try/catch, KHÔNG
// BAO GIỜ ném exception ra ngoài - search phải luôn trả được kết quả từ
// Postgres kể cả khi Redis sập hoàn toàn (đã có timeout ngắn ở
// application.properties để lỗi kết nối trả về nhanh, không treo request).
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchCacheService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String KEY_PREFIX = "search:flights:";

    private final RedisTemplate<String, Object> redisTemplate;

    // Comma-separated string tự động convert sang List<Integer> nhờ
    // GenericConversionService của Spring (StringToCollectionConverter).
    @Value("${app.search-cache.tier-thresholds-days}")
    private List<Integer> tierThresholdDays;

    @Value("${app.search-cache.tier-ttl-seconds}")
    private List<Integer> tierTtlSeconds;

    @Value("${app.search-cache.jitter-percent}")
    private int jitterPercent;

    public String buildKey(String origin, String destination, LocalDate departureDate, SeatClass seatClass) {
        return KEY_PREFIX
                + origin.toUpperCase() + ":"
                + destination.toUpperCase() + ":"
                + DATE_FORMAT.format(departureDate) + ":"
                + seatClass.name();
    }

    public String buildKeyForEntity(FlightTicketInventory inventory) {
        return buildKey(
                inventory.getOrigin(),
                inventory.getDestination(),
                inventory.getDepartureTime().toLocalDate(),
                inventory.getSeatClass());
    }

    @SuppressWarnings("unchecked")
    public List<FlightTicketInventory> get(String key) {
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            return (List<FlightTicketInventory>) cached;
        } catch (Exception e) {
            log.warn("[SearchCacheService] Lỗi đọc cache key={}, bỏ qua cache, rơi xuống Postgres", key, e);
            return null;
        }
    }

    public void put(String key, List<FlightTicketInventory> matches, LocalDate departureDate) {
        try {
            redisTemplate.opsForValue().set(key, matches, resolveTtl(departureDate));
        } catch (Exception e) {
            log.warn("[SearchCacheService] Lỗi ghi cache key={}, bỏ qua - vẫn trả kết quả từ Postgres", key, e);
        }
    }

    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[SearchCacheService] Lỗi xoá cache key={}, cache có thể tạm thời stale tới khi hết TTL", key, e);
        }
    }

    // Dùng chung cho MỌI nơi đổi origin/destination/departureTime/seatClass/
    // availableSeats/status của 1 FlightTicketInventory - InventoryService
    // (Task 4/6) VÀ InventoryService.decrementSeats/incrementSeats (Task 7,
    // BookingService gọi gián tiếp qua đó) đều cần đúng 1 logic này. Đăng ký
    // SAU KHI transaction commit chắc chắn (đúng pattern AuthService.register())
    // - tránh xoá cache rồi transaction lại rollback.
    public void evictAfterCommit(FlightTicketInventory inventory) {
        String key = buildKeyForEntity(inventory);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(key);
            }
        });
    }

    // daysToDeparture luôn >= 0 vì FlightSearchRequest.departureDate đã validate
    // @FutureOrPresent, và Inventory.departureTime luôn ở tương lai (xem
    // InventoryCreateRequest @Future) tại thời điểm entity được tạo/sửa.
    private Duration resolveTtl(LocalDate departureDate) {
        long daysToDeparture = ChronoUnit.DAYS.between(LocalDate.now(), departureDate);

        int tierIndex = 0;
        while (tierIndex < tierThresholdDays.size() && daysToDeparture > tierThresholdDays.get(tierIndex)) {
            tierIndex++;
        }

        int baseTtlSeconds = tierTtlSeconds.get(tierIndex);
        return Duration.ofSeconds(applyJitter(baseTtlSeconds));
    }

    // +-jitterPercent% ngẫu nhiên quanh baseTtlSeconds, chống cache stampede
    // (nhiều key cùng bậc hết hạn đồng loạt khi nhiều route hot rơi vào cùng
    // khung ngày).
    private long applyJitter(int baseTtlSeconds) {
        double maxDelta = baseTtlSeconds * (jitterPercent / 100.0);
        double jitter = ThreadLocalRandom.current().nextDouble(-maxDelta, maxDelta);
        return Math.max(1, Math.round(baseTtlSeconds + jitter));
    }
}
