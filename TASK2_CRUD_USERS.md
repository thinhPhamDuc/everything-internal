# Task 2 — CRUD Users: Giáo Án Tự Làm (Step by Step)

> Khác với `GIAO_AN.md` (chỉ nêu overview + các bước ở mức cao cho cả 7 task),
> file này đào sâu **riêng Task 2**, chia nhỏ thành các bước thật cụ thể để bạn
> tự gõ code, không copy-paste. Đúng tinh thần ban đầu (`requirement-self.md`):
> **mình không implement hộ business logic** — chỉ đưa method signature +
> TODO + gợi ý, còn code bên trong bạn tự viết để nhớ. Vài chỗ thuần cú pháp
> framework (Pageable, `@PreAuthorize`...) mình cho code mẫu thật vì đó là thứ
> để tra cứu, không phải thứ cần tự nghĩ ra.
>
> Xong bước nào, tự test bước đó trước khi qua bước tiếp — đừng viết hết 6
> bước rồi mới chạy thử, sẽ khó biết lỗi nằm ở đâu.
>
> Có bí ở đâu, hoặc code chạy ra kết quả lạ, quay lại hỏi mình — câu trả lời
> sẽ được chốt vào `QA_KIEN_THUC.md` như mọi lần.

---

## Trạng thái hiện có (đọc trước khi bắt đầu)

Từ Task 1, project đã có sẵn — Task 2 sẽ **dùng lại**, không tạo trùng:

- `User` entity (`user/entity/User.java`): `id, email, password, fullName,
  status, roles, createdAt, deletedAt, bookings`.
- `UserRepository` (`auth/repository/UserRepository.java`): đang nằm trong
  package `auth`, không phải `user` như cây thư mục đề xuất ở `GIAO_AN.md` —
  đây là 1 khoản "nợ kỹ thuật" nhỏ từ lúc code Task 1, **cứ dùng lại repository
  này**, đừng tạo `UserRepository` thứ 2 trong `user/repository/`. Nếu muốn dọn
  cho sạch (move package), để cuối cùng làm, không phải việc bắt buộc của
  Task 2.
- `UserService` (`user/service/UserService.java`): mới chỉ có
  `softDeleteUser(Long userId)` — Task 2 sẽ **mở rộng thêm method** vào đúng
  class này, không tạo class song song.
- Auth đã hoạt động: `POST /auth/register`, `POST /auth/login` trả JWT chứa
  `sub=userId` và `roles` (mảng, hiện tại luôn có đúng 1 phần tử vì `User.roles`
  là 1 String, không phải list). `JwtFilter` tự map role thành authority dạng
  `ROLE_<role>` (VD role `"ADMIN"` → authority `"ROLE_ADMIN"`).
- `SecurityConfig`: mọi endpoint đều yêu cầu `authenticated()` trừ
  `/auth/register`, `/auth/login`. **Chưa có phân quyền theo role** — mọi user
  đã login đều gọi được mọi API như nhau. Đây là lỗ hổng Task 2 phải vá.

**Vấn đề cần giải quyết trước tiên:** `AuthService.register()` luôn gán cứng
`roles = "CUSTOMER"` — hiện **chưa có cách nào tạo tài khoản ADMIN** qua API.
Xem Bước 0.

---

## Bước 0 — Tạo 1 tài khoản ADMIN để test

**Tại sao cần:** Không có tài khoản ADMIN thì không thể tự test được các
endpoint chỉ-dành-cho-ADMIN ở các bước sau.

**Cách làm (chọn 1 trong 2):**

1. **Đăng ký bình thường qua `POST /auth/register`, rồi sửa tay trong DB:**
   ```sql
   -- Chạy trong psql (docker exec -it airline-postgres psql -U airline_user -d airline_db)
   UPDATE users SET roles = 'ADMIN' WHERE email = 'admin@example.com';
   ```
   Ưu điểm: không đụng code, không sợ quên đổi lại. **Khuyến nghị dùng cách
   này.**

2. Tạm sửa `DEFAULT_ROLE` trong `AuthService` thành `"ADMIN"`, register 1 tài
   khoản, rồi **nhớ đổi lại `"CUSTOMER"`** trước khi commit. Dễ quên đổi lại,
   dễ bug — chỉ dùng nếu không tiện mở psql.

