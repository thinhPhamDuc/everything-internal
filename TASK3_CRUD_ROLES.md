# Task 3 — CRUD Roles/Permissions: Giáo Án Tự Làm (Step by Step)

> Cùng tinh thần `TASK2_CRUD_USERS.md`: mình **không implement hộ business
> logic** — chỉ đưa method signature + TODO + gợi ý, còn code bên trong bạn tự
> viết để nhớ. Vài chỗ thuần cú pháp framework (JPA relation, `@PreAuthorize`,
> `data.sql`...) mình cho code mẫu thật vì đó là thứ để tra cứu, không phải thứ
> cần tự nghĩ ra.
>
> Xong bước nào, tự test bước đó trước khi qua bước tiếp — Task 3 có 1 bước
> "phá vỡ compile" khá rộng (Bước 3), nên càng cần đi từng bước nhỏ, đừng viết
> hết rồi mới chạy thử.

---

## Trạng thái hiện có (đọc trước khi bắt đầu)

Từ Task 1 + Task 2, project đang **hard-code role dạng String** ở nhiều nơi —
đây chính là "nợ kỹ thuật" mà Task 3 phải dọn:

- `User.java`: field `private String roles` — **luôn chỉ có 1 giá trị**
  ("ADMIN" hoặc "CUSTOMER"), không phải bảng quan hệ thật.
- `AuthService.register()`: `DEFAULT_ROLE = "CUSTOMER"` hard-code cứng, không
  có cách nào tạo role mới ngoài sửa code.
- `AuthService.login()`: `List<String> roles = List.of(user.getRoles())` —
  bọc 1 String thành List 1 phần tử, để khớp chữ ký `JwtProvider.generateToken`.
- `JwtProvider.generateToken(Long userId, List<String> roles)`: nhúng roles
  vào claim `"roles"` của JWT.
- `JwtFilter`: đọc claim `"roles"`, tự thêm tiền tố `ROLE_` cho từng phần tử
  → authority Spring Security (VD `"ADMIN"` → `"ROLE_ADMIN"`).
- `SecurityConfig`: đã có `@EnableMethodSecurity` (bật từ Task 2).
- `UserController`: `@PreAuthorize("hasRole('ADMIN')")` hard-code role string
  ở cấp class.
- `application.properties`: `spring.jpa.hibernate.ddl-auto=update` — Hibernate
  tự **thêm** cột/bảng theo entity, nhưng **không tự xoá** cột cũ khi bạn xoá
  field khỏi entity. Lưu ý này quan trọng ở Bước 3.
- Chưa có Flyway/Liquibase, chưa có `data.sql`.

**Vấn đề Task 3 phải giải quyết:** thay hard-code role string bằng bảng
`Role`/`Permission` thật, để admin tự tạo role mới / gán quyền chi tiết mà
không cần sửa code.

---

## Bước 0 — Quyết định phạm vi (đọc kỹ trước khi code)

`GIAO_AN.md` cho phép 2 mức độ, tự chọn:

1. **Đầy đủ**: `Role` + `Permission` (many-to-many) + `User → Role`.
2. **Rút gọn (MVP)**: chỉ `Role`, bỏ hẳn `Permission` chi tiết, `@PreAuthorize`
   vẫn check theo tên role như hiện tại nhưng role load từ DB thay vì hard-code.

File này viết theo hướng **đầy đủ** (Role + Permission) vì nền tảng đã sẵn có
phần "map role → authority" ở `JwtFilter`, thêm Permission không tốn nhiều
công hơn bao nhiêu. Nếu bạn thấy quá tải, có thể dừng ở mức rút gọn — bỏ qua
Bước 1 (Permission) và Bước 8 (gán permission), các bước còn lại vẫn áp dụng
được cho hướng rút gọn.

**Quyết định thứ 2 cần chốt trước:** quan hệ `User ↔ Role` là
**many-to-one** (1 user 1 role) hay **many-to-many** (1 user nhiều role)?

