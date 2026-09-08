# Giáo Án: Xây Dựng Hệ Thống Quản Lý Phòng Vé Máy Bay

> Vai trò của tài liệu này: đây là **giáo án**, không phải code. Mỗi task có phần
> "Tại sao cần", "Overview", "Các bước thực hiện" và "Lưu ý". Bạn đọc, tự code,
> quay lại hỏi khi bí — giống một buổi hướng dẫn đồ án thật.
>
> Stack đã xác nhận từ project hiện tại: **Java 17, Spring Boot (Gradle), package
> gốc `com.app.internal`**, hiện mới có `spring-boot-starter-data-jpa` và
> `spring-boot-starter-webmvc`. Các phần dưới đây sẽ giả định bạn thêm dần các
> dependency khác (Security, AMQP, Batch, Validation...) theo từng task.
>
> Database mình đề xuất: **PostgreSQL** (đã cấu hình sẵn trong `docker-compose.yml`).
> Nếu bạn muốn MySQL thì báo lại, mình sửa compose file.

---

## 0. Overview dự án

Hệ thống quản lý phòng vé máy bay, gồm 2 "site" (2 nhóm chức năng, có thể là 2
frontend riêng nhưng dùng chung 1 backend API):

- **Site Admin**: nhân viên/admin quản lý user, quản lý kho vé (inventory), và có
  một tiến trình nền tự động đồng bộ dữ liệu vé từ một hệ thống bên thứ ba vào kho.
- **Site User (client)**: khách hàng tìm kiếm vé và đặt vé + thanh toán.

Trái tim của hệ thống là bảng **Inventory** (kho vé máy bay) — mọi thứ đều xoay
quanh việc: kho được đổ dữ liệu vào bằng cách nào (Task 5), và kho được đọc/ghi
ra sao khi user tìm kiếm (Task 6) và đặt vé (Task 7).

---

## 1. Cấu trúc thư mục dự án (đề xuất)

Vì đây vẫn là **1 Spring Boot module duy nhất** (không phải microservices), mình
tổ chức theo **package-by-feature** — mỗi nghiệp vụ một package riêng, dễ quản lý
hơn package-by-layer khi project lớn dần.