**Tự kiểm tra:** login bằng tài khoản này (`POST /auth/login`), decode JWT trả
về (dán vào [jwt.io] hoặc tự thêm log tạm trong `JwtProvider`) — confirm
`roles: ["ADMIN"]`.

---

## Bước 1 — DTO cho response: `UserResponse`

**Tại sao cần:** Nhắc lại nguyên tắc đã áp dụng ở Task 1 (`RegisterResponse`)
— **không bao giờ trả thẳng entity `User` ra API**, vì lộ `password` (dù đã
hash) và làm response phụ thuộc cứng vào schema DB.

**Việc cần làm:** Tạo file `user/dto/UserResponse.java`, dạng `record` (giống
`RegisterResponse`).

**Tự quyết định:** field nào nên có trong response? Gợi ý cân nhắc — không
cho đáp án:
- Chắc chắn cần: `id, email, fullName, status, roles, createdAt`.
- `deletedAt` có nên trả về không? Nếu listing (Bước 3) đã lọc bỏ user đã xoá
  thì field này trong response luôn là `null`, có thừa không? Nhưng nếu sau
  này bạn muốn cho admin xem cả user đã xoá (audit) thì field này lại hữu ích.
  Tự quyết, không có đáp án đúng/sai tuyệt đối ở bước này.

**Tự kiểm tra:** chưa test được ở bước này (chưa có gì gọi tới DTO) — làm
tiếp Bước 2, 3 rồi test chung.

---

## Bước 2 — DTO cho update: `UserUpdateRequest`

**Tại sao cần:** Input để admin sửa thông tin user — tách riêng khỏi
`UserResponse` vì input và output không nên dùng chung 1 DTO (input cần
validate, output thì không).

**Việc cần làm:** Tạo `user/dto/UserUpdateRequest.java`.

**Câu hỏi tự trả lời trước khi code** (quan trọng hơn code):
- Admin được sửa field nào của user? Chắc chắn: `fullName`. Còn `email`? Nếu
  cho sửa, phải tự lo lại đúng vấn đề unique constraint + check trùng đã gặp ở
  Task 1 (`Câu 1, Phần 1` trong `QA_KIEN_THUC.md`) — **gợi ý: bước đầu, đừng
  cho sửa `email`**, giữ scope nhỏ, thêm sau nếu cần.
- Có nên cho `UserUpdateRequest` chứa `status` hoặc `roles` không? **Gợi ý:
  KHÔNG** — đổi status (khoá/mở) và đổi role là 2 hành động nhạy cảm, nên có
  endpoint riêng, dễ audit/log riêng, tránh 1 endpoint "update chung chung" âm
  thầm cho phép admin tự nâng quyền cho ai đó qua 1 field ẩn trong body.

**Việc cần làm tiếp:** Thêm validate bằng Bean Validation (`@NotBlank`...) như
`RegisterRequest` đã làm.

---

## Bước 3 — Mở rộng `UserRepository` cho danh sách + lọc

**Tại sao cần:** Endpoint `GET /admin/users` cần: phân trang (dữ liệu user có
thể rất nhiều, trả hết 1 lần sẽ chậm/tốn băng thông) + lọc theo `status`/`email`
(theo đúng "Lưu ý" Task 2 ở `GIAO_AN.md`).

**Khái niệm mới cần đọc trước khi code — Spring Data `Pageable`/`Page`:**
- `Pageable` (interface `org.springframework.data.domain.Pageable`): gói thông
  tin "trang số mấy, mỗi trang bao nhiêu dòng, sort theo field nào" — Spring
  MVC tự bind từ query param `?page=0&size=20&sort=createdAt,desc` nếu bạn
  khai `Pageable` làm tham số controller (xem Bước 5).
- `Page<T>`: kết quả trả về của 1 query phân trang — không chỉ có `content`
  (list dữ liệu của đúng trang này) mà còn `totalElements, totalPages,
  number`... Spring Data JPA tự tính, bạn không cần tự đếm.

**Việc cần làm:** Thêm method vào `UserRepository` (derived query method —
Spring Data JPA tự sinh SQL từ tên method, không cần viết `@Query` tay ở mức
lọc đơn giản này):

