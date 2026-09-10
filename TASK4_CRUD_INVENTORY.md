# Task 4 — CRUD Inventory (Vé Máy Bay): Giáo Án Tự Làm (Step by Step)

> Cùng tinh thần `TASK2_CRUD_USERS.md` và `TASK3_CRUD_ROLES.md`: mình **không
> implement hộ business logic** — chỉ đưa method signature + TODO + gợi ý, còn
> code bên trong bạn tự viết để nhớ. Vài chỗ thuần cú pháp framework (composite
> index, `@Version`, Bean Validation cho số...) mình cho code mẫu thật vì đó là
> thứ để tra cứu, không phải thứ cần tự nghĩ ra.
>
> Xong bước nào, tự test bước đó trước khi qua bước tiếp. Task 4 không có bước
> "phá vỡ compile" rộng như Task 3 (đây là entity hoàn toàn mới, không sửa cái
> cũ) — nhưng có nhiều quyết định thiết kế field ảnh hưởng tới Task 5/6/7 sau
> này, nên đọc kỹ phần "tại sao" trước khi gõ code, đừng chỉ copy field.

---

## Trạng thái hiện có (đọc trước khi bắt đầu)

Từ Task 1-3, project đã có sẵn — Task 4 sẽ **dùng lại pattern**, không phát
minh lại:

- Mô hình phân quyền `User – Role – Permission` đã chạy (`role/` package):
  `RoleController` (`/admin/roles`) khoá bằng `hasRole('ADMIN')`,
  `UserController` (`/admin/users`) khoá bằng `hasAuthority('USER_MANAGE')`.
  Permission hiện có: `USER_READ`, `USER_MANAGE`, `ROLE_MANAGE` — seed trong
  `data.sql`.
- Pattern CRUD chuẩn đã lặp lại 2 lần (`User`, `Role`): Controller mỏng gọi
  Service → Service xử lý logic + `@Transactional` → Repository
  `extends JpaRepository` → DTO tách riêng input/output, không trả entity ra
  API → exception riêng theo domain, đăng ký handler ở
  `GlobalExceptionHandler`.
- Soft-delete pattern đã có ở `User` (`deletedAt` + `status = INACTIVE`, giữ
  nguyên row vì `Booking.user` tham chiếu FK) — Task 4 sẽ áp dụng tinh thần
  tương tự cho Inventory (lý do ở Bước 8).
- `Booking` entity hiện tại (`booking/entity/Booking.java`) mới chỉ có
  `id, user, flightCode (String), createdAt` — đây là bảng **tối giản, tạm
  thời**, chỉ để phục vụ test `LazyInitializationException` ở Task 2, **chưa**
  có quan hệ tới Inventory. Quan hệ `Booking → Inventory` thật sự là việc của
  Task 7, Task 4 **không cần** đụng tới `Booking`.
- `application.properties`: `spring.jpa.hibernate.ddl-auto=update` (Hibernate
  tự tạo bảng mới theo entity, không tự xoá/sửa cột cũ), đã có
  `spring.sql.init.mode=always` + `spring.jpa.defer-datasource-initialization=true`
  (từ Task 3, để `data.sql` seed đúng thứ tự) — 2 dòng này **dùng lại được
  luôn** cho việc seed permission mới của Task 4, không cần thêm gì.

**Vấn đề Task 4 phải giải quyết:** hiện **chưa có bảng nào lưu dữ liệu vé máy
bay**. `GIAO_AN.md` gọi đây là "trái tim của hệ thống" — Task 5 (đồng bộ tự
động) sẽ ghi vào bảng này, Task 6 (search) và Task 7 (đặt vé) sẽ đọc/ghi lên
nó. Thiết kế sai field/index ở Task 4 sẽ khiến 3 task sau phải quay lại sửa,
nên đầu tư kỹ ở bước entity.

---

## Bước 0 — Quyết định phạm vi & field (đọc kỹ trước khi code)

**Việc cần làm:** tạo package mới `inventory/` với đủ
`controller/service/repository/entity/dto`, đúng cây thư mục đã đề xuất ở
`GIAO_AN.md`.

