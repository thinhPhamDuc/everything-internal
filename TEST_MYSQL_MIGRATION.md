# Test Case: Kiểm tra sau khi chuyển Postgres → MySQL

> Dùng file này để **tự test tay** sau khi đã áp dụng
> `MIGRATE_POSTGRES_TO_MYSQL.md` (build.gradle, docker-compose.yml,
> application.properties, schema.sql, data.sql đã đổi sang MySQL). Test theo
> đúng thứ tự từ trên xuống — nhóm sau phụ thuộc dữ liệu/trạng thái nhóm
> trước. Mỗi test case có: **Mục đích**, **Cách làm**, **Kết quả mong đợi**.
> Tick `[x]` khi pass, ghi chú lại nếu fail.

Biến dùng chung khi test bằng curl (thay giá trị thật vào sau khi có):

```bash
BASE=http://localhost:8080
TOKEN=""        # set sau khi login admin ở TC-4.3
```

---

## Nhóm A — Hạ tầng Docker (MySQL/RabbitMQ/Redis)

- [ ] **TC-A1. Container MySQL khởi động và healthy**
  - Cách làm: `docker compose up -d && docker compose ps`
  - Kết quả mong đợi: `airline-mysql` status `Up (healthy)`, không restart loop.

- [ ] **TC-A2. Kết nối được vào MySQL bằng client**
  - Cách làm:
    ```bash
    docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e "SELECT 1;"
    ```
  - Kết quả mong đợi: trả về `1`, không lỗi access denied.

- [ ] **TC-A3. Charset DB đúng utf8mb4**
  - Cách làm:
    ```bash
    docker exec -it airline-mysql mysql -uairline_user -pairline_pass -e \
      "SELECT DEFAULT_CHARACTER_SET_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='airline_db';"
    ```
  - Kết quả mong đợi: `utf8mb4` (không phải `latin1`/`utf8` cũ — quan trọng để tiếng Việt có dấu không bị lỗi ô vuông).

- [ ] **TC-A4. RabbitMQ + Redis vẫn healthy (không bị ảnh hưởng bởi đổi DB)**
  - Cách làm: `docker compose ps` — xem `airline-rabbitmq`, `airline-redis`.
  - Kết quả mong đợi: cả 2 `Up (healthy)`.

---

## Nhóm B — App khởi động + schema

- [ ] **TC-B1. Build tải đúng driver MySQL**
  - Cách làm: `./gradlew clean build -x test`
  - Kết quả mong đợi: build thành công, không còn cảnh báo/lỗi liên quan
    `org.postgresql`.

- [ ] **TC-B2. App start sạch, không exception**
  - Cách làm: `./gradlew bootRun`, đọc log tới dòng `Started EverythingInternalApplication`.
  - Kết quả mong đợi: không có stacktrace `CommunicationsException`,
    `Unknown database`, `Access denied`, hay lỗi Hibernate dialect.

- [ ] **TC-B3. Hibernate tạo đủ bảng nghiệp vụ**
  - Cách làm:
    ```bash
    docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e "SHOW TABLES;"
    ```
  - Kết quả mong đợi: có đủ `users`, `roles`, `permissions`,
    `role_permissions`, `flight_ticket_inventory`, `flight_staging_record`,
    `booking`, `payment`, và các bảng `batch_job_instance` (hoặc
    `BATCH_JOB_INSTANCE` tuỳ hoa/thường MySQL trên máy bạn).

- [ ] **TC-B4. Bảng Spring Batch + bảng sequence-giả tạo đủ**
  - Cách làm:
    ```bash
    docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e "SHOW TABLES LIKE 'BATCH%';"
    ```
  - Kết quả mong đợi: đủ 8 bảng `BATCH_JOB_INSTANCE`,
    `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`,
    `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`,
    `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_STEP_EXECUTION_SEQ`,
    `BATCH_JOB_EXECUTION_SEQ`, `BATCH_JOB_INSTANCE_SEQ` (đây là phần rủi ro
    cao nhất khi migrate vì MySQL không có `CREATE SEQUENCE` — nếu thiếu
    bảng nào, Task 5 sẽ lỗi ngay khi Job chạy).