```java
// Gợi ý chữ ký method — tự nghĩ tên tham số, tự quyết định có cho phép
// status/email null (nghĩa là "không lọc field đó") hay bắt buộc phải có.
Page<User> findByStatusAndEmailContainingIgnoreCase(String status, String email, Pageable pageable);
```

**Câu hỏi tự trả lời:** Nếu admin muốn xem TẤT CẢ user (không lọc gì) thì
method trên có dùng được không? (Gợi ý: derived query method match chính xác
tham số truyền vào — muốn "optional filter" thật sự linh hoạt cần
`Specification<User>` hoặc tách nhiều method riêng cho từng tổ hợp filter. Ở
mức MVP, có thể chấp nhận: bắt buộc luôn truyền `status`, còn `email` cho phép
chuỗi rỗng `""` để coi như "không lọc" — vì `LIKE '%%'` khớp mọi email. Tự cân
nhắc đánh đổi.)

**Tự kiểm tra:** chưa gọi được qua HTTP ở bước này — viết 1 test JUnit nhỏ
(theo đúng pattern `UserLazyLoadingTest`/`UserSoftDeleteTest` đã có: seed vài
user bằng `userRepository.save()`, gọi method mới, assert `page.getContent()`
đúng số lượng/đúng nội dung) để chắc chắn query đúng trước khi lắp vào
Controller.

---

## Bước 4 — Mở rộng `UserService`

**Tại sao cần:** Controller không nên gọi thẳng Repository — giữ nguyên
pattern đã có (`AuthService` gọi `UserRepository`, không phải `AuthController`
gọi thẳng).

**Việc cần làm:** Thêm các method vào `UserService` (file đã có sẵn
`softDeleteUser`, viết thêm bên cạnh, đừng tạo file mới):

```java
public UserResponse getUserById(Long userId) {
    // TODO: tìm user theo id, ném UserNotFoundException nếu không có
    // (exception này đã có sẵn từ soft-delete, dùng lại)
    // TODO: map User -> UserResponse (tự viết method map tay, hoặc constructor
    // của record nhận thẳng entity - tự chọn cách nào rõ ràng hơn với bạn)
}

public Page<UserResponse> listUsers(String status, String email, Pageable pageable) {
    // TODO: gọi UserRepository.findByStatusAndEmailContainingIgnoreCase(...)
    // TODO: map Page<User> -> Page<UserResponse> (gợi ý: Page có sẵn method
    // .map(Function) rất tiện cho đúng việc này, tìm hiểu javadoc của nó)
}

@Transactional
public UserResponse updateUser(Long userId, UserUpdateRequest request) {
    // TODO: tìm user, set field mới, KHÔNG cần gọi save() (nhắc lại đúng lý do
    // đã ghi trong QA_KIEN_THUC.md: entity lấy từ findById() trong transaction
    // là managed, Hibernate tự dirty-checking)
}

@Transactional
public UserResponse changeStatus(Long userId, String newStatus) {
    // TODO: tìm user, validate newStatus có hợp lệ không (ACTIVE/LOCKED - tự
    // định nghĩa hằng số, đừng hard-code string rải rác nhiều nơi), set status
}
```

**Bẫy cần tự tránh (không phải TODO code, mà TODO tư duy):**
- `changeStatus` và `softDeleteUser` đều set field `status` trên cùng 1 entity
  — nếu 1 user đang bị `LOCKED` rồi bị gọi `softDeleteUser`, `status` sẽ bị
  ghi đè thành `INACTIVE`. Đây có phải hành vi đúng ý không? (Gợi ý: đúng —
  "xoá" nên là trạng thái cuối cùng, ghi đè lock là hợp lý. Nhưng bạn nên tự
  quyết định và ghi lý do vào comment, đừng để mặc định ngầm không ai hiểu vì
  sao.)
- `updateUser` có nên cho sửa user đã bị soft-delete (`deletedAt != null`)
  không? Tự quyết — gợi ý: không nên, ném lỗi rõ ràng thay vì âm thầm cho sửa
  1 tài khoản coi như "không tồn tại".

---

## Bước 5 — `UserController`

**Tại sao cần:** Lớp REST API thật sự, dành cho admin thao tác.

**Việc cần làm:** Tạo `user/controller/UserController.java`, base path
`/admin/users` (khớp đúng tên đã dùng trong `GIAO_AN.md`).