**Field bắt buộc (theo `GIAO_AN.md` mục Task 4)** — tự quyết định kiểu dữ liệu
cho từng field, có gợi ý kèm lý do, không có đáp án tuyệt đối:

| Field | Gợi ý kiểu | Vì sao cân nhắc |
|---|---|---|
| `flightCode` | `String` | Mã chuyến bay, VD `"VN123"` — định danh nghiệp vụ, không phải khoá chính DB. |
| `airline` | `String` | Tên/mã hãng bay. |
| `origin`, `destination` | `String` (mã sân bay, VD `"HAN"`, `"SGN"`) | Sẽ là cột lọc nóng nhất khi search (Task 6) — giữ ngắn, dùng mã chuẩn IATA thay vì tên đầy đủ để so khớp chính xác, không phụ thuộc chính tả. |
| `departureTime`, `arrivalTime` | `LocalDateTime` | Giờ bay thật, không phải "ngày bay" — search (Task 6) sẽ lọc theo ngày từ field này. |
| `seatClass` | **Tự quyết định `String` hay `enum`** | Khác với `Permission.code` ở Task 3 (nên để `String` vì mở rộng tự do) — hạng ghế (`ECONOMY/BUSINESS/FIRST`) là tập giá trị **cố định, hiếm khi đổi**, nên cân nhắc `enum` để có type-safety lúc compile. Tự cân nhắc, không có đáp án tuyệt đối. |
| `price` | **`BigDecimal`, không dùng `Double`** | Tiền tệ dùng `double`/`float` dễ dính lỗi làm tròn số học dấu phẩy động (VD `0.1 + 0.2 != 0.3`) — đây là lỗi kinh điển, bắt buộc tránh khi có Task 7 (thanh toán) sau này. |
| `totalSeats`, `availableSeats` | `Integer` | Ràng buộc `availableSeats <= totalSeats` xử lý ở Service (Bước 8), không xử lý được ở tầng DB column type. |
| `status` | **Tự quyết định `String` hay `enum`** | Giá trị cố định `OPEN/CLOSED/CANCELLED` — cùng logic cân nhắc như `seatClass`. |
| `sourceSystem` | `String` (VD `"MANUAL"` khi admin tự nhập tay, `"SYNC"` khi Task 5 đổ vào) | Để phân biệt vé nhập tay và vé đồng bộ tự động — chưa dùng ngay ở Task 4 nhưng khai báo sẵn cho Task 5. |
| `lastSyncedAt` | `LocalDateTime`, nullable | `null` với vé nhập tay thủ công, chỉ Task 5 mới set giá trị này. |

**Câu hỏi tự trả lời trước khi qua Bước 1:** vì sao `flightCode` **không** nên
là khoá chính (`@Id`) của bảng, dù nghe có vẻ là định danh chuyến bay? (Gợi ý:
1 `flightCode` có thể lặp lại ở nhiều `departureTime` khác nhau (chuyến bay
hàng ngày) và nhiều `seatClass` khác nhau (economy/business) — khoá chính vẫn
nên là `id` tự sinh, còn định danh nghiệp vụ thật là **tổ hợp 3 field**
`flightCode + departureTime + seatClass`, xem Bước 1 phần unique constraint.)

**Tự kiểm tra:** chưa test được ở bước này — làm tiếp Bước 1.

---

## Bước 1 — Entity `FlightTicketInventory`

**Việc cần làm:** tạo `inventory/entity/FlightTicketInventory.java` với các
field đã chốt ở Bước 0.

**Cú pháp mẫu cho unique constraint tổ hợp nhiều cột** (chỗ này cho code thật
vì thuần cú pháp JPA — `@Table(uniqueConstraints = ...)` hay bị quên tên
tường minh nếu tự tra cứu lần đầu, mà tên constraint rõ ràng sẽ giúp đọc log
lỗi dễ hơn nhiều khi vi phạm):