- [ ] **TC-B5. Restart app lần 2 không lỗi (idempotent DDL/DML)**
  - Cách làm: Ctrl+C, chạy lại `./gradlew bootRun`.
  - Kết quả mong đợi: start sạch lần 2, không lỗi `Table already exists`
    hay `Duplicate entry`.

---

## Nhóm C — Data seed (`data.sql`)

- [ ] **TC-C1. Roles seed đủ 3 dòng**
  ```bash
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e "SELECT * FROM roles;"
  ```
  Kết quả mong đợi: đúng 3 dòng `ADMIN`, `STAFF`, `CUSTOMER`.

- [ ] **TC-C2. Permissions seed đủ 5 dòng**
  ```bash
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e "SELECT * FROM permissions;"
  ```
  Kết quả mong đợi: đúng 5 dòng (`USER_READ`, `USER_MANAGE`, `ROLE_MANAGE`,
  `INVENTORY_READ`, `INVENTORY_MANAGE`).

- [ ] **TC-C3. `role_permissions` map đúng ADMIN/STAFF**
  ```bash
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e \
    "SELECT r.name, p.code FROM role_permissions rp JOIN roles r ON r.id=rp.role_id JOIN permissions p ON p.id=rp.permission_id ORDER BY r.name, p.code;"
  ```
  Kết quả mong đợi: ADMIN có đủ 5 permission, STAFF có 4 (thiếu
  `ROLE_MANAGE`), CUSTOMER không có dòng nào.

- [ ] **TC-C4. Seed không bị nhân đôi sau nhiều lần restart**
  - Cách làm: restart app 2-3 lần, chạy lại TC-C1/C2/C3.
  - Kết quả mong đợi: số dòng **không đổi** — xác nhận `INSERT IGNORE` (thay
    cho `ON CONFLICT DO NOTHING` cũ) hoạt động đúng trên MySQL.

---

## Nhóm D — Auth + JWT (Task 1)

- [ ] **TC-D1. Đăng ký thành công**
  ```bash
  curl -s -X POST $BASE/auth/register -H "Content-Type: application/json" -d '{
    "email": "admin.test@example.com",
    "password": "Abc12345@",
    "fullName": "Admin Test"
  }'
  ```
  Kết quả mong đợi: HTTP 200/201, không trả `password` trong response.

- [ ] **TC-D2. Password được hash, không lưu plaintext**
  ```bash
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e \
    "SELECT email, password FROM users WHERE email='admin.test@example.com';"
  ```
  Kết quả mong đợi: cột `password` là chuỗi bcrypt (`$2a$...` hoặc `$2b$...`),
  không phải `Abc12345@`.

- [ ] **TC-D3. Đăng ký trùng email → lỗi rõ ràng (kiểm tra unique constraint MySQL)**
  - Cách làm: gọi lại y hệt TC-D1.
  - Kết quả mong đợi: HTTP 409 (`DuplicateEmailException`), **không phải**
    lỗi 500 `DataIntegrityViolationException` bị lộ ra ngoài — xác nhận
    unique constraint trên MySQL và exception-translation vẫn hoạt động như
    trên Postgres.

- [ ] **TC-D4. Login đúng → nhận JWT**
  ```bash
  curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{
    "email": "admin.test@example.com",
    "password": "Abc12345@"
  }'
  ```
  Kết quả mong đợi: HTTP 200, có field token JWT.

- [ ] **TC-D5. Login sai mật khẩu → lỗi chung chung**
  - Cách làm: gọi lại TC-D4 với password sai.
  - Kết quả mong đợi: lỗi 401, message không tiết lộ "email không tồn tại"
    hay "sai password" riêng biệt.