**Endpoint cần có** (tự viết signature + gọi xuống `UserService`, theo đúng
pattern `AuthController` đã làm — `ResponseEntity`, `@Valid` cho body input):

| Method | Path | Việc gì |
|---|---|---|
| GET | `/admin/users` | Danh sách, phân trang, lọc |
| GET | `/admin/users/{id}` | Chi tiết 1 user |
| PUT | `/admin/users/{id}` | Sửa thông tin (`UserUpdateRequest`) |
| PATCH | `/admin/users/{id}/status` | Đổi status (khoá/mở) |
| DELETE | `/admin/users/{id}` | Soft-delete (gọi lại `softDeleteUser` đã có) |

**Gợi ý cú pháp cho endpoint list (đây là chỗ code mẫu thật, vì thuần cú pháp
Spring MVC, không phải logic nghiệp vụ):**

```java
@GetMapping
public ResponseEntity<Page<UserResponse>> list(
        @RequestParam String status,
        @RequestParam(defaultValue = "") String email,
        @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
    return ResponseEntity.ok(userService.listUsers(status, email, pageable));
}
```

`@PageableDefault` cho giá trị mặc định nếu client không truyền `page/size/sort`
trên query string — không có nó, Spring vẫn tự bind `Pageable` nhưng mặc định
`size=20` không có sort cụ thể.

**Câu hỏi tự trả lời cho endpoint DELETE:** trả HTTP status nào cho đúng chuẩn
REST — `200 OK` với body rỗng, hay `204 No Content`? Tự tra cứu, tự quyết
định, cả 2 đều được chấp nhận rộng rãi miễn nhất quán trong cả project.

---

## Bước 6 — Bảo vệ endpoint: chỉ ADMIN mới gọi được

**Tại sao cần:** Đây là lỗ hổng đã nhắc ở đầu file — hiện tại mọi user login
xong đều gọi được mọi API như nhau.

**Việc cần làm 1 — bật method security** (chưa có trong `SecurityConfig`):

```java
@Configuration
@EnableMethodSecurity   // <-- thêm annotation này, thiếu nó @PreAuthorize bị lờ đi hoàn toàn, không báo lỗi gì
@RequiredArgsConstructor
public class SecurityConfig {
    ...
}
```

**Việc cần làm 2 — áp `@PreAuthorize` lên `UserController`:**

```java
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")   // áp cho TOÀN BỘ method trong class này
@RequiredArgsConstructor
public class UserController {
    ...
}
```

**Lưu ý cú pháp hay nhầm:** viết `hasRole('ADMIN')`, **KHÔNG** viết
`hasRole('ROLE_ADMIN')` — Spring Security tự thêm tiền tố `ROLE_` khi so khớp
`hasRole(...)`. `JwtFilter` (đã có sẵn) đã tự tạo authority đúng dạng
`ROLE_ADMIN` rồi, nên `hasRole('ADMIN')` là đúng, thêm `ROLE_` vào sẽ thành
so khớp `ROLE_ROLE_ADMIN` — sai, luôn trả `403` dù token đúng.

**Tự kiểm tra:**
1. Gọi `GET /admin/users` bằng token ADMIN (Bước 0) → phải thành công.
2. Gọi lại đúng API đó bằng token 1 user `CUSTOMER` thường (register bình
   thường) → phải nhận `403 Forbidden`.
3. Gọi không kèm token → phải nhận `401 Unauthorized` (do
   `.anyRequest().authenticated()` ở `SecurityConfig` xử lý trước khi tới được
   `@PreAuthorize`).

Nếu case 2 lại trả `200` thay vì `403` → khả năng cao là quên
`@EnableMethodSecurity`, hoặc gõ nhầm `hasRole('ROLE_ADMIN')` theo chiều
ngược lại (thiếu tiền tố ở phía token nhưng thừa ở phía check, hoặc ngược lại)
— tự debug bằng cách log thử `authentication.getAuthorities()` (xem lại
`AuthController.me()` đã có sẵn logic in ra field này, gọi thử `/auth/me` bằng
đúng token đang test để soi xem authority thật sự là gì).

---

## Bước 7 — Nối lại 1 lỗ hổng cũ: `login()` chưa chặn user bị khoá