Gợi ý: chọn **many-to-one**. Lý do — `JwtFilter`/`JwtProvider` hiện tại đã
ngầm giả định mỗi user chỉ có đúng 1 role (`roles` truyền vào luôn là list 1
phần tử). Đổi sang many-to-many sẽ phải sửa lại cấu trúc claim JWT lẫn
`JwtFilter` — không sai, nhưng là 1 quyết định kiến trúc lớn hơn phạm vi Task
3. Many-to-one vừa đủ để thay hard-code string bằng bảng thật, mà không phải
đập lại toàn bộ luồng JWT đã chạy ổn từ Task 1. File này viết theo hướng
many-to-one — nếu bạn vẫn muốn many-to-many, tự điều chỉnh Bước 3 + Bước 10.

---

## Bước 1 — Entity `Permission`

**Tại sao cần tạo trước `Role`:** `Role` sẽ tham chiếu tới `Permission` qua
quan hệ many-to-many — tạo `Permission` trước để có bảng cho `Role` trỏ vào.

**Việc cần làm:** tạo `role/entity/Permission.java`.

**Tự quyết định field:** gợi ý cân nhắc — không cho đáp án:
- Chắc chắn cần: `id`, `code` (unique — VD `"USER_READ"`, `"USER_MANAGE"`,
  `"ROLE_MANAGE"`), `description`.
- `code` nên là `String` hay `enum`? Gợi ý: `String` — để sau này thêm
  permission mới chỉ cần insert DB (qua seed hoặc admin tool), không cần sửa
  code + build lại. Đánh đổi: mất type-safety lúc compile, dễ gõ sai chuỗi
  (tự cân nhắc, không có đáp án tuyệt đối).

**Tự kiểm tra:** chưa test được ở bước này — làm tiếp Bước 2.

---

## Bước 2 — Entity `Role`

**Việc cần làm:** tạo `role/entity/Role.java`.

**Tự quyết định field:** `id`, `name` (unique — VD `"ADMIN"`, `"STAFF"`,
`"CUSTOMER"`), `description`, và quan hệ tới `Permission`.

**Cú pháp mẫu cho quan hệ many-to-many** (chỗ này cho code thật vì thuần cú
pháp JPA, không phải business logic — annotation `@JoinTable` hay bị gõ sai
tên cột nếu tự tra cứu lần đầu):

```java
@ManyToMany(fetch = FetchType.LAZY)
@JoinTable(
        name = "role_permissions",
        joinColumns = @JoinColumn(name = "role_id"),
        inverseJoinColumns = @JoinColumn(name = "permission_id")
)
private Set<Permission> permissions = new HashSet<>();
```

**Câu hỏi tự trả lời:** vì sao dùng `Set` thay vì `List` cho collection
many-to-many? (Gợi ý: Hibernate với nhiều collection `List` cùng lúc trên 1
entity dễ dính lỗi `MultipleBagFetchException`; `Set` an toàn hơn, và về mặt
nghiệp vụ "tập hợp permission của 1 role" vốn không nên có phần tử trùng lặp
hay quan tâm thứ tự — `Set` diễn tả đúng ý nghĩa đó hơn `List`.)

**Tự kiểm tra:** chưa test được — cần `Role` gắn với `User` trước (Bước 3) để
có dữ liệu thật mà test.

---

## Bước 3 — Sửa entity `User`: thay cột `roles` (String) bằng quan hệ tới `Role`

**Đây là bước "phá vỡ compile" rộng nhất Task 3** — vì `User.roles` hiện là
`String`, đang được dùng trực tiếp ở nhiều nơi. Đọc hết mục này trước khi sửa.

