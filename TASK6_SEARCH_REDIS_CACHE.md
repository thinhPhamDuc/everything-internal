# Task 6 — Tìm Kiếm Vé Máy Bay + Redis Cache: Design Đã Chốt

> Khác với `TASK2/3/4_*.md` (viết theo kiểu tự làm, TODO để bạn tự code), file
> này là **design doc đã chốt qua trao đổi trực tiếp** — dùng để tham chiếu
> khi implement, không phải bài tập tự học. Từ Task 4 trở đi, cách làm việc đã
> đổi sang **Claude code trực tiếp** thay vì chỉ đưa TODO (xác nhận ngày
> 2026-09-10). Vì vậy file này ghi quyết định thiết kế cụ thể, không đặt câu
> hỏi "tự quyết định" như các file trước.
>
> **Phụ thuộc:** cần Task 5 (đồng bộ dữ liệu) chạy xong để Inventory có dữ
> liệu thật đủ nhiều thì search + cache mới có ý nghĩa để test. Task 6 cơ bản
> (Phase A) có thể code trước, chạy tạm với dữ liệu nhập tay từ Task 4 để
> test luồng, nhưng Phase B (Redis cache) chỉ nên bật khi đã có dữ liệu thật.

---

## Bối cảnh & phạm vi

`GIAO_AN.md` Task 6 định nghĩa: API public (không cần login) cho client tìm
vé theo chặng bay + ngày + hạng ghế, trả danh sách vé còn chỗ, sắp theo giá.
Trong quá trình bàn bạc, đã quyết định bổ sung **Redis cache** lên trên luồng
search này để tăng tốc, vì search là truy vấn **đọc nhiều, dùng chung cho mọi
user** (khác với danh sách booking cá nhân — ít lợi ích cache hơn nhiều, xem
lý do đầy đủ ở cuộc trao đổi ngày 2026-09-10, không lặp lại ở đây).

Chia làm 2 phase độc lập, implement lần lượt:

- **Phase A — Search cơ bản:** chạy đúng bằng Postgres thuần, không cache.
- **Phase B — Redis cache:** thêm lên trên Phase A mà không đổi contract API.

---

## Phase A — Search cơ bản (package `search/`)

### Entity/dữ liệu dùng lại

Dùng lại `FlightTicketInventory` (Task 4), **không tạo entity mới**. Chỉ đọc
(`@Transactional(readOnly = true)`), không ghi.

### DTO

- `search/dto/FlightSearchRequest` (record): `origin, destination,
  departureDate (LocalDate — theo đúng ngày, không phải giờ chính xác, đúng
  ghi chú GIAO_AN), seatClass, passengerCount`. Validate: `origin`/
  `destination` `@NotBlank`, `departureDate` `@NotNull @FutureOrPresent`,
  `passengerCount` `@Positive`.
- `search/dto/FlightSearchResponse` (record): **chỉ** field client cần thấy —
  `id, flightCode, airline, origin, destination, departureTime, arrivalTime,
  seatClass, price, availableSeats`. **Cố ý KHÔNG có** `sourceSystem`,
  `totalSeats`, `status`, `version`, `lastSyncedAt` — đây là field nội bộ,
  đúng nguyên tắc "không lộ thông tin nhạy cảm" đã ghi ở GIAO_AN Task 6.

### Repository (mở rộng `InventoryRepository` đã có ở Task 4)

Thêm derived method lọc theo ngày (không phải giờ chính xác) + điều kiện còn
chỗ + đang mở bán:

```java
@Query("""
        SELECT i FROM FlightTicketInventory i
        WHERE i.origin = :origin
          AND i.destination = :destination
          AND i.departureTime >= :startOfDay AND i.departureTime < :endOfDay
          AND i.seatClass = :seatClass
          AND i.availableSeats >= :passengerCount
          AND i.status = com.app.internal.inventory.enums.InventoryStatus.OPEN
        ORDER BY i.price ASC
        """)
List<FlightTicketInventory> searchAvailable(
        String origin, String destination,
        LocalDateTime startOfDay, LocalDateTime endOfDay,
        SeatClass seatClass, Integer passengerCount);
```

(`startOfDay`/`endOfDay` tính từ `departureDate` — `LocalDateTime` không hỗ
trợ so sánh trực tiếp theo `LocalDate` trong JPQL nên convert ở Service.)

### Service