```java
@Entity
@Table(
        name = "flight_ticket_inventory",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inventory_flight_departure_class",
                columnNames = {"flight_code", "departure_time", "seat_class"}
        )
)
public class FlightTicketInventory {
    // ... các field đã chốt ở Bước 0
}
```

**Tại sao unique constraint này quan trọng ngay từ Task 4:** `GIAO_AN.md` Task
5 đã nêu rõ Writer của Spring Batch sẽ "upsert vào bảng Inventory theo unique
key `flightCode + departureTime + seatClass`" — nếu không khai báo ràng buộc
này từ Task 4, tới Task 5 bạn sẽ phải `ALTER TABLE` thêm constraint trên bảng
đã có dữ liệu, phức tạp hơn nhiều so với khai báo ngay từ đầu.

**Quyết định tuỳ chọn (đọc kỹ, không bắt buộc làm ngay):** `GIAO_AN.md` Task 7
nói tới **optimistic locking bằng `@Version`** để tránh bán vượt số ghế khi
nhiều request trừ `availableSeats` cùng lúc. Bạn có 2 lựa chọn:

1. Thêm cột `@Version private Long version;` **ngay từ Task 4** — không tốn
   công gì thêm bây giờ (Hibernate tự quản lý field này), nhưng đỡ phải sửa
   entity (thêm cột mới) lúc code Task 7.
2. Bỏ qua ở Task 4, thêm sau ở Task 7 khi thật sự cần.

Gợi ý: làm luôn (1) — chi phí gần như bằng 0, và tránh phải nhớ quay lại sửa
entity core sau 3 task nữa.

**Tự kiểm tra:** start app, kiểm tra bằng `psql` xem bảng
`flight_ticket_inventory` đã được Hibernate tự tạo đúng cột + đúng
constraint tên `uk_inventory_flight_departure_class` chưa (`\d
flight_ticket_inventory` trong `psql`).

---

## Bước 2 — Index cho cột hay lọc

**Tại sao cần:** `GIAO_AN.md` ghi rõ đây là "truy vấn nóng nhất của toàn hệ
thống" — Task 6 (search) sẽ luôn lọc theo `origin, destination, departureTime`
cùng lúc. Không có composite index, mỗi query search sẽ full table scan khi
dữ liệu lớn dần (sau Task 5 đổ hàng nghìn dòng vào).

**Cú pháp mẫu** (thuần JPA annotation, cho code thật):

```java
@Table(
        name = "flight_ticket_inventory",
        indexes = @Index(
                name = "idx_inventory_route_departure",
                columnList = "origin, destination, departure_time"
        ),
        uniqueConstraints = @UniqueConstraint(/* như Bước 1 */)
)
```

**Câu hỏi tự trả lời:** vì sao thứ tự cột trong composite index
(`origin, destination, departure_time`) lại quan trọng, không phải liệt kê
tuỳ ý? (Gợi ý: tìm hiểu cách B-Tree index hoạt động với multi-column — index
dùng hiệu quả nhất khi query lọc theo đúng thứ tự cột từ trái sang, hoặc ít
nhất lọc được cột đầu tiên. Đặt cột nào lọc chắc chắn có mặt trong mọi query
search lên trước.)

**Tự kiểm tra:** `\d flight_ticket_inventory` trong `psql`, xác nhận thấy
index `idx_inventory_route_departure` bên cạnh index tự động của unique
constraint.

---

## Bước 3 — Repository

**Việc cần làm:** tạo `inventory/repository/InventoryRepository.java`. Phần
derived query đơn giản cho code mẫu (đã quen cú pháp từ Task 2/3), phần lọc
nhiều điều kiện tự viết:

```java
public interface InventoryRepository extends JpaRepository<FlightTicketInventory, Long> {
    Optional<FlightTicketInventory> findByFlightCodeAndDepartureTimeAndSeatClass(
            String flightCode, LocalDateTime departureTime, String seatClass);

    // TODO: tự viết derived method (hoặc @Query) cho list có lọc theo
    // origin/destination/status + Pageable — tham khảo cách UserRepository
    // đã làm ở Task 2 (findByStatusAndEmailContainingIgnoreCase) nếu muốn
    // dùng derived method thuần, hoặc dùng Specification nếu số điều kiện
    // lọc nhiều/động hơn UserRepository.
    Page<FlightTicketInventory> findByOriginAndDestination(
            String origin, String destination, Pageable pageable);
}
```