**Việc cần làm trong `User.java`:** xoá field `private String roles`, thay
bằng:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "role_id", nullable = false)
private Role role;
```

**Vấn đề cột DB cũ:** `spring.jpa.hibernate.ddl-auto=update` chỉ **thêm**
cột/bảng mới (cột `role_id`, bảng `roles`, `role_permissions`), **không tự
xoá** cột `roles` (String) cũ trên bảng `users`. Tự quyết định: xoá tay bằng
1 câu `ALTER TABLE users DROP COLUMN roles;` chạy trong `psql`, hay để đó
không dùng tới (thừa nhưng không hại)? Gợi ý: xoá tay cho sạch, vì đang ở giai
đoạn dev, không có dữ liệu thật cần giữ.

**Liệt kê trước — những chỗ đang gọi `user.getRoles()` / `.roles(...)` sẽ vỡ
compile ngay khi bạn đổi field, tự sửa lần lượt từng chỗ:**

- `AuthService.register()` — dòng `.roles(DEFAULT_ROLE)` trong `User.builder()`.
  Cần đổi `DEFAULT_ROLE` (hiện là `String "CUSTOMER"`) thành logic tìm đúng
  `Role` entity tên `"CUSTOMER"` từ `RoleRepository` (làm ở Bước 4) rồi
  `.role(customerRole)`.
- `AuthService.login()` — dòng `List<String> roles = List.of(user.getRoles())`.
  Cần đổi thành lấy tên role từ `user.getRole().getName()`.
- `UserService` (`getUserById`, `listUsers`, `updateUser`, `changeStatus`) —
  cả 4 method đang gọi `user.getRoles()` khi build `UserResponse`. Đổi thành
  `user.getRole().getName()`.
- `UserResponse` (record) — field `roles` hiện kiểu `String`. Tự quyết định
  đổi tên field thành `roleName` cho rõ nghĩa hơn không (gợi ý: nên, tên cũ dễ
  gây hiểu lầm là danh sách nhiều role). **Không trả thẳng entity `Role`** ra
  response — vẫn giữ đúng nguyên tắc DTO đã áp dụng từ Task 1/2.
- **Test cũ** (`UserSoftDeleteTest`, `UserLazyLoadingTest`, `UserStatusTest`,
  `UserAuthorizationTest`, `UserControllerHttpTest`) — tất cả đang gọi
  `.roles("CUSTOMER")` trong `User.builder()` ở `setUp()`. Cần sửa thành
  `.role(mộtRoleEntityCóSẵn)` — nghĩa là mỗi test phải tự seed (hoặc load từ
  `RoleRepository`) 1 `Role` trước khi build `User`. Đây là lúc bạn thấm rõ vì
  sao Bước 5 (seed data) cần làm sớm.

**Tự kiểm tra:** chạy `./gradlew compileJava compileTestJava` ngay sau bước
này — **chấp nhận nhiều lỗi compile hiện ra**, đây chính là danh sách việc
cần làm, không phải bug. Sửa hết tới khi compile sạch mới qua bước sau.

---

## Bước 4 — Repository

**Việc cần làm:** tạo 2 file, thuần derived query method (không cần TODO,
đây là cú pháp đã quen từ Task 2):

```java
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(String name);
}

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByCode(String code);
    List<Permission> findByCodeIn(List<String> codes);
}
```

**Tự kiểm tra:** viết 1 test JUnit nhỏ (giống pattern `UserSoftDeleteTest`) —
save 1 `Role`, `findByName` lại, assert đúng.

---

## Bước 5 — Seed data (`ADMIN`/`STAFF`/`CUSTOMER`)

**Tại sao cần làm sớm:** Bước 3 đã chỉ ra — sửa xong `User` thì mọi chỗ tạo
`User` (kể cả test) đều cần có sẵn ít nhất 1 `Role` trong DB để gán.

**Việc cần làm:** tạo `src/main/resources/data.sql`.

**Bẫy cần tự tránh (2 điểm dễ bị bỏ sót với Postgres + JPA):**

1. Mặc định Spring Boot chỉ tự chạy `data.sql` cho database **embedded**
   (H2...). Project dùng Postgres thật → phải tự bật, thêm vào
   `application.properties`:
   ```properties
   spring.sql.init.mode=always
   ```
2. Mặc định, `data.sql` chạy **trước** khi Hibernate tạo bảng theo entity (vì
   `ddl-auto=update` chạy ở pha khởi tạo `EntityManagerFactory`, muộn hơn pha
   chạy SQL init) → insert vào bảng chưa tồn tại → lỗi. Phải thêm:
   ```properties
   spring.jpa.defer-datasource-initialization=true
   ```
   để buộc Hibernate tạo bảng xong trước, `data.sql` chạy sau.

**Tự quyết định nội dung insert:** tối thiểu 3 role + vài permission mẫu +
gán permission cho role — tự thiết kế bảng permission nào thuộc role nào (VD
`ADMIN` có hết, `STAFF` chỉ có `USER_READ`+`USER_MANAGE`, `CUSTOMER` không có
permission quản trị nào).

**Bẫy idempotency:** `data.sql` chạy lại **mỗi lần start app** (vì
`ddl-auto=update` không xoá bảng cũ giữa các lần chạy) → `INSERT` thường sẽ
báo lỗi trùng khoá ở lần chạy thứ 2. Dùng cú pháp Postgres:
```sql
INSERT INTO roles (name, description) VALUES ('ADMIN', 'Toàn quyền quản trị')
ON CONFLICT (name) DO NOTHING;
```
(muốn dùng được `ON CONFLICT (name)`, cột `name` phải có ràng buộc
`UNIQUE` — tự kiểm tra lại entity `Role` đã khai `unique = true` chưa.)

**Tự kiểm tra:** start app 2 lần liên tiếp, không có lỗi. Viết test gọi
`roleRepository.findByName("ADMIN")`, xác nhận có kèm đúng permission đã gán.

---

## Bước 6 — DTO

**Việc cần làm:** tạo trong `role/dto/`, tự quyết định field theo đúng tinh
thần Task 2 (input/output tách riêng, không lộ entity ra ngoài):

- `RoleResponse` — chắc chắn cần `id, name, description`, và danh sách
  permission (gợi ý: trả `List<String>` mã permission, không trả thẳng
  entity `Permission`).
- `RoleCreateRequest` — `name`, `description` + validate `@NotBlank`.
- `RoleUpdateRequest` — chỉ nên có `description`. **Câu hỏi tự trả lời:** vì
  sao không nên cho sửa `name`? (Gợi ý: `name` là định danh nghiệp vụ, dùng
  để so khớp ở nhiều nơi — VD `DEFAULT_ROLE`/seed data/`@PreAuthorize` cũ.
  Đổi tên 1 role đang được N user tham chiếu là hành động rủi ro cao, nên tạo
  role mới thay vì sửa tên role cũ, giống lý do Task 2 không cho sửa `status`
  qua `UserUpdateRequest`.)
- `AssignPermissionRequest` — danh sách mã permission muốn gán cho 1 role.

---

## Bước 7 — `RoleService`

**Việc cần làm:** tạo `role/service/RoleService.java`. Method signature +
TODO (tự viết business logic bên trong):

```java
public List<RoleResponse> listRoles() {
    // TODO: lấy toàn bộ Role, map sang RoleResponse
}