**Tại sao cần:** Bước 6 đã có endpoint khoá tài khoản (`PATCH
/admin/users/{id}/status`), nhưng nếu không sửa thêm, **user bị khoá vẫn login
bình thường** — khoá tài khoản chỉ có tác dụng "trên giấy", không tác dụng
thật.

Bối cảnh: `AuthService.login()` hiện tại (từ lúc làm soft-delete) chỉ chặn
`deletedAt != null` qua `findByEmailAndDeletedAtIsNull(...)`, **chưa** kiểm
tra `status`.

**Việc cần tự làm:** Thêm 1 điều kiện check `status` trong `login()`, ném
`BadCredentialsException` giống hệt cách đã làm với case sai mật khẩu (đúng
nguyên tắc "không lộ chi tiết lỗi" đã ghi trong `GIAO_AN.md` Task 1 — thông
báo lỗi khoá tài khoản không nên khác thông báo sai mật khẩu, tránh lộ thông
tin tài khoản có tồn tại và đang bị khoá).

**Tự kiểm tra:** login bằng 1 tài khoản vừa bị `PATCH .../status` thành
`LOCKED` (Bước 6) → phải nhận lỗi login thất bại, không nhận được token.

---

## Bước 8 — Test toàn luồng thủ công (checklist)

Tự đi qua từng dòng, tick khi pass, đừng bỏ qua dòng nào:

- [ ] Admin list user: có phân trang đúng (`totalElements`, `content.size()`
      khớp `size` truyền vào hoặc ít hơn nếu ở trang cuối).
- [ ] Admin filter theo `status=ACTIVE` → chỉ thấy user đang active.
- [ ] Admin filter theo `email` (1 phần email) → tìm đúng, không phân biệt
      hoa/thường (đã dùng `ContainingIgnoreCase` ở Bước 3).
- [ ] Admin xem chi tiết 1 user bằng đúng `id` → đúng thông tin, KHÔNG có
      field `password` trong response.
- [ ] Admin xem chi tiết user với `id` không tồn tại → `404`, đúng message từ
      `UserNotFoundException` đã có sẵn.
- [ ] Admin sửa `fullName` → query lại `GET /admin/users/{id}` thấy đổi đúng.
- [ ] Admin khoá 1 user (`status=LOCKED`) → user đó login thất bại (Bước 7).
- [ ] Admin mở khoá lại (`status=ACTIVE`) → user đó login lại thành công.
- [ ] Admin xoá 1 user (`DELETE`) → user đó login thất bại, nhưng row **vẫn
      còn trong DB** (`SELECT * FROM users WHERE id=...` vẫn thấy, có
      `deleted_at` khác null) — đúng tinh thần soft-delete đã làm trước đó.
- [ ] User thường (`CUSTOMER`) gọi bất kỳ endpoint `/admin/users/**` nào →
      `403` cho tất cả, không có endpoint nào "lọt lưới".
- [ ] Không kèm token gọi `/admin/users` → `401`.

---

## Bước 9 (tuỳ chọn) — Test tự động

Nếu muốn chắc chắn hơn thay vì test tay mỗi lần sửa code, viết thêm test theo
đúng pattern đã dùng ở `UserSoftDeleteTest.java` (SpringBootTest chạy trên
Postgres thật, không mock) cho ít nhất các case: list có phân trang đúng,
update thành công, khoá xong login fail, user thường gọi API admin bị 403.

Việc này không bắt buộc để coi là "xong Task 2", nhưng rất đáng làm trước khi
chuyển sang Task 3 (khi đó `roles`/`status` sẽ phức tạp hơn, có test sẵn giúp
đổi code Task 3 mà không sợ phá vỡ Task 2).

---

## Khi nào coi là xong Task 2?

Tick hết checklist Bước 8, tự tin giải thích được (không cần nhìn code) 3 câu
hỏi sau — nếu trả lời trôi chảy nghĩa là đã hiểu, không chỉ copy chạy được:

1. Vì sao `UserUpdateRequest` không có field `status`/`roles`?
2. Vì sao `hasRole('ADMIN')` đúng mà `hasRole('ROLE_ADMIN')` sai?
3. Vì sao thêm `@PreAuthorize` ở Bước 6 vẫn chưa đủ — phải sửa thêm
   `login()` ở Bước 7 thì việc "khoá tài khoản" mới thật sự có tác dụng?

Xong thì quay lại báo mình như thường lệ để review trước khi sang Task 3.