`search/service/FlightSearchService` — **tách rõ 2 method** (đúng gợi ý
GIAO_AN, để Phase B chèn cache vào giữa mà không đụng method còn lại):

```java
public List<FlightSearchResponse> search(FlightSearchRequest request) {
    List<FlightTicketInventory> matches = loadMatches(request); // Phase B sẽ chèn cache vào đây
    return formatResponse(matches);
}

private List<FlightTicketInventory> loadMatches(FlightSearchRequest request) {
    // query Postgres qua searchAvailable(...)
}

private List<FlightSearchResponse> formatResponse(List<FlightTicketInventory> matches) {
    // map entity -> DTO, KHÔNG lộ field nội bộ
}
```

### Controller

`search/controller/FlightSearchController` — `GET /flights/search`, **public,
không `@PreAuthorize`** (thêm vào danh sách endpoint public ở
`SecurityConfig`, cạnh `/auth/**`).

### Test

Search bằng dữ liệu đã seed thủ công qua Task 4 (`POST /admin/inventory`):
đủ chỗ → trả về; hết chỗ (`availableSeats < passengerCount`) → không trả;
`status != OPEN` → không trả; sai `origin`/`destination` → danh sách rỗng chứ
không lỗi 404.

---

## Phase B — Redis cache lên trên Phase A

### Hạ tầng cần thêm

1. `docker-compose.yml`: thêm service `redis` (image `redis:7-alpine`), port
   `6379`, kèm `command: redis-server --maxmemory 256mb --maxmemory-policy
   allkeys-lru` — cấu hình thẳng trong `command`, không cần file config riêng
   cho MVP.
2. `build.gradle`: thêm `spring-boot-starter-data-redis` (Lettuce client, đi
   kèm mặc định).
3. `application.properties`: `spring.data.redis.host`, `spring.data.redis.port`,
   và **timeout kết nối ngắn** (`spring.data.redis.timeout=500ms`,
   `spring.data.redis.lettuce.shutdown-timeout=200ms`) — bắt buộc, để Redis
   chết không kéo chậm luôn request search (mục "Resilience" bên dưới).

### Cache key

Chuẩn hoá trước khi build key: `origin`/`destination` viết hoa, `departureDate`
format `yyyy-MM-dd`, `seatClass` tên enum:

```
search:flights:{ORIGIN}:{DESTINATION}:{yyyy-MM-dd}:{SEAT_CLASS}
```

**Không đưa `page`/`sort`/`passengerCount` vào key.** Cache **toàn bộ danh
sách khớp route+ngày+hạng ghế** (đã sort theo giá tăng dần từ query), lọc
`passengerCount` áp dụng **sau khi** lấy từ cache (in-memory), để 1 route+ngày
hot chỉ tốn đúng 1 cache entry thay vì nổ số lượng key theo mọi tổ hợp
passengerCount có thể có.

### TTL thích ứng theo khoảng cách tới ngày bay (đã chốt)

Tính `daysToDeparture = ChronoUnit.DAYS.between(LocalDate.now(),
request.departureDate())` tại thời điểm ghi cache (giá trị luôn `>= 0` vì
input đã validate `@FutureOrPresent`).

| `daysToDeparture` | TTL cơ sở |
|---|---|
| 0–1 | 30 giây |
| 2–7 | 2 phút |
| 8–30 | 10 phút |
| > 30 | 30 phút |

Cấu hình qua `application.properties`, không hard-code:

```properties
app.search-cache.tier-thresholds-days=1,7,30
app.search-cache.tier-ttl-seconds=30,120,600,1800
app.search-cache.jitter-percent=15
```

TTL thật khi `SETEX` = TTL cơ sở theo bậc, cộng/trừ ngẫu nhiên
`jitter-percent` (chống cache stampede — nhiều key cùng bậc hết hạn đồng
loạt khi có nhiều route hot rơi vào cùng khung ngày).

### Luồng đọc (chèn vào `loadMatches()` ở Phase A, không đổi `search()`/`formatResponse()`)

```
loadMatches(request):
  key = buildCacheKey(request)
  try:
    cached = redisTemplate.opsForValue().get(key)
    if cached != null: return cached
  catch (lỗi kết nối Redis):
    log.warn(...), rơi xuống nhánh dưới — KHÔNG throw

  matches = inventoryRepository.searchAvailable(...)   // Postgres, như Phase A
  try:
    ttl = resolveTtl(request.departureDate())            // bảng tier + jitter
    redisTemplate.opsForValue().set(key, matches, ttl)   // best-effort, không chặn response
  catch (lỗi kết nối Redis):
    log.warn(...), vẫn return matches bình thường

  return matches
```