```
everything-internal/
├── docker-compose.yml              # đã tạo ở bước này
├── build.gradle
├── settings.gradle
├── GIAO_AN.md                      # file này
├── src/
│   ├── main/
│   │   ├── java/com/app/internal/
│   │   │   ├── EverythingInternalApplication.java
│   │   │   │
│   │   │   ├── config/              # Task 0-5: bean config
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   ├── RabbitMQConfig.java      # khai báo exchange/queue/binding
│   │   │   │   ├── BatchConfig.java         # khai báo Job/Step
│   │   │   │   └── SchedulerConfig.java     # bật @EnableScheduling
│   │   │   │
│   │   │   ├── common/              # Task 1: dùng chung toàn app
│   │   │   │   ├── exception/       # GlobalExceptionHandler, custom exceptions
│   │   │   │   ├── response/        # ApiResponse<T> wrapper chuẩn hoá output
│   │   │   │   └── util/
│   │   │   │
│   │   │   ├── auth/                # Task 1: đăng ký / đăng nhập
│   │   │   │   ├── controller/
│   │   │   │   ├── service/
│   │   │   │   ├── dto/             # RegisterRequest, LoginRequest, TokenResponse
│   │   │   │   └── jwt/             # JwtProvider, JwtFilter
│   │   │   │
│   │   │   ├── user/                # Task 2: CRUD Users
│   │   │   │   ├── controller/
│   │   │   │   ├── service/
│   │   │   │   ├── repository/
│   │   │   │   ├── entity/          # User.java
│   │   │   │   └── dto/
│   │   │   │
│   │   │   ├── role/                # Task 3: CRUD Roles/Permissions
│   │   │   │   ├── controller/
│   │   │   │   ├── service/
│   │   │   │   ├── repository/
│   │   │   │   └── entity/          # Role.java, Permission.java
│   │   │   │
│   │   │   ├── inventory/           # Task 4: CRUD vé máy bay trong kho
│   │   │   │   ├── controller/      # dành cho admin thao tác trực tiếp
│   │   │   │   ├── service/
│   │   │   │   ├── repository/
│   │   │   │   ├── entity/          # FlightTicketInventory.java
│   │   │   │   └── dto/
│   │   │   │
│   │   │   ├── sync/                # Task 5: đồng bộ dữ liệu định kỳ
│   │   │   │   ├── scheduler/       # FlightSyncScheduler (@Scheduled 12h)
│   │   │   │   ├── mq/              # Producer + Listener cho từng bước
│   │   │   │   ├── client/          # Http client gọi API bên thứ 3
│   │   │   │   ├── mock/            # Controller mock giả lập API bên thứ 3
│   │   │   │   └── staging/         # entity/repository bảng staging (dữ liệu thô)
│   │   │   │
│   │   │   ├── batch/               # Task 5: Spring Batch job xử lý & import
│   │   │   │   ├── job/             # FlightImportJobConfig
│   │   │   │   ├── reader/
│   │   │   │   ├── processor/
│   │   │   │   └── writer/
│   │   │   │
│   │   │   ├── search/              # Task 6: tìm kiếm vé (client)
│   │   │   │   ├── controller/
│   │   │   │   ├── service/
│   │   │   │   └── dto/             # FlightSearchRequest/Response
│   │   │   │
│   │   │   └── booking/             # Task 7: đặt vé + thanh toán
│   │   │       ├── controller/
│   │   │       ├── service/
│   │   │       ├── repository/
│   │   │       ├── entity/          # Booking.java, Payment.java
│   │   │       ├── payment/         # PaymentGatewayClient (mock/sandbox)
│   │   │       └── dto/
│   │   │
│   │   └── resources/
│   │       ├── application.properties
│   │       ├── application-local.properties
│   │       └── db/migration/        # Flyway/Liquibase script (khuyến nghị)
│   │
│   └── test/java/com/app/internal/...
```

**Ghi chú:** frontend (site admin, site user) không nằm trong repo backend này —
đề xuất tạo 2 repo/thư mục riêng (`admin-web/`, `user-web/`) khi bạn tới phần
frontend. Giáo án này tập trung vào backend vì đó là phần có luồng nghiệp vụ
phức tạp nhất (Task 5).

---

## 2. docker-compose.yml — code mẫu

File đã được tạo sẵn tại **`docker-compose.yml`** ở gốc project, bạn chạy:

```bash
docker compose up -d
docker compose ps
```

Nội dung gồm 2 service:
- **postgres** — port `5432`, db `airline_db`, user/pass `airline_user`/`airline_pass`
- **rabbitmq** — port `5672` (AMQP) + `15672` (Management UI, mở trình duyệt để
  xem queue/exchange trực quan — rất hữu ích khi debug Task 5)

App Spring Boot vẫn chạy local (không container hoá) để bạn dev nhanh. Phần
`spring.datasource.*` / `spring.rabbitmq.*` cần thêm vào `application.properties`
được ghi chú ngay cuối file compose.

---

## 3. Danh sách Task (thứ tự khuyến nghị)

| # | Task | Vì sao làm theo thứ tự này |
|---|------|------------------------------|
| 0 | Docker Compose hạ tầng | Đã xong ở trên — nền tảng để mọi task sau chạy được |
| 1 | Đăng ký / Đăng nhập (Auth + JWT) | Mọi API sau đều cần biết "ai đang gọi" |
| 2 | CRUD Users | Cần có Users thật để gán quyền, để test Auth |
| 3 | CRUD Roles/Permissions | Bổ sung phân quyền cho Users (admin vs staff vs...) |
| 4 | CRUD Inventory (vé máy bay) | Kho dữ liệu trung tâm — Task 5,6,7 đều thao tác lên đây |
| 5 | Luồng đồng bộ tự động (Scheduler → RabbitMQ → 3rd-party mock → RabbitMQ → Spring Batch → DB) | Phần phức tạp nhất, cần Inventory (Task 4) tồn tại trước |
| 6 | Tìm kiếm vé (client) | Cần Inventory có dữ liệu thật (từ Task 5) để search có ý nghĩa |
| 7 | Đặt vé + Thanh toán | Bước cuối cùng của hành trình user, phụ thuộc Search (Task 6) |