- [ ] **TC-D6. Gọi `/auth/me` không kèm token → 401**
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" $BASE/auth/me
  ```
  Kết quả mong đợi: `401`.

- [ ] **TC-D7. Gọi `/auth/me` kèm token hợp lệ → 200**
  ```bash
  TOKEN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{"email":"admin.test@example.com","password":"Abc12345@"}' | jq -r .token)
  curl -s $BASE/auth/me -H "Authorization: Bearer $TOKEN"
  ```
  Kết quả mong đợi: HTTP 200, trả đúng thông tin user vừa đăng ký.

> Lưu ý: user `admin.test@example.com` vừa tạo mặc định là role `CUSTOMER`
> (hoặc role mặc định của hệ thống) — muốn test nhóm E (API admin) cần gán
> role `ADMIN` cho user này trực tiếp qua DB (chỉ cho môi trường test):
> ```bash
> docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e \
>   "UPDATE users u JOIN roles r ON r.name='ADMIN' SET u.role_id=r.id WHERE u.email='admin.test@example.com';"
> ```
> (đổi tên cột FK cho khớp thực tế nếu entity `User` đặt tên khác — kiểm tra
> `DESCRIBE users;` nếu câu trên báo lỗi cột không tồn tại).

---

## Nhóm E — CRUD Users (Task 2) + Roles (Task 3)

- [ ] **TC-E1. Admin xem danh sách user (phân trang)**
  ```bash
  curl -s "$BASE/admin/users?page=0&size=10" -H "Authorization: Bearer $TOKEN"
  ```
  Kết quả mong đợi: HTTP 200, có `content`, `totalElements`.

- [ ] **TC-E2. User thường gọi API admin → 403**
  - Cách làm: đăng ký 1 user mới (role CUSTOMER mặc định), login lấy token
    riêng, gọi lại TC-E1 với token đó.
  - Kết quả mong đợi: HTTP 403.

- [ ] **TC-E3. Soft-delete user không xoá cứng khỏi MySQL**
  ```bash
  curl -s -X DELETE $BASE/admin/users/<id> -H "Authorization: Bearer $TOKEN"
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e \
    "SELECT id, email, status, deleted_at FROM users WHERE id=<id>;"
  ```
  Kết quả mong đợi: dòng **vẫn tồn tại** trong bảng, chỉ `status`/`deleted_at`
  đổi — không mất record.

- [ ] **TC-E4. CRUD Role hoạt động (`/admin/roles`)**
  ```bash
  curl -s $BASE/admin/roles -H "Authorization: Bearer $TOKEN"
  ```
  Kết quả mong đợi: HTTP 200, thấy đủ 3 role đã seed.

---

## Nhóm F — Inventory (Task 4)

- [ ] **TC-F1. Tạo inventory mới thành công**
  ```bash
  curl -s -X POST $BASE/admin/inventory -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
    "flightCode": "VN100",
    "airline": "Vietnam Airlines",
    "origin": "SGN",
    "destination": "HAN",
    "departureTime": "2026-12-01T08:00:00",
    "arrivalTime": "2026-12-01T10:00:00",
    "seatClass": "ECONOMY",
    "price": 1500000,
    "totalSeats": 10
  }'
  ```
  Kết quả mong đợi: HTTP 201, `availableSeats = 10` (tự set = totalSeats).

- [ ] **TC-F2. Tạo trùng unique key (flightCode+departureTime+seatClass) → lỗi 409**
  - Cách làm: gọi lại y hệt TC-F1.
  - Kết quả mong đợi: HTTP 409 (`DuplicateInventoryException`) — xác nhận
    composite unique constraint hoạt động đúng trên MySQL/InnoDB.

- [ ] **TC-F3. Optimistic locking (`@Version`) hoạt động trên MySQL**
  - Cách làm: mở 2 terminal, cùng lúc gọi PUT update giá vé của cùng 1
    inventory vừa tạo (dùng đúng payload update hợp lệ ở cả 2, chạy gần như
    đồng thời bằng `&` để chạy song song):
    ```bash
    curl -s -X PUT $BASE/admin/inventory/<id> -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"price": 1600000}' &
    curl -s -X PUT $BASE/admin/inventory/<id> -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"price": 1700000}' &
    wait
    ```
  - Kết quả mong đợi: 1 request 200, request còn lại lỗi (409/500 tuỳ cách
    `GlobalExceptionHandler` map `OptimisticLockException`) — quan trọng vì
    đây là cơ chế chống race-condition dùng ở cả Task 4 lẫn Task 7.

---

## Nhóm G — Sync + Spring Batch (Task 5) — **nhóm rủi ro cao nhất**

- [ ] **TC-G1. Mock API bên thứ 3 trả dữ liệu**
  ```bash
  curl -s $BASE/mock/third-party/flights
  ```
  Kết quả mong đợi: HTTP 200, JSON array vé giả.

- [ ] **TC-G2. Trigger sync thủ công**
  ```bash
  curl -s -X POST $BASE/admin/sync/trigger -H "Authorization: Bearer $TOKEN"
  ```
  Kết quả mong đợi: HTTP 200/202.

- [ ] **TC-G3. Message đi qua đúng 2 queue (RabbitMQ Management UI)**
  - Cách làm: mở `http://localhost:15672` (user/pass `airline_mq`/`airline_mq_pass`),
    xem tab Queues ngay sau khi trigger.
  - Kết quả mong đợi: thấy message chạy qua `flight.sync.trigger` rồi
    `flight.batch.trigger`, không bị kẹt/rơi vào DLQ.