**Tự kiểm tra:** viết 1 test JUnit nhỏ (giống pattern các test Task 2/3) —
save 1 `FlightTicketInventory`, tìm lại bằng
`findByFlightCodeAndDepartureTimeAndSeatClass`, assert đúng. Thử save bản ghi
thứ 2 với đúng 3 field đó (flightCode/departureTime/seatClass) → xác nhận ném
`DataIntegrityViolationException` (unique constraint hoạt động).

---

## Bước 4 — DTO

**Việc cần làm:** tạo trong `inventory/dto/`, tự quyết định field theo đúng
tinh thần Task 2/3 (input/output tách riêng, không lộ entity ra ngoài):

- `InventoryResponse` — record, đủ field để admin xem: `id, flightCode,
  airline, origin, destination, departureTime, arrivalTime, seatClass, price,
  totalSeats, availableSeats, status, sourceSystem, lastSyncedAt`.
- `InventoryCreateRequest` — toàn bộ field nhập tay khi admin tạo vé mới +
  validate (`@NotBlank` cho String, `@NotNull @Positive` cho `price`/
  `totalSeats`, `@Future` cho `departureTime` — tự cân nhắc field nào bắt
  buộc). **Câu hỏi tự trả lời:** `availableSeats` có nên là field input của
  `InventoryCreateRequest` không, hay luôn tự set bằng `totalSeats` lúc tạo
  mới? (Gợi ý: luôn bằng `totalSeats` lúc mới tạo — 1 vé vừa tạo thì chưa ai
  đặt, để admin tự nhập 2 số độc lập dễ tạo ra trạng thái vô lý ngay từ đầu,
  giống lý do Task 2 không cho `UserUpdateRequest` sửa `status` trực tiếp.)
- `InventoryUpdateRequest` — **tự quyết định cho sửa field nào**. Gợi ý: chỉ
  `price`, `totalSeats`, `airline` — **không** cho sửa `flightCode`,
  `origin`, `destination`, `departureTime`, `seatClass` qua API update. Vì
  sao? Tự trả lời dựa trên lý do đã áp dụng ở Bước 0 (tổ hợp 3 field này là
  unique constraint/định danh nghiệp vụ — đổi field định danh của 1 bản ghi
  đang tồn tại rất dễ gây nhầm lẫn dữ liệu, nên tạo bản ghi vé mới thay vì sửa
  vé cũ đổi hẳn sang chuyến bay khác).
- `InventoryUpdateStatusRequest` — 1 field `status` (enum hoặc String tuỳ
  Bước 0), theo đúng pattern `UserUpdateStatusRequest` đã có ở Task 2.

---

## Bước 5 — Exception

**Việc cần làm:** tạo trong `common/exception/`, theo đúng pattern
`UserNotFoundException`/`RoleNotFoundException` đã có:

- `InventoryNotFoundException` — ném khi tìm `id` không tồn tại.
- `InvalidSeatCountException` (hoặc tên khác tự đặt) — ném khi vi phạm ràng
  buộc nghiệp vụ `availableSeats <= totalSeats` hoặc `availableSeats < 0`
  (chi tiết TODO ở Bước 8).
- **Tự quyết định:** vi phạm unique constraint (`flightCode + departureTime +
  seatClass` trùng khi tạo mới) nên tự bắt bằng cách check trước
  (`findByFlightCodeAndDepartureTimeAndSeatClass` rồi ném exception rõ ràng,
  giống `existsByEmail` ở `AuthService.register()`), hay để DB tự ném
  `DataIntegrityViolationException` rồi bắt exception đó ở
  `GlobalExceptionHandler`? Gợi ý: check trước — trả lỗi nghiệp vụ rõ ràng
  (409 kèm message dễ hiểu) tốt hơn để lộ lỗi kỹ thuật tầng DB ra API, đúng
  tinh thần đã áp dụng cho `deleteRole` ở Task 3.