---

## 4. Chi tiết từng Task

### Task 1 — Đăng ký / Đăng nhập (Auth + JWT)

**Tại sao cần:** Đây là "cửa vào" của toàn hệ thống. Không có auth thì không phân
biệt được admin và client, không bảo vệ được API quản trị (CRUD Users, Inventory).

**Overview:** Xây dựng cơ chế đăng ký (tạo tài khoản mới) và đăng nhập (xác thực,
trả về JWT). Các API sau này sẽ dùng JWT trong header `Authorization: Bearer <token>`
để xác định user + role.

**Các bước thực hiện:**
1. Thêm dependency: `spring-boot-starter-security`, `jjwt` (hoặc `nimbus-jose-jwt`),
   `spring-boot-starter-validation`.
2. Thiết kế entity `User` tối thiểu: `id, email, password (hash), fullName, status,
   roles`. Chưa cần entity Role hoàn chỉnh ở bước này — có thể tạm 1 cột `role` string,
   rồi chuẩn hoá thành bảng riêng ở Task 3.
3. Viết `RegisterRequest` (email, password, fullName) + validate (email hợp lệ,
   password đủ mạnh) bằng Bean Validation.
4. Hash password bằng `BCryptPasswordEncoder` trước khi lưu — **không bao giờ**
   lưu plaintext.
5. Viết `LoginRequest` (email, password) → xác thực → nếu đúng, sinh JWT chứa
   `sub` (userId), `roles`, `exp` (thời hạn hết hạn).
6. Viết `JwtFilter` (OncePerRequestFilter) chạy trước mỗi request: đọc token từ
   header, verify chữ ký + hạn dùng, set `SecurityContext`.
7. Cấu hình `SecurityFilterChain`: các endpoint `/auth/**` public, còn lại yêu cầu
   token hợp lệ (sẽ tinh chỉnh theo role ở Task 3).
8. Test bằng Postman/curl: register → login → gọi 1 API bất kỳ kèm token.

**Lưu ý:**
- Nghĩ tới refresh token nếu muốn phiên đăng nhập dài hạn (không bắt buộc ở
  bản đầu, có thể để "khi làm xong hết mới quay lại nâng cấp").
- Đừng để lộ chi tiết lỗi khi login sai (VD tránh trả "email không tồn tại" —
  dễ bị dò email — chỉ nên trả chung "sai email hoặc mật khẩu").

---

### Task 2 — CRUD Users (quản lý người dùng, dành cho Admin)

**Tại sao cần:** Admin cần xem danh sách user, khoá/mở tài khoản, sửa thông tin,
xoá tài khoản vi phạm. Đây cũng là nơi bạn thực hành pattern CRUD chuẩn sẽ lặp
lại ở Task 3, Task 4.

**Overview:** Một bộ API REST chuẩn (`GET/POST/PUT/DELETE /admin/users`) chỉ
admin mới gọi được, thao tác trên entity `User` đã tạo ở Task 1.

**Các bước thực hiện:**
1. Tạo `UserController` với các endpoint: list (có phân trang + filter theo
   status/email), get by id, update thông tin, đổi status (active/locked), xoá
   (soft-delete được khuyến nghị hơn xoá cứng — giữ lịch sử booking sau này).
2. Tạo `UserService` xử lý logic, `UserRepository extends JpaRepository`.
3. Dùng DTO riêng cho input/output — **không** trả thẳng entity `User` ra API
   (tránh lộ password hash, tránh vấn đề lazy-loading serialize).