public RoleResponse getRoleById(Long id) {
    // TODO: tìm role theo id, ném exception nếu không có (tạo mới
    // RoleNotFoundException, theo đúng pattern UserNotFoundException đã có ở
    // common/exception, nhớ đăng ký thêm handler trong GlobalExceptionHandler)
}

@Transactional
public RoleResponse createRole(RoleCreateRequest request) {
    // TODO: check trùng name (giống existsByEmail ở AuthService.register),
    // tạo Role mới
}

@Transactional
public RoleResponse updateRole(Long id, RoleUpdateRequest request) {
    // TODO: tìm role, set description (KHÔNG set name - xem Bước 6)
}

@Transactional
public void deleteRole(Long id) {
    // TODO: tìm role, xoá - xem "Bẫy cần tự tránh" ngay dưới trước khi code
}

@Transactional
public RoleResponse assignPermissions(Long roleId, AssignPermissionRequest request) {
    // TODO: tìm role, tìm list Permission theo codes (PermissionRepository.findByCodeIn),
    // set/replace vào role.getPermissions()
}
```

**Bẫy cần tự tránh (tư duy, không phải code):**
- `User.role` đang khai `nullable = false` (Bước 3) — nếu `deleteRole` xoá 1
  role đang có user tham chiếu, sẽ vi phạm ràng buộc khoá ngoại. Tự quyết
  định: chặn xoá nếu còn user đang dùng role đó (ném exception rõ ràng), hay
  bắt buộc phải chuyển hết user sang role khác trước? Gợi ý: chặn xoá — đúng
  tinh thần "đừng âm thầm để lỗi FK ở tầng DB thay vì báo lỗi nghiệp vụ rõ
  ràng ở tầng service" đã áp dụng cho soft-delete User ở Task 2.
- `assignPermissions` là **replace toàn bộ** danh sách permission hay
  **thêm vào** danh sách hiện có? Tự quyết định, nêu rõ trong tên method nếu
  cần (VD `replacePermissions` nếu chọn replace).

---

## Bước 8 — API gán role cho user

**Tự quyết định đặt ở đâu:** `UserService` (mở rộng thêm, đừng tạo class
song song) hay `RoleService`? Gợi ý: `UserService` — hành động này về bản
chất là "sửa 1 field của User", giống đúng tinh thần `changeStatus` đã làm ở
Task 2 (đổi field nhạy cảm → có method + endpoint riêng, dễ audit).

```java
@Transactional
public UserResponse assignRole(Long userId, Long roleId) {
    // TODO: tìm user, tìm role, set user.setRole(role)
}
```

**Endpoint:** `PATCH /admin/users/{id}/role` trong `UserController` (đặt
cạnh `PATCH /{id}/status` đã có) — tự viết DTO input (`AssignRoleRequest`
chứa `roleId`), tự quyết định trả `200`/`204` (nhất quán với cách đã chọn ở
Task 2).

---

## Bước 9 — `RoleController`

**Việc cần làm:** tạo `role/controller/RoleController.java`, base path
`/admin/roles`, bảo vệ bằng `@PreAuthorize("hasRole('ADMIN')")` cấp class
(giống hệt `UserController`).

| Method | Path | Việc gì |
|---|---|---|
| GET | `/admin/roles` | Danh sách role |
| GET | `/admin/roles/{id}` | Chi tiết 1 role |
| POST | `/admin/roles` | Tạo role mới |
| PUT | `/admin/roles/{id}` | Sửa `description` |
| DELETE | `/admin/roles/{id}` | Xoá role (xem bẫy Bước 7) |
| PUT | `/admin/roles/{id}/permissions` | Gán danh sách permission cho role |

Tự viết signature + `@Valid` cho body input, theo đúng pattern
`UserController` đã làm.

---

## Bước 10 — Nhúng permission vào JWT (đánh đổi cần tự cân nhắc)

**Trade-off cần hiểu trước khi code** (không có đáp án đúng/sai tuyệt đối):
- **Nhúng permission vào token** (giống cách `roles` đã làm): token nặng hơn
  chút, nhưng mỗi request không cần query DB để biết quyền. Nhược điểm: admin
  đổi permission của 1 role thì user đã login **không bị ảnh hưởng ngay** —
  phải đợi token cũ hết hạn hoặc login lại.
- **Không nhúng, load lại từ DB mỗi request**: luôn đúng dữ liệu mới nhất,
  nhưng tốn 1 query DB mỗi request được bảo vệ.

File này gợi ý chọn **nhúng vào token** cho đơn giản, nhất quán với cách
`roles` đã làm từ Task 1 — nếu bạn muốn chọn hướng còn lại, tự điều chỉnh.

**Việc cần làm:**
1. Sửa `JwtProvider.generateToken` — thêm tham số `List<String> permissions`,
   thêm 1 claim mới (VD `"permissions"`), viết thêm `getPermissions(Claims)`
   song song với `getRoles(Claims)` đã có.
2. Sửa `AuthService.login()` — lấy `user.getRole().getPermissions()`, map
   sang `List<String>` mã permission, truyền vào `generateToken`.
3. Sửa `JwtFilter` — đọc thêm claim permission, gộp vào `authorities`. Cú
   pháp mẫu (thuần framework, permission **không** có tiền tố `ROLE_` — khác
   với role):
   ```java
   var roleAuthority = new SimpleGrantedAuthority("ROLE_" + role);
   var permissionAuthorities = permissions.stream()
           .map(SimpleGrantedAuthority::new)
           .toList();
   ```

**Tự kiểm tra:** login bằng tài khoản ADMIN (seed từ Bước 5), decode JWT
(jwt.io hoặc log tạm), xác nhận có cả claim `roles` và `permissions`.

---

## Bước 11 — Đổi `@PreAuthorize` từ role sang permission

**Khái niệm cần hiểu:** `hasRole('ADMIN')` **tương đương**
`hasAuthority('ROLE_ADMIN')` — Spring tự thêm tiền tố. Permission thì dùng
thẳng `hasAuthority('...')`, **không** có tiền tố `ROLE_` (đúng như cách
`JwtFilter` map ở Bước 10 — permission authority giữ nguyên mã, không cộng
`ROLE_`).

**Việc cần làm:** sửa `UserController` — đổi
`@PreAuthorize("hasRole('ADMIN')")` thành permission tương ứng, VD
`@PreAuthorize("hasAuthority('USER_MANAGE')")` (tuỳ mã permission bạn đặt ở
Bước 1 + gán cho role nào ở Bước 5).

**Câu hỏi tự trả lời:** `RoleController` (Bước 9) có nên đổi theo permission
luôn không, hay giữ nguyên `hasRole('ADMIN')`? Gợi ý: **giữ nguyên
`hasRole('ADMIN')`** cho `RoleController` — CRUD role/permission là hành động
cực nhạy cảm (tự cấp quyền cho chính mình được nếu lỏng), nên khoá chặt theo
đúng role ADMIN tuyệt đối. Trong khi `UserController` đổi sang permission-based
để sau này có thể cấp cho `STAFF` gọi được (nếu được gán permission
`USER_MANAGE`) mà không cần lên hẳn `ADMIN`. Đây chính là lý do Task 3 tồn
tại — tách biệt "ai là ADMIN" khỏi "ai được làm gì".

---

## Bước 12 — Test toàn luồng thủ công (checklist)

Tự đi qua từng dòng, tick khi pass:

- [ ] App start 2 lần liên tiếp không lỗi, `data.sql` không insert trùng.
- [ ] `roleRepository.findByName("ADMIN")` có đủ permission đã seed.
- [ ] Register user mới → mặc định gán đúng role `CUSTOMER` (không còn hard-code
      string, mà là `Role` entity thật lấy từ DB).
- [ ] Login bằng ADMIN → decode JWT thấy cả `roles` và `permissions` đúng.
- [ ] `GET /admin/roles` bằng token ADMIN → thành công; bằng token CUSTOMER →
      `403`.
- [ ] Tạo role mới qua `POST /admin/roles` → `GET /admin/roles/{id}` thấy
      đúng.
- [ ] Gán permission cho role qua `PUT /admin/roles/{id}/permissions` → login
      lại user thuộc role đó, JWT có permission mới.
- [ ] Xoá 1 role đang có user tham chiếu → bị chặn với lỗi rõ ràng, không phải
      lỗi FK 500 xấu xí.
- [ ] `PATCH /admin/users/{id}/role` đổi role user → `GET /admin/users/{id}`
      thấy đổi đúng.
- [ ] `UserController` giờ check theo permission (`hasAuthority`) chứ không
      còn `hasRole('ADMIN')` cứng — test bằng 1 role `STAFF` được gán đúng
      permission, xác nhận gọi được `/admin/users/**` dù không phải `ADMIN`.

---

## Bước 13 (tuỳ chọn) — Test tự động

Viết test theo đúng pattern đã dùng ở Task 2 (`UserStatusTest`,
`UserAuthorizationTest`, `UserControllerHttpTest`) cho ít nhất: seed
role/permission trong `@BeforeEach`, `assignPermissions` cập nhật đúng,
`deleteRole` bị chặn khi còn user tham chiếu, `STAFF` có permission
`USER_MANAGE` gọi được `/admin/users` qua HTTP thật (nhớ bài học Task 2: phải
tự build `MockMvc` kèm `.apply(springSecurity())`, `@AutoConfigureMockMvc`
không tự làm việc này trong Spring Boot 4).

Không bắt buộc để coi là "xong Task 3", nhưng rất đáng làm trước khi sang
Task 4 — lúc đó `Inventory` sẽ có endpoint riêng cho STAFF, càng cần chắc
phần phân quyền không bị phá khi thêm role/permission mới.

---

## Khi nào coi là xong Task 3?

Tick hết checklist Bước 12, tự tin giải thích được (không cần nhìn code) 4
câu hỏi sau:

1. Vì sao chọn `User ↔ Role` là many-to-one thay vì many-to-many ở MVP này?
2. Vì sao `data.sql` cần `ON CONFLICT DO NOTHING`, và vì sao cần thêm 2 dòng
   `spring.sql.init.mode=always` + `spring.jpa.defer-datasource-initialization=true`
   trong `application.properties`?
3. Vì sao permission authority trong `JwtFilter` **không** có tiền tố
   `ROLE_`, trong khi role authority thì có?
4. Vì sao `RoleController` vẫn giữ `hasRole('ADMIN')` trong khi `UserController`
   đổi sang `hasAuthority(...)` theo permission?

Xong thì quay lại báo mình như thường lệ để review trước khi sang Task 4.