**Đăng ký handler:** thêm `@ExceptionHandler` tương ứng trong
`GlobalExceptionHandler.java`, theo đúng format các handler đã có (HTTP status
+ `ApiError`).

**Tự kiểm tra:** chưa test được — cần Service ở Bước 6 để ném ra các
exception này.

---

## Bước 6 — `InventoryService`

**Việc cần làm:** tạo `inventory/service/InventoryService.java`. Method
signature + TODO (tự viết business logic bên trong):

```java
@Transactional(readOnly = true)
public Page<InventoryResponse> listInventory(String origin, String destination, Pageable pageable) {
    // TODO: gọi repository, map sang InventoryResponse - tham khảo
    // UserService.listUsers() đã làm ở Task 2
}

@Transactional(readOnly = true)
public InventoryResponse getInventoryById(Long id) {
    // TODO: tìm theo id, ném InventoryNotFoundException nếu không có
}

@Transactional
public InventoryResponse createInventory(InventoryCreateRequest request) {
    // TODO:
    // 1. Check trùng (flightCode + departureTime + seatClass) - xem Bước 5
    // 2. Validate availableSeats <= totalSeats (xem Bước 8)
    // 3. Set availableSeats = totalSeats (xem câu hỏi tự trả lời ở Bước 4)
    // 4. Set sourceSystem = "MANUAL" (nhập tay qua API này, phân biệt với Task 5)
    // 5. Save, map sang InventoryResponse
}

@Transactional
public InventoryResponse updateInventory(Long id, InventoryUpdateRequest request) {
    // TODO: tìm theo id, chỉ set field được phép sửa (xem Bước 4), validate
    // lại ràng buộc availableSeats <= totalSeats nếu totalSeats bị đổi nhỏ
    // hơn availableSeats hiện có
}

@Transactional
public InventoryResponse changeStatus(Long id, String newStatus) {
    // TODO: tìm theo id, set status - tham khảo UserService.changeStatus()
    // đã làm ở Task 2 cho cách chặn giá trị status không hợp lệ
}

@Transactional
public void closeInventory(Long id) {
    // TODO: xem "Bẫy cần tự tránh" ở Bước 8 trước khi viết method này -
    // đây KHÔNG phải xoá cứng
}
```

**Tự kiểm tra:** chưa test HTTP được — cần Controller ở Bước 7. Có thể viết
test JUnit gọi thẳng Service (giống `UserStatusTest`) để test trước logic
validate.

---

## Bước 7 — Permission mới cho Inventory