4. Áp dụng phân trang (`Pageable`) cho endpoint list — dữ liệu user có thể lớn.
5. Giới hạn quyền: chỉ role `ADMIN` mới gọi được nhóm endpoint `/admin/users/**`
   (dùng `@PreAuthorize("hasRole('ADMIN')")` — tạm thời dùng role string, sẽ
   chuẩn hoá ở Task 3).
6. Viết test cơ bản: tạo user, sửa, xoá, kiểm tra user thường không gọi được API
   admin (401/403).

**Lưu ý:**
- Soft-delete (`deletedAt` hoặc `status = INACTIVE`) tốt hơn xoá cứng vì Booking
  (Task 7) sẽ tham chiếu tới User — xoá cứng dễ vỡ dữ liệu liên quan.

---

### Task 3 — CRUD Roles/Permissions (phân quyền)

**Tại sao cần:** Hệ thống có nhiều loại người dùng: admin (toàn quyền), nhân viên
vận hành (chỉ CRUD inventory), khách hàng (chỉ search + booking). Hard-code role
string không mở rộng được khi nghiệp vụ phức tạp lên — cần bảng Role/Permission
độc lập để admin tự cấu hình mà không cần sửa code.

> *(Đây là phần mình suy ra từ việc đề bài liệt kê trùng "2.2/2.3 CRUD Users" —
> báo lại nếu ý bạn là tính năng khác, ví dụ CRUD Airlines/Airports.)*

**Overview:** Thiết kế mô hình `User – Role – Permission` (many-to-many), thay
thế cột role string tạm ở Task 1 bằng quan hệ thực sự, và cập nhật `SecurityConfig`
để check theo permission thay vì hard-code.

**Các bước thực hiện:**
1. Entity `Role` (id, name, description) và `Permission` (id, code, description),
   quan hệ `Role <-> Permission` (many-to-many), `User <-> Role` (many-to-many
   hoặc many-to-one tuỳ độ phức tạp bạn muốn — many-to-one đơn giản hơn cho MVP).
2. Seed data sẵn vài role cơ bản: `ADMIN`, `STAFF`, `CUSTOMER` (migration script
   hoặc `data.sql`).
3. CRUD API cho Role (`/admin/roles`) — gán/gỡ permission cho role.
4. API gán role cho user (`/admin/users/{id}/roles`).
5. Cập nhật `JwtProvider` để nhúng danh sách role/permission vào token (hoặc load
   lại từ DB mỗi request — đánh đổi giữa token nhẹ và dữ liệu luôn mới).
6. Cập nhật các `@PreAuthorize` ở Task 2 dùng permission thay vì role cứng.

**Lưu ý:**
- Nếu thấy quá phức tạp cho MVP, có thể tạm dừng ở mức chỉ có Role (bỏ
  Permission chi tiết), thêm Permission sau khi các task khác đã chạy được.

---

### Task 4 — CRUD Inventory (vé máy bay trong kho)

**Tại sao cần:** Đây là bảng dữ liệu trung tâm của cả hệ thống — mọi vé máy bay
(chuyến bay, hạng ghế, số lượng còn lại, giá) đều nằm ở đây. Task 5 sẽ đổ dữ liệu
tự động vào bảng này; Task 6/7 sẽ đọc/ghi lên nó.

**Overview:** Thiết kế entity `FlightTicketInventory` đại diện 1 "chuyến bay +
hạng vé" cụ thể, và bộ CRUD admin để thao tác thủ công (sửa giá, sửa số lượng,
tạm ẩn vé...) song song với luồng tự động ở Task 5.

**Các bước thực hiện:**
1. Thiết kế entity, tối thiểu gồm: `flightCode, airline, origin, destination,
   departureTime, arrivalTime, seatClass (economy/business...), price,
   totalSeats, availableSeats, status (OPEN/CLOSED/CANCELLED), sourceSystem
   (đánh dấu vé này đến từ đâu — nhập tay hay đồng bộ tự động), lastSyncedAt`.
2. `InventoryController` (`/admin/inventory`) CRUD chuẩn + tìm kiếm nội bộ theo
   chặng/ngày cho admin.