- [ ] **TC-G4. Staging record được ghi vào MySQL**
  ```bash
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e \
    "SELECT batch_id, processed, count(*) FROM flight_staging_record GROUP BY batch_id, processed;"
  ```
  Kết quả mong đợi: có record mới, `processed` chuyển từ `0` → `1` sau khi
  batch chạy xong.

- [ ] **TC-G5. Spring Batch Job chạy thành công trên schema MySQL**
  ```bash
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e \
    "SELECT JOB_EXECUTION_ID, STATUS, EXIT_CODE FROM BATCH_JOB_EXECUTION ORDER BY JOB_EXECUTION_ID DESC LIMIT 5;"
  ```
  Kết quả mong đợi: `STATUS = COMPLETED`. **Đây là test quan trọng nhất của
  toàn bộ migration** — nếu bảng `BATCH_*_SEQ` (TC-B4) sai định dạng, Job sẽ
  fail ngay ở bước sinh `JOB_INSTANCE_ID`/`JOB_EXECUTION_ID`.

- [ ] **TC-G6. Inventory được cập nhật sau batch**
  ```bash
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e \
    "SELECT flight_code, source_system, last_synced_at FROM flight_ticket_inventory WHERE source_system <> 'MANUAL' ORDER BY last_synced_at DESC LIMIT 5;"
  ```
  Kết quả mong đợi: có dòng mới/được update, `last_synced_at` gần thời điểm
  vừa trigger.

- [ ] **TC-G7. Idempotency: trigger lại đúng batch cũ không tạo trùng JobInstance**
  - Cách làm: trigger sync 2 lần liên tiếp trong cùng 1 chu kỳ (nếu code
    dùng batchId làm JobParameter identifying).
  - Kết quả mong đợi: Spring Batch chặn chạy lại `JobInstance` trùng
    `JOB_NAME + JOB_KEY` (kiểm tra không có 2 `JOB_INSTANCE_ID` khác nhau
    cho cùng 1 batchId nếu logic yêu cầu vậy — đối chiếu lại với thiết kế
    thật của `FlightImportJobConfig`).

---

## Nhóm H — Search + Redis Cache (Task 6)

- [ ] **TC-H1. Search có dữ liệu (sau khi đã chạy Nhóm G ít nhất 1 lần)**
  ```bash
  curl -s "$BASE/flights/search?origin=SGN&destination=HAN&departureDate=2026-12-01&seatClass=ECONOMY&passengerCount=1"
  ```
  Kết quả mong đợi: HTTP 200, list vé sort theo giá tăng dần, không thấy
  field nội bộ (`sourceSystem`...).

- [ ] **TC-H2. Cache hit ở Redis sau lần search đầu**
  ```bash
  docker exec -it airline-redis redis-cli KEYS '*'
  ```
  Kết quả mong đợi: thấy key cache mới xuất hiện ngay sau TC-H1.

- [ ] **TC-H3. Redis chết vẫn không làm hỏng search (đọc fallback MySQL)**
  ```bash
  docker stop airline-redis
  curl -s -o /dev/null -w "%{http_code}\n" "$BASE/flights/search?origin=SGN&destination=HAN&departureDate=2026-12-01&seatClass=ECONOMY&passengerCount=1"
  docker start airline-redis
  ```
  Kết quả mong đợi: vẫn `200`, không bị treo lâu (timeout 500ms đã cấu hình).

---

## Nhóm I — Booking + Payment (Task 7)