**Tại sao cần bước riêng:** khác với Task 2 (tận dụng lại permission có sẵn),
Task 4 là 1 domain hoàn toàn mới — theo đúng tinh thần Task 3 (tách "ai là
ADMIN" khỏi "ai được làm gì"), Inventory cần permission riêng, không tái dùng
`USER_MANAGE`.

**Việc cần làm:**

1. Thêm permission mới vào `data.sql`, theo đúng format đã có (tự viết câu
   `INSERT ... ON CONFLICT DO NOTHING`, tham khảo cách Task 3 đã seed
   `USER_READ`/`USER_MANAGE`): `INVENTORY_READ`, `INVENTORY_MANAGE`.
2. **Tự quyết định gán cho role nào.** Gợi ý dựa theo overview gốc của dự án
   (`GIAO_AN.md` mục 0: "nhân viên vận hành chỉ CRUD inventory"):
   - `ADMIN`: cả `INVENTORY_READ` + `INVENTORY_MANAGE` (và mọi permission
     khác đã có).
   - `STAFF`: cả `INVENTORY_READ` + `INVENTORY_MANAGE` — đây chính là lý do
     nghiệp vụ role `STAFF` tồn tại, khác với Task 3 (STAFF lúc đó chỉ có
     `USER_READ`/`USER_MANAGE`).
   - `CUSTOMER`: không có permission nào (giữ nguyên).

**Tự kiểm tra:** start lại app, query
`SELECT r.name, p.code FROM roles r JOIN role_permissions rp ON r.id =
rp.role_id JOIN permissions p ON p.id = rp.permission_id ORDER BY r.name;`
trong `psql`, xác nhận `STAFF` giờ có thêm 2 dòng `INVENTORY_READ`/
`INVENTORY_MANAGE`.

---

## Bước 8 — Ràng buộc nghiệp vụ (tư duy trước, code sau)

**Đọc kỹ mục này trước khi viết TODO ở Bước 6** — đây là phần cốt lõi của
Task 4, không phải cú pháp framework.

1. **`availableSeats <= totalSeats`, không cho âm.** Tự quyết định validate
   ở đâu: Bean Validation (`@AssertTrue` custom trên DTO — validate được 1
   field so với field khác cùng request) hay validate tay trong Service
   (so sánh 2 field trước khi save)? Gợi ý: Service — vì ràng buộc này phải
   đúng **cả** lúc tạo mới lẫn lúc update (2 DTO khác nhau), để logic ở 1 chỗ
   dễ bảo trì hơn lặp lại annotation ở nhiều DTO.

2. **Không cho xoá cứng vé đã có booking.** `GIAO_AN.md` ghi rõ điều này,
   nhưng đọc kỹ "Trạng thái hiện có" ở đầu file: `Booking` hiện tại **chưa**
   có quan hệ FK tới Inventory (đó là việc của Task 7) — nên bạn **chưa thể
   check "đã có booking chưa" bằng query thật** ở Task 4. Tự quyết định:
   - Vẫn áp dụng nguyên tắc chung ngay từ bây giờ — **không bao giờ
     `DELETE`** bản ghi Inventory qua API, chỉ đổi `status = CANCELLED`
     (giống soft-delete User ở Task 2). Đây là lựa chọn **nên làm**, vì nó
     không phụ thuộc việc Task 7 đã xong hay chưa, và nhất quán với pattern
     đã dùng cho User.
   - Không cần (và **đừng**) cố viết check "còn booking hay không" ngay bây
     giờ khi bảng đó chưa tồn tại thật — đó là việc thêm ở Task 7 khi
     `Booking.inventory` đã là FK thật, tránh code logic kiểm tra dữ liệu
     chưa từng tồn tại.

3. **Đặt tên method xoá cho đúng bản chất:** vì hành động thật sự là "đóng
   vé" chứ không phải xoá, đặt tên `closeInventory`/`cancelInventory` thay vì
   `deleteInventory` giúp code tự giải thích, tránh gây hiểu lầm cho người
   đọc sau này rằng có `DELETE` thật.

**Tự kiểm tra:** viết test JUnit gọi `createInventory` với
`availableSeats > totalSeats` trong request (nếu bạn chọn cho phép input này
ở Bước 4) hoặc test `updateInventory` giảm `totalSeats` xuống dưới
`availableSeats` hiện có — xác nhận ném đúng exception, không lưu xuống DB.

---

## Bước 9 — `InventoryController`

**Việc cần làm:** tạo `inventory/controller/InventoryController.java`, base
path `/admin/inventory`, bảo vệ bằng `@PreAuthorize` cấp class — tự quyết
định dùng permission nào (gợi ý `hasAuthority('INVENTORY_MANAGE')` cho toàn
bộ endpoint ghi, hoặc tách riêng `GET` dùng `INVENTORY_READ` nếu muốn phân
quyền chi tiết hơn — tự cân nhắc độ phức tạp cần thiết, `UserController` ở
Task 3 chọn gộp chung 1 permission cho cả class để đơn giản).

| Method | Path | Việc gì |
|---|---|---|
| GET | `/admin/inventory` | Danh sách vé, filter theo `origin`/`destination`, phân trang |
| GET | `/admin/inventory/{id}` | Chi tiết 1 vé |
| POST | `/admin/inventory` | Tạo vé mới (nhập tay) |
| PUT | `/admin/inventory/{id}` | Sửa `price`/`totalSeats`/`airline` (xem Bước 4) |
| PATCH | `/admin/inventory/{id}/status` | Đổi status (giống `PATCH /admin/users/{id}/status`) |
| DELETE | `/admin/inventory/{id}` | **Đóng vé** (set `CANCELLED`), không xoá cứng — xem Bước 8 |

Tự viết signature + `@Valid` cho body input, theo đúng pattern
`UserController`/`RoleController` đã làm.

**Tự kiểm tra:** gọi thử qua Postman/curl từng endpoint bằng token ADMIN,
xác nhận đúng status code; gọi lại bằng token `CUSTOMER` (không có
`INVENTORY_MANAGE`), xác nhận `403`.

---

## Bước 10 — Test toàn luồng thủ công (checklist)

Tự đi qua từng dòng, tick khi pass:

- [ ] Bảng `flight_ticket_inventory` được tạo đúng cột, đúng unique
      constraint, đúng composite index (`\d` trong `psql`).
- [ ] `POST /admin/inventory` tạo vé mới thành công, `availableSeats` tự
      động bằng `totalSeats`.
- [ ] Tạo lại đúng 1 vé trùng `flightCode + departureTime + seatClass` →
      bị chặn với lỗi nghiệp vụ rõ ràng (409), không phải lỗi 500 từ DB.
- [ ] `PUT /admin/inventory/{id}` giảm `totalSeats` xuống dưới
      `availableSeats` hiện có → bị chặn.
- [ ] `GET /admin/inventory?origin=HAN&destination=SGN` trả đúng danh sách
      lọc, có phân trang.
- [ ] `DELETE /admin/inventory/{id}` không xoá row khỏi DB — chỉ đổi
      `status` thành `CANCELLED` (tự kiểm tra bằng `psql`, không chỉ tin
      response API).
- [ ] Token `STAFF` (đã gán `INVENTORY_MANAGE` ở Bước 7) gọi được toàn bộ
      endpoint `/admin/inventory/**`.
- [ ] Token `CUSTOMER` gọi bất kỳ endpoint `/admin/inventory/**` nào → `403`.

---

## Bước 11 (tuỳ chọn) — Test tự động

Viết test theo đúng pattern đã dùng ở Task 2/3 cho ít nhất: tạo vé trùng
unique key bị chặn, `updateInventory` vi phạm ràng buộc số ghế bị chặn,
`closeInventory` không xoá row, `STAFF` gọi được `/admin/inventory` qua HTTP
thật (nhớ `.apply(springSecurity())` khi build `MockMvc`, bài học đã ghi ở
`TASK3_CRUD_ROLES.md` Bước 13).

Không bắt buộc để coi là "xong Task 4", nhưng rất đáng làm trước khi sang
Task 5 — lúc đó Spring Batch sẽ ghi thẳng vào đúng bảng này, càng cần chắc
ràng buộc `availableSeats <= totalSeats` và unique constraint không bị phá
khi có nguồn ghi dữ liệu thứ 2 (đồng bộ tự động, song song với nhập tay).

---

## Khi nào coi là xong Task 4?

Tick hết checklist Bước 10, tự tin giải thích được (không cần nhìn code) 4
câu hỏi sau:

1. Vì sao unique constraint đặt trên tổ hợp `flightCode + departureTime +
   seatClass`, không phải chỉ `flightCode`?
2. Vì sao `price` dùng `BigDecimal` thay vì `Double`?
3. Vì sao `DELETE /admin/inventory/{id}` không xoá row thật, dù tại Task 4
   `Booking` còn chưa hề tham chiếu tới Inventory?
4. Vì sao `STAFF` được gán `INVENTORY_MANAGE` ở Task 4, khác với Task 3 (lúc
   đó `STAFF` chỉ có quyền quản lý User)?

Xong thì quay lại báo mình như thường lệ để review trước khi sang Task 5
(luồng đồng bộ tự động — Scheduler → RabbitMQ → mock 3rd-party → Spring
Batch → chính bảng Inventory vừa xây ở Task 4 này).