3. Ràng buộc nghiệp vụ quan trọng: `availableSeats <= totalSeats`, không cho sửa
   `availableSeats` âm, không cho xoá vé đã có booking (chỉ đóng `status`).
4. Index DB trên các cột hay được lọc: `origin, destination, departureTime` —
   sẽ cần cho Task 6 tìm kiếm nhanh.
5. Test CRUD cơ bản qua Postman.

**Lưu ý:**
- Cân nhắc composite index `(origin, destination, departureTime)` ngay từ đầu vì
  đây chính là truy vấn nóng nhất của toàn hệ thống (search).

---

### Task 5 — Luồng đồng bộ dữ liệu tự động (Scheduler → RabbitMQ → 3rd-party mock → RabbitMQ → Spring Batch → DB)

**Tại sao cần:** Trong thực tế, dữ liệu vé không phải admin nhập tay hết mà đến
từ hệ thống đối tác/GDS (Global Distribution System). Task này mô phỏng một pipeline
ETL thực tế: chạy định kỳ, lấy dữ liệu ngoài, xử lý, nạp vào kho — dùng message
queue để tách rời (decouple) các bước, và Spring Batch để xử lý dữ liệu lớn theo
lô một cách có kiểm soát (resume được nếu lỗi giữa chừng, tránh load hết vào
memory).

**Overview — luồng đầy đủ:**

```
[Scheduler 12h trưa]
      │  (1) publish message "trigger-sync" — không chứa dữ liệu, chỉ là "tín hiệu"
      ▼
[RabbitMQ: queue "flight.sync.trigger"]
      │  (2) Consumer #1 lắng nghe, nhận tín hiệu
      ▼
[Consumer #1: gọi API bên thứ 3 (mock)]
      │  (3) fetch dữ liệu thô (list vé máy bay) → lưu tạm vào bảng "staging"
      │      (hoặc file/S3 — nhưng bảng staging đơn giản hơn cho MVP)
      ▼
      │  (4) publish message "data-ready" kèm theo id của batch dữ liệu vừa fetch
      ▼
[RabbitMQ: queue "flight.batch.trigger"]
      │  (5) Consumer #2 lắng nghe, nhận tín hiệu "data-ready"
      ▼
[Consumer #2: gọi JobLauncher.run(flightImportJob, params)]
      ▼
[Spring Batch Job]
      │  Step: Reader (đọc từ bảng staging theo chunk, VD 100 dòng/lần)
      │        → Processor (validate, chuẩn hoá, map sang entity Inventory)
      │        → Writer (ghi vào bảng Inventory thật, upsert theo flightCode+date)
      ▼
[Bảng Inventory] ← dữ liệu đã sẵn sàng cho Task 6/7
```

**Vì sao qua RabbitMQ 2 lần thay vì gọi thẳng?** Đây chính là điểm hay của kiến
trúc message-driven: bước "gọi API bên ngoài" (I/O chậm, có thể fail, có thể bị
rate-limit) tách biệt hoàn toàn khỏi bước "xử lý dữ liệu nặng" (CPU/DB-bound).
Nếu 1 trong 2 bước lỗi, chỉ cần retry đúng bước đó (nhờ queue giữ message lại),
không phải chạy lại từ đầu.

**Các bước thực hiện:**

1. **Mock API bên thứ 3:** Tạo 1 controller riêng (VD `/mock/third-party/flights`)
   trả về danh sách vé máy bay giả (random hoặc fixture cố định) — đóng vai trò
   hệ thống đối tác bên ngoài. Có thể để random field như `seatsLeft`, `price`
   thay đổi mỗi lần gọi để mô phỏng dữ liệu "sống".
2. **Bảng/entity Staging:** Lưu dữ liệu thô fetch về (gần như nguyên bản JSON từ
   bên thứ 3, kèm `batchId`, `fetchedAt`, `processed=false`) — tách biệt với
   Inventory để Batch có nguồn đọc ổn định, và giữ lại lịch sử fetch để debug.