- [ ] **TC-I1. Đặt vé thành công, trừ ghế ngay**
  ```bash
  curl -s -X POST $BASE/bookings -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{
    "inventoryId": <id-vé-vừa-tạo>,
    "passengerCount": 1
  }'
  docker exec -it airline-mysql mysql -uairline_user -pairline_pass airline_db -e \
    "SELECT id, available_seats FROM flight_ticket_inventory WHERE id=<id>;"
  ```
  Kết quả mong đợi: Booking status `PENDING`, `available_seats` giảm ngay
  (không đợi thanh toán).

- [ ] **TC-I2. Race condition: 2 request đặt cùng lúc vé chỉ còn 1 ghế**
  - Cách làm: set 1 inventory `available_seats = 1`, bắn 2 request `POST
    /bookings` gần như đồng thời (dùng `&` + `wait` như TC-F3).
  - Kết quả mong đợi: **chỉ 1** request thành công, request còn lại lỗi
    `SeatUnavailableException`/409 — xác nhận `@Version` optimistic lock vẫn
    chống được overselling trên MySQL/InnoDB giống Postgres trước đây.

- [ ] **TC-I3. Thanh toán thành công → Booking CONFIRMED**
  ```bash
  curl -s -X POST $BASE/bookings/<bookingId>/pay -H "Authorization: Bearer $TOKEN"
  ```
  Kết quả mong đợi: HTTP 200, `status = CONFIRMED`, ghế **không** bị trừ
  thêm lần nữa.

- [ ] **TC-I4. Booking PENDING quá hạn tự chuyển EXPIRED + hoàn ghế**
  - Cách làm: đặt 1 booking mới không thanh toán, đợi đủ
    `app.booking.hold-minutes` (hoặc set tạm giá trị nhỏ trong
    `application.properties` để test nhanh, nhớ trả lại sau khi test xong),
    chờ `BookingExpiryScheduler` chạy (`app.booking.expiry-cron`).
  - Kết quả mong đợi: `status = EXPIRED`, `available_seats` của inventory
    được cộng lại đúng số đã trừ.

- [ ] **TC-I5. Xem lịch sử booking của chính mình**
  ```bash
  curl -s $BASE/bookings/me -H "Authorization: Bearer $TOKEN"
  ```
  Kết quả mong đợi: HTTP 200, chỉ thấy booking của user đang login, không
  thấy booking của user khác.

---

## Nhóm J — Test tự động (JUnit)

- [ ] **TC-J1. Toàn bộ test suite pass trên MySQL**
  ```bash
  ./gradlew test
  ```
  Kết quả mong đợi: `BUILD SUCCESSFUL`. Chú ý các test sau — trước đây chạy
  trực tiếp trên Postgres thật (không mock), nay phải tự chạy đúng trên
  MySQL vì code chỉ cần "có DB thật đang chạy":
  - `RoleServiceTest`, `RoleControllerHttpTest`
  - `UserSoftDeleteTest`, `UserStatusTest`, `UserAuthorizationTest`,
    `UserLazyLoadingTest`, `UserControllerHttpTest`
  - `InventoryServiceTest`, `InventoryControllerHttpTest`
  - `FlightStagingRepositoryTest`, `FlightStagingReaderConfigTest`
  - `FlightStagingProcessorTest`, `FlightInventoryWriterTest`

  Nếu 1 test cụ thể fail, chạy riêng để đọc log rõ hơn:
  ```bash
  ./gradlew test --tests "com.app.internal.role.RoleServiceTest"
  ```

---

## Checklist dọn dẹp sau khi test xong

- [ ] Xoá user/inventory/booking test tạo ra trong lúc test (hoặc
  `docker compose down -v && docker compose up -d` để làm sạch DB, chạy lại
  app để `schema.sql`/`data.sql` seed lại từ đầu).
- [ ] Trả lại `app.booking.hold-minutes` / `app.sync.cron` về giá trị dev
  bình thường nếu đã chỉnh tạm để test nhanh ở TC-I4.
- [ ] Nếu tất cả nhóm A→J đều pass: cập nhật `MIGRATE_POSTGRES_TO_MYSQL.md`
  đánh dấu migration đã verify xong, có thể tiếp tục code task mới.