### Invalidate khi Inventory thay đổi

Trong `InventoryService` (Task 4, đã có) — ở `createInventory`,
`updateInventory`, `changeStatus`, `closeInventory` — sau khi entity đổi
`origin`/`destination`/`departureTime`/`seatClass`/`availableSeats`/`status`,
xoá đúng 1 cache key tương ứng (build key từ chính entity vừa đổi, cùng hàm
`buildCacheKey` dùng ở Phase B). Đây là lý do nên đăng ký hook này ở
`InventoryService` bằng `TransactionSynchronization.afterCommit()` (đúng
pattern đã dùng ở `AuthService.register()`) — invalidate **sau khi** DB commit
chắc chắn, không invalidate rồi transaction lại rollback.

**Không bắt buộc ở MVP nhưng nên làm cùng lúc (chi phí thấp):** Task 7 khi
trừ `availableSeats` lúc đặt vé cũng nên gọi cùng hàm invalidate này — chỉ để
trải nghiệm search không hiển thị số ghế cũ ngay sau khi có người vừa đặt,
**không phải yêu cầu đúng đắn bắt buộc** (đúng đắn bắt buộc vẫn nằm ở nguyên
tắc dưới).

### Nguyên tắc bắt buộc — không đổi dù cache có hay không

**Task 7 (trừ ghế khi đặt vé thật) luôn đọc thẳng Postgres kèm lock
(optimistic/pessimistic), không bao giờ được đọc/tin số liệu từ Redis để
quyết định còn ghế hay không.** Cache chỉ phục vụ **hiển thị kết quả search**
(chấp nhận stale vài chục giây tới vài chục phút tuỳ tier), không phải nguồn
sự thật cho hành động ghi. Vi phạm nguyên tắc này là nguyên nhân trực tiếp
gây oversell vé.

### Resilience khi Redis chết

- Timeout kết nối ngắn (đã cấu hình ở phần Hạ tầng).
- Mọi lời gọi Redis trong `loadMatches()`/invalidate đều bọc try/catch riêng,
  log `WARN`, không propagate exception ra ngoài — search phải luôn trả được
  kết quả từ Postgres kể cả khi Redis sập hoàn toàn.
- Không cần circuit breaker (Resilience4j) ở MVP — timeout ngắn + try/catch
  là đủ cho quy mô đồ án; cân nhắc thêm sau nếu thấy Redis chập chờn gây
  nhiều lần timeout liên tiếp làm chậm search rõ rệt.

---

## Checklist khi implement xong

- [ ] `GET /flights/search` chạy đúng bằng Postgres thuần khi Redis tắt hẳn
      (dừng container `redis`) — không lỗi, không chậm bất thường (nhờ
      timeout ngắn).
- [ ] Search cùng 1 route+ngày+hạng ghế 2 lần liên tiếp → lần 2 không query
      Postgres (kiểm tra qua log Hibernate hoặc thời gian phản hồi).
- [ ] Sửa giá 1 vé qua `PUT /admin/inventory/{id}` → search lại route đó →
      thấy giá mới ngay (không phải đợi hết TTL) nhờ invalidate.
- [ ] Kiểm tra TTL thực tế trong Redis (`TTL <key>` qua `redis-cli`) đúng
      theo bậc tương ứng với `daysToDeparture` đã search.
- [ ] `redis-cli INFO memory` không vượt `maxmemory` đã cấu hình khi seed
      nhiều route khác nhau; key bị evict theo LRU khi chạm ngưỡng (không
      cần code kiểm tra, chỉ quan sát).
- [ ] Response `FlightSearchResponse` không có field `sourceSystem`/
      `totalSeats`/`status`/`version`.

---

## Việc KHÔNG làm ở Task 6 (nhắc lại để không lấn phạm vi)

- Không đụng tới `Booking`/danh sách booking cá nhân — ngoài phạm vi cache
  này (xem lý do ở cuộc trao đổi thiết kế).
- Không implement circuit breaker/Resilience4j — để dành nếu thực tế cần.
- Không cache theo `passengerCount` riêng — lọc in-memory sau khi lấy cache
  (xem phần "Cache key").