3. **RabbitMQ config:** Khai báo 2 exchange/queue (`flight.sync.trigger`,
   `flight.batch.trigger`), có thể dùng cùng 1 exchange với 2 routing key khác
   nhau cho gọn. Cấu hình thêm **Dead Letter Queue (DLQ)** cho mỗi queue — nếu
   consumer xử lý lỗi liên tục, message rơi vào DLQ thay vì retry vô hạn.
4. **Scheduler:** `@Scheduled(cron = "0 0 12 * * *")` — publish message trigger
   vào `flight.sync.trigger`. (Lưu ý: khi dev, tạm để cron chạy mỗi vài phút để
   test nhanh, đừng đợi thật tới 12h trưa.)
5. **Consumer #1 (fetch service):** `@RabbitListener` trên `flight.sync.trigger`
   → gọi mock API bằng `WebClient`/`RestClient` → lưu vào staging → publish
   message sang `flight.batch.trigger` kèm `batchId`.
6. **Spring Batch Job:** Thêm dependency `spring-boot-starter-batch`. Định nghĩa
   `Job` gồm 1 `Step` dạng chunk-oriented:
   - **Reader**: `RepositoryItemReader` hoặc `JdbcCursorItemReader` đọc staging
     theo `batchId`, `processed=false`.
   - **Processor**: validate dữ liệu (giá > 0, ngày bay hợp lệ...), map sang
     `FlightTicketInventory`, đánh dấu record lỗi để reject riêng thay vì làm
     fail cả batch.
   - **Writer**: upsert vào bảng Inventory (theo unique key `flightCode + departureTime + seatClass`
     — nếu đã tồn tại thì update giá/số lượng, chưa có thì insert mới).
7. **Consumer #2 (batch trigger):** `@RabbitListener` trên `flight.batch.trigger`
   → gọi `jobLauncher.run(job, new JobParametersBuilder().addString("batchId",...).toJobParameters())`.
8. **Idempotency:** Đảm bảo chạy lại cùng 1 `batchId` không tạo dữ liệu trùng
   (Spring Batch tự chặn chạy lại `JobInstance` với cùng `JobParameters` — tận
   dụng điều này).
9. Test toàn luồng: gọi tay endpoint trigger (thay vì đợi scheduler), theo dõi
   RabbitMQ Management UI (`localhost:15672`) xem message đi qua từng queue,
   kiểm tra bảng Inventory sau khi Job chạy xong (`spring_batch_job_execution`
   để xem log Job).

**Lưu ý:**
- Đây là task khó nhất — nên làm sau khi Task 4 (Inventory) đã ổn định.
- Có thể làm từng nửa: đầu tiên chạy được "Scheduler → Consumer #1 → staging"
  (không cần Batch), test ổn rồi mới nối tiếp "Consumer #2 → Batch → Inventory".
- Đọc thêm về **acknowledgment mode** của RabbitMQ (manual ack) để tránh mất
  message khi consumer crash giữa chừng.

---

### Task 6 — Tìm kiếm vé máy bay (Client)

**Tại sao cần:** Đây là entry point chính của người dùng cuối — trải nghiệm tìm
kiếm nhanh/đúng quyết định UX toàn bộ site user.

**Overview:** API public (không cần đăng nhập) cho phép tìm vé theo chặng bay,
ngày, số hành khách, hạng ghế — trả về danh sách vé còn chỗ trống, sắp xếp theo
giá/giờ bay.

**Các bước thực hiện:**
1. Thiết kế `FlightSearchRequest` (origin, destination, departureDate, seatClass,
   passengerCount) + validate input (VD ngày không được ở quá khứ).
2. `FlightSearchService` truy vấn Inventory: lọc `origin, destination`, khoảng
   ngày (theo `departureDate`, không phải giờ chính xác), `availableSeats >=
   passengerCount`, `status = OPEN`.
3. Áp dụng phân trang + sort (giá tăng dần mặc định, cho phép đổi sort theo giờ
   bay).
4. Cân nhắc cache kết quả search phổ biến (VD Redis, TTL ngắn 1-5 phút) nếu sau
   này thấy tải cao — **không bắt buộc ở bản đầu**, chỉ cần biết trước để thiết
   kế service không bị khó thêm cache sau này (tách riêng method load data khỏi
   method format response).
5. Test với dữ liệu đã có từ Task 5 (chạy sync 1 lần để có data thật để search).

**Lưu ý:**
- Đảm bảo API này **không lộ thông tin nhạy cảm** của vé (VD không trả về field
  nội bộ như `sourceSystem`, giá vốn nếu có).

---

### Task 7 — Đặt vé máy bay và thanh toán

**Tại sao cần:** Đây là bước "chốt đơn" — nơi tiền thật (hoặc mock) di chuyển,
và cũng là nơi dễ phát sinh bug nghiêm trọng nhất: **bán vượt số ghế** (overselling)
nếu không xử lý concurrency đúng.

**Overview:** Luồng: user chọn vé từ kết quả search → tạo booking tạm (giữ chỗ)
→ thanh toán → xác nhận booking (trừ ghế thật) hoặc huỷ giữ chỗ nếu thanh toán
thất bại/hết hạn.

**Các bước thực hiện:**
1. Thiết kế entity `Booking` (id, user, inventory, passengerCount, status:
   PENDING/CONFIRMED/CANCELLED/EXPIRED, totalPrice, createdAt, expiresAt) và
   `Payment` (id, booking, provider, status, transactionRef, amount, paidAt).
2. **Giữ chỗ (reserve):** Khi user bấm "đặt vé" → tạo `Booking` status `PENDING`
   + **trừ tạm `availableSeats`** trên Inventory ngay lúc này (không đợi thanh
   toán xong) để tránh 2 người cùng đặt vé cuối cùng. Dùng **optimistic locking**
   (`@Version` trên entity Inventory) hoặc **pessimistic lock** (`SELECT ... FOR
   UPDATE`) để tránh race condition khi nhiều request trừ ghế cùng lúc.
3. Đặt `expiresAt` cho booking PENDING (VD giữ chỗ 15 phút) — cần 1 job định kỳ
   (hoặc `@Scheduled`) quét các booking PENDING quá hạn → chuyển `EXPIRED` + hoàn
   lại `availableSeats`.
4. **Thanh toán:** Tích hợp 1 payment gateway ở chế độ **sandbox/mock** (VD
   VNPay/Momo sandbox, hoặc tự viết 1 mock gateway trả về thành công/thất bại
   giả lập) — không cần gateway thật cho đồ án.
5. Payment gateway callback/webhook → cập nhật `Payment.status` → nếu thành công,
   chuyển `Booking.status = CONFIRMED` (ghế đã trừ từ bước 2, giờ chỉ là xác nhận
   final, không trừ lại lần nữa); nếu thất bại, hoàn ghế + `Booking.status =
   CANCELLED`.
6. API cho user xem lịch sử booking của chính mình (`/bookings/me`).
7. Test kịch bản concurrency: giả lập 2 request đặt cùng 1 vé chỉ còn 1 ghế cùng
   lúc — đảm bảo chỉ 1 request thành công.

**Lưu ý:**
- Đây là nơi cần **transaction** cẩn thận nhất trong toàn bộ dự án — toàn bộ
  bước "trừ ghế + tạo booking" phải nằm trong 1 `@Transactional`.
- Không xử lý thanh toán đồng bộ trong cùng transaction giữ chỗ (gateway có thể
  chậm/timeout) — tách rời 2 bước như mô tả ở trên.

---

## Gợi ý cách dùng giáo án này

Làm theo đúng thứ tự Task 1 → 7. Sau khi xong mỗi task, quay lại báo mình
("xong Task X rồi") để mình review hướng đi/đặt câu hỏi kiểm tra trước khi bạn
sang task tiếp theo — giống một buổi checkpoint với giáo viên hướng dẫn.
