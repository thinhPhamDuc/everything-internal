# Q&A Kiến Thức Thực Tế

> File này khác với `GIAO_AN.md`. `GIAO_AN.md` là lộ trình làm task; file này là
> **sổ tay Q&A** — mỗi khi bạn hỏi sâu về một đoạn code cụ thể đã viết ra (tại sao
> viết thế này, có cách khác không, điều gì có thể sai), câu trả lời được chốt lại
> ở đây để tra cứu về sau, không bị trôi mất trong lịch sử chat.
>
> Format mỗi mục: đoạn code liên quan → câu hỏi → trả lời chi tiết.

---

## Mục lục

- **Phần 1 — `AuthService.register()`**
  - Câu 1: Exception ném trong `@Transactional` có case nào không tới được caller?
  - Câu 2: Ngoài `save()` còn cách nào để lưu? Khác gì nhau?
  - Câu 3: Tại sao dùng `User.builder()`? Có cách khác không?
  - Câu 4: Dòng cuối `return new RegisterResponse(...)` nghĩa là gì?
- **Phần 2 — Xử lý bất đồng bộ gửi mail qua RabbitMQ**
  - Câu 1: Tại sao dùng `TopicExchange` chứ không phải `Direct`/`Fanout`?
  - Câu 2: `durable=true` nghĩa là gì? Message có sống sót qua restart không?
  - Câu 3: `convertAndSend()` có đảm bảo message tới được RabbitMQ không?
  - Câu 4: Consumer ném exception thì message bị làm sao?
  - Câu 5: Vì sao publish DTO nhỏ thay vì gửi thẳng entity `User`?
  - Câu 6: Queue tồn đọng do xử lý chậm — tăng consumer được không?
- **Phần 3 — JPA/Hibernate Session & Lazy Loading**
  - Lý thuyết: Session Hibernate là gì? Vòng đời ra sao?
  - Thực hành: Tự tay tái hiện `LazyInitializationException` bằng test thật
    (kèm phát hiện phụ về identity map, và kiểm chứng bằng Hibernate Statistics)

---

## Phần 1 — `AuthService.register()`

```java
@Transactional
public RegisterResponse register(RegisterRequest request) {
    if (userRepository.existsByEmail(request.getEmail())) {
        throw new DuplicateEmailException("Email đã được sử dụng");
    }

    User user = User.builder()
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getFullName())
            .status(STATUS_ACTIVE)
            .roles(DEFAULT_ROLE)
            .createdAt(LocalDateTime.now())
            .build();

    User saved = userRepository.save(user);

    return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getFullName());
}
```

### Câu 1 — Có case nào khiến Exception ném trong `@Transactional` KHÔNG trả ra ngoài được không?

Cần tách rõ 2 khái niệm hay bị nhầm: **"exception có propagate (lan truyền) ra
ngoài method không"** và **"transaction có rollback không"**. Đây là 2 chuyện
độc lập. `@Transactional` mặc định chỉ rollback khi gặp `RuntimeException`/`Error`
(unchecked) — nếu bạn ném 1 checked exception, transaction **không rollback**,
nhưng exception đó **vẫn ném ra ngoài bình thường**, không hề bị nuốt. Đây là ngộ
nhận phổ biến nhất — rollback và propagation không liên quan tới nhau.

Vậy case nào khiến exception **thật sự không bao giờ tới được caller**:

1. **Bản thân bạn (hoặc code khác) `catch` rồi không `rethrow`** — lỗi hay gặp
   nhất trong thực tế, không phải do Spring:
   ```java
   try {
       userRepository.save(user);
   } catch (Exception e) {
       log.error("Lỗi khi lưu user", e); // nuốt luôn, caller không biết gì cả
   }
   ```
   Code hiện tại của bạn **không** mắc lỗi này — không có `try/catch` nào bọc
   quanh, nên `DuplicateEmailException` và mọi exception từ `save()` đều bay
   thẳng ra `AuthController`, tới `GlobalExceptionHandler`.

2. **`TransactionSynchronization` callback** — đây là 2 hook khác nhau với hành
   vi khác nhau, dễ nhầm nên tách rõ (bản đầu tiên mình viết ở đây từng gộp
   chung 2 hook này lại — không chính xác, sửa lại như dưới):

   - **`afterCommit()`**: chạy ngay sau khi transaction **đã commit xong** (dữ
     liệu chắc chắn nằm trong DB rồi, không rollback lại được nữa). Nếu hook này
     ném exception, Spring **không nuốt** — exception này ném ngược lại đúng vào
     bước `commit()` bên trong proxy transaction, và **lan tới tận caller của
     `register()`** (tức `AuthController`). Hậu quả thực tế rất khó chịu: DB đã
     lưu user thành công, nhưng client vẫn nhận `500 Internal Server Error` vì
     proxy coi cả giao dịch là thất bại ở bước cuối — **dữ liệu đúng nhưng
     response sai**, loại bug này rất khó bắt qua test thông thường vì log DB
     cho thấy insert OK trong khi client báo lỗi.
   - **`afterCompletion(int status)`**: chạy sau `afterCommit` (hoặc sau
     rollback), mang tính "dọn dẹp cuối cùng". Đây mới **đúng nghĩa** hook mà
     Spring chủ động bắt riêng exception của từng synchronization rồi **chỉ
     log lại**, không ném cho ai — response về client hoàn toàn không bị ảnh
     hưởng. Đây mới là case "exception biến mất hoàn toàn" mà câu hỏi ban đầu
     muốn nói tới.

   Xem code minh hoạ chi tiết ở cuối mục "Câu 1" bên dưới.

3. **Race condition giữa bước check và bước save (TOCTOU — time-of-check to
   time-of-use)** — đây là rủi ro thực tế nhất với chính đoạn code này: nếu 2
   request register cùng email gửi lên gần như đồng thời, cả 2 đều có thể vượt
   qua `existsByEmail()` (vì lúc đó DB chưa có row nào), rồi cả 2 cùng gọi
   `save()`. Do `User` dùng `GenerationType.IDENTITY`, Hibernate bắt buộc phải
   `INSERT` ngay lập tức trong `save()` để lấy ID trả về (không đợi tới lúc
   commit) — nên 1 trong 2 request sẽ bị Postgres từ chối vì vi phạm ràng buộc
   `unique` trên cột `email`, và exception **vẫn ném ra ngoài** — nhưng không
   phải là `DuplicateEmailException` thân thiện của bạn, mà là
   `DataIntegrityViolationException` (Spring dịch lại từ lỗi JDBC của Postgres).
   `GlobalExceptionHandler` hiện tại **chưa** có handler cho loại này → request
   xui xẻo đó sẽ nhận `500 Internal Server Error` thay vì `409 Conflict` sạch sẽ.
   → Muốn vá triệt để: bắt thêm `DataIntegrityViolationException` trong
   `GlobalExceptionHandler` và trả về `409` giống `DuplicateEmailException`
   (tầng cuối cùng bảo vệ, còn `existsByEmail()` chỉ là tối ưu UX để trả lỗi sớm
   trong đa số trường hợp không có race).

4. **Self-invocation (gọi `@Transactional` method từ chính class chứa nó qua
   `this.xxx()`)** — không liên quan tới việc "nuốt exception", nhưng là bẫy rất
   hay gặp nên nhắc kèm: `@Transactional` hoạt động nhờ Spring tạo 1 **proxy**
   bọc quanh bean. Nếu 1 method khác trong `AuthService` tự gọi
   `this.register(...)` thay vì được gọi từ bên ngoài (VD từ Controller), request
   đó đi thẳng vào method thật, bỏ qua proxy hoàn toàn → `@Transactional` **bị vô
   hiệu hoá âm thầm** (không transaction nào được mở, không rollback nào xảy ra),
   dù exception vẫn ném ra bình thường. Hiện tại `register()` được gọi từ
   `AuthController` (class khác) nên không dính lỗi này — chỉ cần nhớ nguyên tắc
   này khi sau này refactor.

**Code minh hoạ 2 hook `afterCommit` vs `afterCompletion`:**

```java
@Transactional
public RegisterResponse register(RegisterRequest request) {
    if (userRepository.existsByEmail(request.getEmail())) {
        throw new DuplicateEmailException("Email đã được sử dụng");
    }

    User user = User.builder()
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getFullName())
            .status(STATUS_ACTIVE)
            .roles(DEFAULT_ROLE)
            .createdAt(LocalDateTime.now())
            .build();

    User saved = userRepository.save(user);

    // Đăng ký 1 hook chạy SAU khi transaction hoàn tất — không chạy ngay tại đây.
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

        @Override
        public void afterCommit() {
            // Lúc này DB ĐÃ commit xong — user chắc chắn tồn tại trong bảng "users".
            // Giả sử gửi mail bị lỗi (SMTP timeout, sai config...):
            emailService.sendWelcomeEmail(saved.getEmail()); // ném RuntimeException
        }

        @Override
        public void afterCompletion(int status) {
            // Chạy sau afterCommit (hoặc sau rollback nếu transaction fail).
            // Nếu dòng dưới ném exception, Spring chỉ log, KHÔNG ném lên đâu cả.
            auditLogService.logRegistration(saved.getId()); // ném RuntimeException
        }
    });

    return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getFullName());
}
```

**Chuyện gì xảy ra ở mỗi case, nhìn từ phía `AuthController`:**

- Nếu `emailService.sendWelcomeEmail(...)` trong **`afterCommit()`** ném lỗi:
  → `userRepository.save(user)` đã thành công, dòng `return new RegisterResponse(...)`
  trong code Java **thực ra không bao giờ được thực thi** — vì exception xảy ra
  ngay tại bước `commit()` mà Spring AOP proxy gọi *sau khi* method body của bạn
  chạy xong nhưng *trước khi* trả kết quả về cho `AuthController`. Kết quả:
  `AuthController.register(...)` nhận được exception (không phải `RegisterResponse`),
  không khớp handler nào trong `GlobalExceptionHandler` → client nhận `500`,
  dù mở DB lên sẽ thấy user đã nằm trong bảng `users` thật. Test bằng cách gọi
  API xong kiểm tra thấy "lỗi 500" rồi kết luận "chưa tạo được user" là **sai**
  trong case này — phải check thẳng trong DB mới biết.

- Nếu `auditLogService.logRegistration(...)` trong **`afterCompletion()`** ném lỗi:
  → Hoàn toàn vô hình với `AuthController` và với client. `register()` vẫn trả
  về `201 Created` với đúng `RegisterResponse` như bình thường. Duy nhất dấu vết
  còn lại là 1 dòng log lỗi (`ERROR`) do chính Spring tự in ra — nếu bạn không
  theo dõi log, sẽ không bao giờ biết việc ghi audit log đã thất bại.

**Rút ra bài học thực tế:** nếu định làm việc "phụ" sau khi transaction xong
(gửi mail, ghi audit log, bắn event...), **luôn tự bọc `try/catch` bên trong
chính hook đó** — dù dùng `afterCommit` hay `afterCompletion` — để bạn tự quyết
định log/alert/retry theo ý mình, thay vì phó mặc cho hành vi mặc định (1 bên
làm hỏng response của client, 1 bên nuốt lỗi âm thầm) — hoặc đơn giản là chuyển
mấy việc "phụ" này sang xử lý bất đồng bộ (RabbitMQ event, `@Async`) để tách
hẳn khỏi vòng đời của transaction chính (không cần tới `TransactionSynchronization`).

**Còn 2 hook nữa chạy TRƯỚC khi commit thật sự — `beforeCommit(boolean readOnly)`
và `beforeCompletion()`:**

- `beforeCommit(readOnly)`: chạy **trước** khi DB thật sự commit, còn nằm trong
  transaction — nên nếu hook này ném exception, Spring **vẫn kịp rollback** toàn
  bộ transaction (khác hẳn `afterCommit`, lúc đó đã quá muộn để rollback). Tham
  số `readOnly` cho biết transaction này có được đánh dấu `@Transactional(readOnly
  = true)` hay không.
- `beforeCompletion()`: chạy ngay sau `beforeCommit` (kể cả khi `beforeCommit` đã
  ném lỗi), dùng để dọn tài nguyên (đóng file, giải phóng lock tay...) trước khi
  transaction đóng lại, bất kể kết quả cuối cùng là commit hay rollback.

Thứ tự đầy đủ khi mọi thứ suôn sẻ:
`method body chạy xong → beforeCommit → beforeCompletion → (DB commit thật) →
afterCommit → afterCompletion(COMMITTED)`.

**Đã tự chạy thật để kiểm chứng** (implement 4 hook này vào `AuthService.register()`,
gọi `POST /auth/register`, xem log ứng dụng) — log thu được đúng như lý thuyết:

```
[TX] beforeCommit(readOnly=false) - userId=2 sắp được commit
[TX] beforeCompletion() - chuẩn bị đóng transaction cho userId=2
[TX] afterCommit() - userId=2 đã chắc chắn có trong DB, gửi mail chào mừng
[MOCK EMAIL] Đã gửi mail chào mừng tới Tran Thi B <hook-test@example.com>
[TX] afterCompletion(status=COMMITTED) - userId=2
```

Code thật đang nằm trong `AuthService.register()` (xem file), gọi
`emailService.sendWelcomeEmail(...)` bên trong `afterCommit()` — đúng nguyên tắc
"chỉ gửi mail sau khi chắc chắn dữ liệu đã lưu thành công", tránh trường hợp gửi
mail chào mừng cho 1 user mà giao dịch tạo ra nó cuối cùng lại bị rollback.

---

### Câu 2 — Ngoài `save()` còn cách nào để lưu? Khác gì so với `save()`?

`userRepository.save(user)` (đến từ `JpaRepository`) thực chất là 1 lớp vỏ tiện
lợi bọc quanh `EntityManager` của JPA. Bên trong, Spring Data JPA làm:

```java
// Rút gọn logic thật của SimpleJpaRepository.save():
if (entityInformation.isNew(entity)) {   // ID đang null -> entity "mới"
    entityManager.persist(entity);
    return entity;                       // trả về CHÍNH instance bạn truyền vào
} else {
    return entityManager.merge(entity);  // trả về 1 instance KHÁC (managed copy)
}
```

Vì `User` của bạn dùng `@GeneratedValue(strategy = GenerationType.IDENTITY)` và
lúc gọi `save()` thì `user.getId()` đang `null` → Spring Data JPA coi đây là
entity mới → đi theo nhánh `persist()`. Nghĩa là trong đúng đoạn code này,
`userRepository.save(user)` **tương đương** `entityManager.persist(user)`.

Các cách khác để lưu, và khác biệt:

| Cách | Khi dùng | Khác biệt với `save()` |
|---|---|---|
| `entityManager.persist(entity)` | Tự inject `EntityManager` thay vì dùng repository | Chỉ dùng được cho entity **mới** (transient). Gọi `persist()` trên entity đã có ID quản lý theo kiểu khác sẽ lỗi. `save()` tự động chọn `persist`/`merge` giúp bạn khỏi tự kiểm tra. |
| `entityManager.merge(entity)` | Cập nhật 1 entity đang ở trạng thái "detached" (VD lấy từ tầng ngoài, đã có ID) | **Luôn trả về 1 object khác** với object bạn truyền vào (bản sao đã được gắn vào persistence context) — bẫy phổ biến: sửa trên object gốc sau khi `merge()` sẽ **không** có tác dụng, phải dùng object trả về. |
| `repository.saveAndFlush(entity)` | Cần bắt lỗi DB (VD constraint violation) ngay tại dòng đó, hoặc cần dữ liệu visible ngay cho 1 native query chạy sau trong cùng transaction | Ép Hibernate đẩy SQL xuống DB ngay lập tức thay vì có thể trì hoãn tới cuối transaction. Với `IDENTITY` như `User` thì `save()` vốn đã flush ngay rồi nên 2 cách này **giống hệt nhau** ở đây — `saveAndFlush` chỉ khác biệt rõ khi entity dùng `SEQUENCE`/`AUTO` (Hibernate có thể gộp nhiều insert lại, trì hoãn tới cuối). |
| `jdbcTemplate.update("INSERT INTO ...")` | Cần SQL thuần, bỏ qua hoàn toàn tầng ORM/entity | Nhanh hơn, không qua persistence context, nhưng bạn tự viết SQL tay, tự map kết quả, mất hết lợi ích của JPA (dirty checking, cascade...). Thường chỉ dùng cho truy vấn phức tạp/tối ưu hiệu năng, không dùng cho CRUD cơ bản như đăng ký user. |

**Tóm lại:** với đúng use-case "tạo 1 user hoàn toàn mới" như đoạn code này,
`save()` là lựa chọn đúng và đơn giản nhất — nó tự nhận ra đây là insert mới và
đi đúng đường (`persist`). Bạn chỉ cần cân nhắc `entityManager` trực tiếp khi có
nhu cầu đặc biệt (batch insert số lượng lớn, cần kiểm soát flush timing thủ công).

---

### Câu 3 — Tại sao dùng `User.builder()`? Có cách khác không?

`builder()` tới từ annotation `@Builder` của Lombok đã gắn trên class `User`
(coi lại `user/entity/User.java`). Nó sinh ra 1 class nội bộ cho phép tạo object
bằng cách gọi tên field rõ ràng, tránh nhầm thứ tự tham số — đặc biệt quan trọng
ở đây vì `User` có tới **5 field kiểu String liên tiếp** (`email, password,
fullName, status, roles`), rất dễ truyền nhầm vị trí nếu dùng constructor thường.

Các cách khác để tạo object, và đánh đổi:

**1. Constructor không tham số + setter (dùng `@NoArgsConstructor`/`@Setter` đã
có sẵn trên `User`):**
```java
User user = new User();
user.setEmail(request.getEmail());
user.setPassword(passwordEncoder.encode(request.getPassword()));
user.setFullName(request.getFullName());
user.setStatus(STATUS_ACTIVE);
user.setRoles(DEFAULT_ROLE);
user.setCreatedAt(LocalDateTime.now());
```
Dài dòng hơn, và tệ hơn: object `user` tồn tại ở trạng thái **"nửa vời"** giữa
lúc `new User()` và lúc set xong field cuối cùng — nếu ai đó lỡ dùng `user` giữa
chừng (VD trong 1 method dài, quên set `status`) sẽ ra bug khó phát hiện.
`builder()` đảm bảo object chỉ tồn tại sau khi **đã đủ field** (gọi `.build()`).

**2. All-args constructor (cũng có sẵn nhờ `@AllArgsConstructor`):**
```java
User user = new User(null, request.getEmail(), passwordEncoder.encode(request.getPassword()),
        request.getFullName(), STATUS_ACTIVE, DEFAULT_ROLE, LocalDateTime.now());
```
Ngắn nhưng **cực kỳ dễ nhầm thứ tự** — compiler không báo lỗi nếu bạn lỡ đảo vị
trí `fullName` và `status` (cùng là `String`), bug này chỉ lộ ra lúc chạy.

**3. Static factory method tự viết (thường được coi là "chuẩn" hơn cho code
nghiệp vụ thật, đáng cân nhắc khi bạn làm Task 2/3):**
```java
// Trong chính class User:
public static User createNew(String email, String hashedPassword, String fullName) {
    return User.builder()
            .email(email)
            .password(hashedPassword)
            .fullName(fullName)
            .status("ACTIVE")
            .roles("CUSTOMER")
            .createdAt(LocalDateTime.now())
            .build();
}
```
Lợi ích: các quy tắc mặc định (`status = ACTIVE`, `roles = CUSTOMER`) nằm ngay
trong `User` — nơi hiểu rõ nhất "1 user mới hợp lệ trông như thế nào" — thay vì
nằm rải rác trong `AuthService`. Đây là hướng đi tốt nếu sau này nhiều nơi khác
cũng cần tạo `User` mới (không chỉ riêng register).

**4. Dùng thư viện mapping (MapStruct, ModelMapper)** — sinh code tự động map
`RegisterRequest` → `User` theo tên field trùng nhau. Hợp lý khi entity có **rất
nhiều field** hoặc mapping lặp lại ở nhiều chỗ; với 1 entity nhỏ như `User` hiện
tại thì thêm thư viện này là **overkill**, builder tay vẫn rõ ràng và dễ đọc hơn.

---

### Câu 4 — Dòng cuối `return new RegisterResponse(...)` nghĩa là gì?

```java
return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getFullName());
```

Tách ra từng phần:

- `RegisterResponse` là 1 **record** (khai báo ở `auth/dto/RegisterResponse.java`):
  `public record RegisterResponse(Long id, String email, String fullName) {}`.
  Record là 1 dạng class bất biến (immutable) — Java tự sinh constructor, getter
  (`id()`, `email()`, `fullName()` — không có tiền tố `get`), `equals()`,
  `hashCode()`, `toString()` chỉ từ 1 dòng khai báo. Dùng để làm DTO là rất hợp
  vì response ra ngoài API không cần thay đổi được sau khi tạo.

- `saved` là kết quả trả về từ `userRepository.save(user)` ở dòng trên — **quan
  trọng**: trước khi gọi `save()`, `user.getId()` đang `null` (chưa có trong DB
  nên chưa có ID); sau khi `save()` chạy xong, Postgres đã tự sinh ID
  (auto-increment) và Hibernate gán ngược ID đó vào object → `saved.getId()` giờ
  có giá trị thật (VD `1`).

- **Vì sao dùng `saved` chứ không dùng thẳng `user`?** Trong trường hợp cụ thể
  này (đi theo nhánh `persist()` như giải thích ở Câu 2), `persist()` sửa trực
  tiếp lên chính object `user` bạn truyền vào, nên `saved` và `user` thực ra là
  **cùng 1 reference** — dùng `user.getId()` ở đây cũng ra kết quả giống hệt.
  Nhưng **luôn dùng giá trị trả về của `save()`** (thay vì tin vào object gốc)
  là thói quen đúng cần giữ, vì nếu sau này logic đổi sang đi nhánh `merge()`
  (VD do bạn đổi cách sinh ID, hoặc entity implement `Persistable`), `save()` sẽ
  trả về 1 **object khác** đã có đủ dữ liệu, còn object gốc `user` có thể **không**
  được cập nhật ID. Code hiện tại đã làm đúng theo nguyên tắc an toàn này.

- **Vì sao không `return user;` hay `return saved;` (trả thẳng entity)?** Đây là
  nguyên tắc "không trả entity ra ngoài API" đã nhắc ở Task 2 của `GIAO_AN.md`:
  entity `User` có field `password` (dù đã hash, vẫn không nên lộ ra response),
  và trả thẳng entity khiến API response bị phụ thuộc cứng vào cấu trúc bảng DB
  — sau này đổi schema DB sẽ vô tình làm vỡ hợp đồng API với client. Map sang
  1 DTO riêng (`RegisterResponse`) giữ 2 tầng này tách biệt.

- Giá trị trả về này được `AuthController.register()` nhận lại, bọc trong
  `ResponseEntity.status(201).body(...)`, và đó chính là JSON bạn đã thấy lúc
  test: `{"id":1,"email":"student@example.com","fullName":"Nguyen Van A"}`.

---

## Phần 2 — Xử lý bất đồng bộ gửi mail qua RabbitMQ

Bối cảnh: thay vì gọi thẳng `emailService.sendWelcomeEmail()` trong `afterCommit()`
(đồng bộ, chặn thread request), ta tách việc gửi mail ra 1 **consumer riêng**
chạy trên thread khác, kết nối với `AuthService` qua RabbitMQ.

```java
// RabbitMQConfig.java
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "app.events.exchange";
    public static final String QUEUE_USER_REGISTERED = "user.registered.queue";
    public static final String ROUTING_KEY_USER_REGISTERED = "user.registered";

    @Bean
    public TopicExchange appEventsExchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public Queue userRegisteredQueue() {
        return new Queue(QUEUE_USER_REGISTERED, true);
    }

    @Bean
    public Binding userRegisteredBinding(Queue userRegisteredQueue, TopicExchange appEventsExchange) {
        return BindingBuilder.bind(userRegisteredQueue).to(appEventsExchange).with(ROUTING_KEY_USER_REGISTERED);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}

// UserEventPublisher.java — gọi trong AuthService.afterCommit()
public void publishUserRegistered(UserRegisteredEvent event) {
    rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY_USER_REGISTERED, event);
}

// UserRegisteredEventListener.java
@RabbitListener(queues = RabbitMQConfig.QUEUE_USER_REGISTERED)
public void handleUserRegistered(UserRegisteredEvent event) {
    emailService.sendWelcomeEmail(event.email(), event.fullName());
}
```

### Câu 1 — Tại sao dùng `TopicExchange` chứ không phải `DirectExchange` hay `FanoutExchange`?

3 loại exchange phổ biến, khác nhau ở cách match routing key:

- **`FanoutExchange`**: bỏ qua routing key hoàn toàn, gửi cho **mọi** queue đã
  bind vào nó. Dùng khi 1 event cần broadcast cho nhiều consumer độc lập (VD
  "user vừa đăng ký" cần cả service Email lẫn service Analytics cùng nhận).
- **`DirectExchange`**: routing key phải khớp **chính xác** (exact match) với
  binding key. Đủ dùng cho case hiện tại vì chỉ có đúng 1 routing key
  `user.registered`.
- **`TopicExchange`**: routing key so khớp theo **pattern** với wildcard (`*`
  khớp đúng 1 từ, `#` khớp nhiều từ, phân cách bằng dấu `.`). VD 1 queue có thể
  bind với pattern `user.*` để nhận cả `user.registered` lẫn `user.deleted`,
  `user.updated` sau này mà không cần đổi kiểu exchange.

Ở code hiện tại, `DirectExchange` sẽ hoạt động **giống hệt** vì chỉ có 1 routing
key duy nhất — chọn `TopicExchange` là quyết định **đón đầu tương lai**: khi bạn
làm thêm các event khác của `User` (VD `user.deleted`, `user.password_changed`),
chỉ cần publish với routing key mới, không phải đổi loại exchange hay khai báo
lại từ đầu. Nếu muốn đúng nguyên tắc "không thêm thứ chưa cần" thì dùng
`DirectExchange` cũng hoàn toàn hợp lý ở giai đoạn này — đây là 1 sự đánh đổi có
chủ đích, không phải bắt buộc.

---

### Câu 2 — `Queue(QUEUE_USER_REGISTERED, true)` — tham số `true` (durable) nghĩa là gì? Message có tự động "sống sót" qua restart RabbitMQ không?

`durable = true` chỉ đảm bảo **định nghĩa của queue** (tên, cấu hình) được RabbitMQ
ghi xuống đĩa — nếu container `airline-rabbitmq` restart, queue `user.registered.queue`
vẫn còn đó, không biến mất. Đây là thuộc tính của **queue**, tách biệt với thuộc
tính của **từng message** nằm trong queue.

Để 1 message cụ thể sống sót qua restart, message đó cần có `deliveryMode =
PERSISTENT`. Mình đã tra trực tiếp trong source code Spring AMQP (`MessageProperties.java`)
để trả lời chính xác thay vì đoán:

```java
public static final MessageDeliveryMode DEFAULT_DELIVERY_MODE = MessageDeliveryMode.PERSISTENT;
```

Tức là **mặc định, mọi message gửi qua `rabbitTemplate.convertAndSend(...)` đã
là PERSISTENT** (trừ khi bạn tự tay set `messageProperties.setDeliveryMode(NON_PERSISTENT)`
để tối ưu hiệu năng cho loại dữ liệu không quan trọng, VD log tạm thời). Kết hợp
`durable=true` (queue sống) + `deliveryMode=PERSISTENT` (message sống) → message
gửi đi vẫn còn nguyên trong queue sau khi restart RabbitMQ, miễn là nó **chưa bị
consumer ack**.

---

### Câu 3 — `rabbitTemplate.convertAndSend(...)` có đảm bảo chắc chắn message tới được RabbitMQ không?

**Không, mặc định là "fire-and-forget".** `convertAndSend()` chỉ đẩy message vào
kết nối TCP tới RabbitMQ rồi return ngay — không đợi broker xác nhận đã nhận
được message thành công. Có 2 rủi ro mặc định chưa được xử lý trong code hiện
tại:

1. **Message bị "lạc" nếu mất kết nối đúng lúc gửi** — muốn chắc chắn broker đã
   nhận, cần bật **publisher confirms**
   (`spring.rabbitmq.publisher-confirm-type=correlated`) và đăng ký 1
   `RabbitTemplate.ConfirmCallback` để biết message có được broker ack hay không,
   từ đó quyết định retry.
2. **Message "unroutable"** — nếu routing key không khớp binding nào (VD gõ sai
   chính tả routing key), mặc định RabbitMQ **âm thầm drop message** mà không
   báo lỗi gì. Muốn phát hiện case này, cần set `mandatory=true` trên
   `RabbitTemplate` và đăng ký `ReturnsCallback`.

→ Đây là điểm để ngỏ có chủ đích cho MVP hiện tại (đủ dùng cho môi trường học/dev
với 1 exchange, 1 routing key cố định, ít khi gõ sai) — nhưng là điều **bắt buộc
phải làm** khi lên production, đặc biệt liên quan tới tiền/đơn hàng (sẽ cực kỳ
quan trọng lại ở Task 5 và Task 7 của `GIAO_AN.md`).

---

### Câu 4 — Nếu `handleUserRegistered()` (consumer) ném exception khi đang xử lý, chuyện gì xảy ra với message đó?

`@RabbitListener` mặc định chạy ở `AcknowledgeMode.AUTO` — nghĩa là container tự
quyết định ack hay nack message dựa trên việc method có ném exception hay không
(khác với `NONE`, tức auto-ack phía RabbitMQ ngay khi nhận, không quan tâm xử lý
thành công hay chưa). Cụ thể:

- Method chạy xong bình thường → container tự **ack** → RabbitMQ xoá message
  khỏi queue.
- Method ném exception → container **nack** message, và theo cấu hình mặc định
  (`defaultRequeueRejected = true`), message được **requeue lại đúng queue đó**.

Hệ quả thực tế: nếu lỗi là **tạm thời** (VD SMTP server đang quá tải), requeue +
retry lại là hành vi mong muốn. Nhưng nếu lỗi là **vĩnh viễn** (VD field `email`
trong `UserRegisteredEvent` bị null do bug code, hoặc địa chỉ email không hợp
lệ khiến `EmailService` luôn ném lỗi) → message này bị đẩy ra rồi nhận lại **liên
tục vô hạn**, chiếm CPU/log liên tục mà không bao giờ xử lý xong — gọi là
**poison message**. Đây chính xác là lý do `GIAO_AN.md` (Task 5) đã nhắc trước:
cần cấu hình **Dead Letter Queue (DLQ)** + giới hạn số lần retry (VD qua
`RetryInterceptorBuilder` hoặc `defaultRequeueRejected=false` kết hợp DLQ) để
1 message lỗi vĩnh viễn bị đưa sang "hàng chờ rác" sau N lần thử, thay vì lặp vô
hạn trên queue chính. Code hiện tại **chưa** có DLQ — đúng ý định để dành xử lý
kỹ hơn ở Task 5, nhưng cần biết rủi ro này tồn tại ngay từ bây giờ.

---

### Câu 5 — Tại sao publish 1 DTO `UserRegisteredEvent` nhỏ (chỉ `userId, email, fullName`) thay vì gửi thẳng entity `User` qua queue?

Vài lý do cộng dồn:

1. **Không lộ dữ liệu nhạy cảm** — `User` có field `password` (dù đã hash).
   Message trong RabbitMQ có thể được xem qua Management UI, log, hoặc lưu lại
   ở DLQ — càng ít dữ liệu nhạy cảm trôi qua hệ thống trung gian càng an toàn.
2. **Tách rời "hợp đồng" giữa producer và consumer khỏi schema DB** — nếu sau
   này bảng `users` có thêm/bớt cột, `UserRegisteredEvent` không nhất thiết phải
   đổi theo, miễn là consumer vẫn cần đúng 3 field đó để gửi mail.
3. **`User` là JPA entity, không nên serialize qua message queue** — entity có
   thể mang theo các quan hệ lazy-loaded (`@OneToMany`, `@ManyToOne`...), khi
   Jackson cố serialize sang JSON dễ gặp lỗi `LazyInitializationException` (vì
   lúc consumer xử lý, session Hibernate của transaction gốc đã đóng từ lâu) hoặc
   vô tình serialize toàn bộ cây quan hệ lồng nhau không cần thiết. (Xem cơ chế
   đầy đủ ở Phần 3 — JPA/Hibernate Session & Lazy Loading.)

Nguyên tắc chung: **event gửi qua message queue nên là 1 DTO "phẳng", tối thiểu,
bất biến** — đúng những gì consumer thật sự cần, không hơn.

---

### Câu 6 — Queue bị tồn đọng (backlog) do xử lý tuần tự quá chậm, có tăng consumer lên được không?

**Có, và đây chính xác là cách chuẩn để giải quyết backlog** — tên gọi của kỹ
thuật này là **"competing consumers"**: nhiều consumer cùng subscribe vào
**MỘT** queue, RabbitMQ tự động chia message cho các consumer đang rảnh theo
kiểu round-robin (consumer nào rảnh trước, nhận message tiếp theo trước).

**Cần tách rõ 2 khái niệm bị lẫn trong câu hỏi — "tăng số queue" và "tăng số
consumer" là 2 việc hoàn toàn khác nhau:**

- **Tăng số queue** (VD nhờ `TopicExchange` bind thêm queue mới) → dùng khi
  muốn **nhiều loại xử lý khác nhau** cùng nhận 1 event (VD vừa gửi mail, vừa
  ghi analytics, vừa đồng bộ sang CRM — mỗi việc 1 queue riêng, 1 consumer
  riêng). Việc này **không giúp gì** cho bài toán "1 loại việc xử lý chậm" —
  thêm 1 queue mới thì message trong queue cũ vẫn tồn y nguyên, không tự chia
  bớt sang queue mới.
- **Tăng số consumer trên CÙNG 1 queue** → đây mới là câu trả lời đúng cho đúng
  vấn đề bạn mô tả: "1 hàng message xử lý tuần tự quá chậm". Thêm consumer vào
  đúng `user.registered.queue` sẽ giúp nhiều message được xử lý **song song**
  thay vì lần lượt.

**Cách làm trong Spring AMQP — chỉ cần thêm 1 thuộc tính vào listener hiện tại:**

```java
// Trước: chỉ 1 thread duy nhất xử lý tuần tự mọi message trong queue
@RabbitListener(queues = RabbitMQConfig.QUEUE_USER_REGISTERED)
public void handleUserRegistered(UserRegisteredEvent event) { ... }

// Sau: Spring tự chạy 3 thread consumer song song ngay từ đầu, tự scale
// thêm tới tối đa 10 thread nếu queue đang tồn đọng nhiều
@RabbitListener(queues = RabbitMQConfig.QUEUE_USER_REGISTERED, concurrency = "3-10")
public void handleUserRegistered(UserRegisteredEvent event) { ... }
```

Ngoài ra còn 1 hướng scale khác **không cần sửa code**: chạy **nhiều instance**
của chính ứng dụng Spring Boot này (VD 3 pod trên Kubernetes) — mỗi instance tự
mở 1 (hoặc nhiều) kết nối consumer tới cùng `user.registered.queue`. RabbitMQ
không quan tâm consumer đến từ process/máy nào, vẫn chia message đều cho tất cả.
Đây chính là điểm mạnh cốt lõi của kiến trúc message queue: **bên xử lý (consumer)
có thể scale độc lập hoàn toàn với bên tạo request (producer/web tier)** — không
cần scale cả cụm API chỉ vì riêng khâu gửi mail đang chậm.

**2 điều cần lưu ý khi tăng consumer, không phải cứ tăng vô hạn là tốt:**

1. **Prefetch (QoS)** — RabbitMQ có cơ chế "prefetch count": số message tối đa
   được đẩy cho 1 consumer trước khi consumer đó ack. Nếu prefetch quá cao, 1
   consumer có thể "ôm" sẵn rất nhiều message trong khi các consumer mới thêm
   vào đang rảnh không có gì để làm — chia tải không đều. Prefetch nhỏ (VD 1-10)
   giúp chia đều hơn, đánh đổi lại là nhiều round-trip ack hơn. Cấu hình qua
   `spring.rabbitmq.listener.simple.prefetch`.
2. **Bottleneck có thể chỉ dịch chuyển, không biến mất** — nếu nút thắt thật sự
   nằm ở downstream (VD SMTP server chỉ chịu được 10 request/giây), tăng consumer
   app lên 50 chỉ khiến SMTP server sập chứ không xử lý nhanh hơn. Luôn xác định
   đúng nút thắt trước khi tăng concurrency.
3. **Mất đảm bảo thứ tự xử lý** — 1 consumer duy nhất xử lý message theo đúng
   thứ tự vào queue (FIFO). Nhiều consumer song song thì message có thể xử lý
   xong không theo đúng thứ tự ban đầu (message B xử lý xong trước message A dù
   A vào queue trước). Với việc gửi mail chào mừng thì thứ tự không quan trọng
   nên tăng consumer ở đây hoàn toàn an toàn — nhưng nếu sau này có queue nào mà
   thứ tự xử lý là bắt buộc (VD `flight.batch.trigger` ở Task 5, xử lý sai thứ
   tự dữ liệu có thể ghi đè nhầm), cần cân nhắc kỹ trước khi tăng concurrency.

---

## Phần 3 — JPA/Hibernate Session & Lazy Loading

### Lý thuyết — Session Hibernate là gì? Nó start/end lúc nào?

**Session** (interface `org.hibernate.Session`) là đối tượng Hibernate dùng để
quản lý **persistence context** — nói đơn giản là "bộ nhớ tạm" theo dõi mọi
entity đang được Hibernate quản lý trong 1 đơn vị làm việc: entity nào đã load,
entity nào bị sửa (để tự sinh `UPDATE` lúc flush — gọi là *dirty checking*), và
là nơi duy nhất có thể lazy-load thêm dữ liệu con (`@OneToMany`, `@ManyToOne`
chưa fetch). Khi dùng Spring Data JPA, bạn thao tác qua `EntityManager` (chuẩn
JPA) — nhưng với Hibernate làm nhà cung cấp JPA (mặc định trong Spring Boot),
class thật đứng sau `EntityManager` **chính là** 1 `Session` (`SessionImpl`
implement cả 2 interface `Session` và `EntityManager`). Nói "Session" hay
"EntityManager" trong 1 app Spring Boot + Hibernate thường là nói về cùng 1 vật.

**Vòng đời Session bị chi phối bởi 2 tầng khác nhau — đây là phần hay gây nhầm:**

**1. Tầng `@Transactional`:** Khi 1 method như `AuthService.register()` được
gọi, Spring transaction interceptor mở 1 giao dịch DB (`BEGIN`). Nếu chưa có
Session nào đang mở sẵn cho thread hiện tại, Hibernate **mở mới 1 Session ngay
lúc này**, gắn nó vào giao dịch. Khi method `return` (hoặc ném exception),
Spring `commit`/`rollback` giao dịch, và **nếu** Session được mở riêng cho đúng
giao dịch này (không có ai mượn thêm), Session cũng bị **đóng ngay sau đó**.

**2. Tầng OSIV — Open Session In View (mặc định BẬT trong Spring Boot, và đang
bật thật trong chính project này):** Lúc chạy app lần đầu, log đã in sẵn dòng
cảnh báo:

```
WARN ... JpaBaseConfiguration$JpaWebConfiguration : spring.jpa.open-in-view is
enabled by default. Therefore, database queries may be performed during view
rendering.
```

`open-in-view=true` nghĩa là: với **mọi request HTTP** đi qua Spring MVC có đụng
tới JPA, Spring tự mở 1 Session **ngay từ đầu request** (trước khi vào
Controller), và chỉ đóng nó **sau khi toàn bộ response đã render xong** — tức
là Session này sống **lâu hơn** cả `@Transactional` method bên trong nó.
`register()` chỉ "mượn" Session có sẵn này cho riêng giao dịch DB của nó (mở/đóng
transaction bên trong), còn bản thân Session vẫn tiếp tục sống tới cuối request.

**Vì sao chi tiết này quan trọng với đúng lo ngại ở Phần 2, Câu 5 (ý 3)?**

Session luôn bị buộc chặt vào **1 request/1 giao dịch cụ thể, trên 1 thread cụ
thể**. `UserRegisteredEventListener.handleUserRegistered()` chạy ở 1 **thread
hoàn toàn khác** (`ntContainer#0-1` như log bạn đã thấy), **sau khi** request
`/auth/register` gốc đã trả response xong xuôi từ lâu — nghĩa là Session của
giao dịch tạo `User` ban đầu **chắc chắn đã đóng** trước khi consumer kịp chạy.
Nếu lúc đó bạn cầm nguyên entity `User` (thay vì DTO) và cố truy cập 1 quan hệ
lazy chưa load (VD `user.getBookings()`), Hibernate cần dùng lại Session gốc để
lazy-load — nhưng Session đó không còn tồn tại nữa:

```java
// Trong 1 transaction/request TRƯỚC ĐÓ, Session A đang mở:
User user = userRepository.findById(1L).get(); // user được Session A quản lý
// ... register() return, response trả về client, Session A ĐÃ ĐÓNG ...

// Sau đó, ở 1 thread/consumer RabbitMQ hoàn toàn khác, lúc này KHÔNG có Session A:
user.getBookings().size();
// -> org.hibernate.LazyInitializationException:
//    failed to lazily initialize a collection: could not initialize proxy
//    - no Session
```

Đây chính là lý do kỹ thuật cụ thể (không chỉ "cho gọn") đằng sau việc dùng DTO
`UserRegisteredEvent` — DTO chỉ chứa `Long`/`String` thuần, không có proxy nào
phụ thuộc Session, nên consumer chạy trên thread/thời điểm nào cũng đọc được.

**Ghi chú thêm cho sau này:** `open-in-view=true` là mặc định của Spring Boot
nhưng nhiều team production chủ động tắt đi
(`spring.jpa.open-in-view=false`) vì nó dễ che giấu vấn đề N+1 query (query
"rơi" xuống tận lúc render view mà không ai để ý) và giữ connection DB lâu hơn
cần thiết. Tắt nó đi thì Session sẽ chỉ còn sống đúng bằng đúng phạm vi
`@Transactional` (đúng như mô tả ở "Tầng `@Transactional`" phía trên, không kéo
dài thêm tới hết request) — task ở Task 2/4 khi viết nhiều query hơn là lúc đáng
cân nhắc việc này.

---

### Thực hành — Tự tay tái hiện `LazyInitializationException`

Bối cảnh: mục "Lý thuyết" ở trên mô tả lý thuyết 1 kịch bản — entity load ở
Session A, Session A đóng, rồi consumer RabbitMQ ở thread khác cố truy cập
lazy collection → `LazyInitializationException`. Thay vì chỉ tin lý thuyết, đã
tạo bảng con thật + viết test JUnit chạy trên Postgres thật (không mock, không
H2) để tự kiểm chứng.

#### 1. Tạo bảng con `Booking` liên kết `User` (1-nhiều)

```java
// booking/entity/Booking.java
@Entity
@Table(name = "bookings")
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String flightCode;
    private LocalDateTime createdAt;
}

// user/entity/User.java — thêm field mới
@OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
private List<Booking> bookings;
```

`spring.jpa.hibernate.ddl-auto=update` tự tạo bảng, kiểm tra lại bằng
`docker exec airline-postgres psql -U airline_user -d airline_db -c "\d bookings"`
→ đúng như kỳ vọng: cột `user_id bigint not null`, FK
`bookings_user_id_fkey ... REFERENCES users(id)`.

#### 2. Test tái hiện — không cần dựng thật Controller + RabbitMQ consumer 2 thread

Insight quan trọng để viết được test đơn giản: `SimpleJpaRepository` (class
Spring Data JPA dùng để implement `UserRepository`) tự mang sẵn
`@Transactional(readOnly = true)` ở **cấp class**. Nếu gọi
`userRepository.findById(...)` mà **không có transaction nào bao ngoài** (test
method không có `@Transactional`, và đây không phải request HTTP nên không có
OSIV mở sẵn Session), Spring tự mở 1 transaction + Session **mới** chỉ cho
riêng lệnh gọi đó, rồi **đóng lại ngay** khi `findById()` return. Đây chính
xác là "Session A đã đóng" — không cần 2 thread thật để mô phỏng.

```java
@SpringBootTest
class UserLazyLoadingTest {

    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private EntityManager entityManager;

    private Long userId;
    private Long bookingId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("lazy-test-" + System.nanoTime() + "@example.com")
                .password("irrelevant-hash").fullName("Lazy Test User")
                .status("ACTIVE").roles("CUSTOMER").createdAt(LocalDateTime.now())
                .build());
        Booking booking = bookingRepository.save(Booking.builder()
                .user(user).flightCode("VN123").createdAt(LocalDateTime.now())
                .build());
        userId = user.getId();
        bookingId = booking.getId();
    }

    @AfterEach
    void tearDown() {
        bookingRepository.deleteById(bookingId);
        userRepository.deleteById(userId);
    }

    @Test
    void truyCapLazyCollectionSauKhiSessionDaDong_nemLazyInitializationException() {
        User user = userRepository.findById(userId).orElseThrow();
        // Session vừa mở cho findById() đã đóng ngay khi dòng trên return.

        LazyInitializationException ex = assertThrows(
                LazyInitializationException.class, () -> user.getBookings().size());
        log.info("Bắt đúng exception mong đợi: {}", ex.getMessage());
    }

    @Test
    @Transactional
    void truyCapLazyCollectionTrongCungTransaction_khongNemLoi() {
        entityManager.clear(); // xem giải thích ở mục "3. Phát hiện phụ" bên dưới
        User user = userRepository.findById(userId).orElseThrow();

        assertDoesNotThrow(() -> {
            int size = user.getBookings().size();
            assertEquals(1, size);
        });
    }
}
```

**Kết quả chạy thật** (`./gradlew test --tests UserLazyLoadingTest`, đọc từ
`build/test-results/test/TEST-....xml`): `tests="2" failures="0" errors="0"`,
log thu được đúng như dự đoán:

```
Bắt đúng exception mong đợi: Cannot lazily initialize collection of role
'com.app.internal.user.entity.User.bookings' with key '8' (no session)
```

→ Xác nhận: đúng là `LazyInitializationException` (từ `org.hibernate`), đúng
message "no session", đúng key = ID của user vừa tạo. Khớp 100% với lý thuyết
đã ghi trước đó.

#### 3. Phát hiện phụ (không nằm trong dự đoán ban đầu, nhưng đáng ghi lại)

Lần chạy đầu tiên, test thứ 2 (`@Transactional`, kỳ vọng KHÔNG lỗi) lại ném
`NullPointerException` chứ không phải chạy trơn tru — vì `user.getBookings()`
trả về `null` thay vì 1 list có dữ liệu. Mổ xẻ từng bước tại sao:

**a) Vì sao `@BeforeEach` và `@Test` lại chung 1 transaction/Session?**

`SpringExtension` (JUnit 5 extension mà `@SpringBootTest` tự gắn) implement
`BeforeEachCallback` — callback này luôn chạy **trước** các method `@BeforeEach`
của chính test class (thứ tự cố định trong JUnit 5: extension callback bọc
ngoài lifecycle method của user). Bên trong `SpringExtension.beforeEach()`, nó
gọi `TestContextManager.beforeTestMethod()` → kích hoạt
`TransactionalTestExecutionListener`. Listener này thấy method test có
`@Transactional` → **mở transaction + Session mới ngay tại đây, trước cả khi
`@BeforeEach` của bạn kịp chạy**. Thứ tự thật sự:

```
1. SpringExtension.beforeEach() -> TransactionalTestExecutionListener mở
   transaction + Session MỚI
2. @BeforeEach setUp() chạy (save user, save booking) - BÊN TRONG Session vừa mở
3. @Test method chạy (findById, getBookings...) - VẪN CÙNG Session đó
4. Kết thúc test -> ROLLBACK toàn bộ (mặc định, trừ khi có @Commit) - vì vậy
   @AfterEach tự tay xoá dữ liệu ở đây thực ra dư thừa, nhưng vô hại
```

`@BeforeTransaction` là lối thoát khỏi cơ chế này: method đánh dấu annotation
đó được listener cố tình chạy **trước khi mở transaction**, tự commit thật, độc
lập với transaction của `@Test` — dùng khi cần dữ liệu nằm sẵn trong DB thật
trước khi transaction test bắt đầu.

**b) Vì sao `findById()` trong `@Test` không hề bắn thêm câu SQL nào?**

Đây là hành vi chuẩn của `EntityManager.find()` (mà `SimpleJpaRepository.findById()`
gọi xuống): trước khi build SQL, nó luôn check persistence context trước —
"trong Session hiện tại đã có entity `User` với ID này chưa?". Vì bước 2 đã
đăng ký `User` này vào **đúng persistence context của Session đang mở** rồi,
câu trả lời là "có" → trả thẳng lại, không bao giờ chạm tới bước sinh SQL.

**c) Vì sao object trả về lại là "chính cái object Lombok builder tạo ra"?**

`persist()` (mà `save()` gọi khi entity là mới) **không tạo bản sao**. Nó chỉ
lấy đúng reference bạn truyền vào và gắn nhãn quản lý lên nó trong persistence
context (đưa vào 1 map nội bộ, key = `(User.class, id)`). Object đó, về bản
chất vật lý, chưa hề bị đổi — vẫn đúng instance từ dòng `.build()`. Khi
`findById()` trả lại "object đã có trong persistence context", nó trả lại
**chính xác cùng 1 reference** đó — không phải bản dựng lại từ ResultSet.

**d) Vì sao field `bookings` lại null, và bao giờ thì nó KHÔNG null?**

Khác biệt cốt lõi giữa 2 con đường 1 entity có thể đi vào persistence context:

| Con đường | Điều gì xảy ra với field `@OneToMany` |
|---|---|
| **`persist()` một object Java mới** (đúng case này) | Hibernate chỉ theo dõi object để dirty-checking/cascade lúc flush — **không đụng vào** field nào chưa set. `bookings` giữ nguyên giá trị Lombok builder để lại: `null` (không có `.bookings(...)` nào được gọi, và `@Builder` không tự default thành `List.of()` trừ khi dùng thêm `@Builder.Default`). |
| **`SELECT` thật từ DB rồi hydrate entity** | Hibernate tự dựng object mới từ ResultSet, và với **mọi** field `@OneToMany`/`@ManyToMany` (kể cả `FetchType.LAZY`), nó **luôn gán 1 wrapper riêng** — với `List` là `org.hibernate.collection.spi.PersistentBag` — chứ tuyệt đối không để field đó `null`. Wrapper này chưa chứa dữ liệu thật, nhưng là object hợp lệ, không null — chỉ cần Session còn mở, gọi `.size()` lên nó sẽ tự bắn `SELECT` để lấp đầy dữ liệu thật. |

`findById()` trong test 2 (trước khi sửa) đi theo cột trái — trả lại đúng
object gốc chưa từng qua tay Hibernate hydrate — nên `bookings` vẫn là `null`
Java thuần, không phải proxy.

**e) Vì sao lỗi ra là `NullPointerException`, không phải `LazyInitializationException`?**

2 lỗi này nguyên nhân hoàn toàn khác nhau dù triệu chứng na ná:

- `LazyInitializationException`: field **có** proxy/wrapper hợp lệ (không
  null), nhưng lúc gọi `.size()` thì **Session đã đóng** — proxy còn tồn tại
  nhưng mất liên lạc với DB.
- `NullPointerException` (case này): field **chưa từng có proxy nào** — biến
  `bookings` đúng nghĩa là `null` trong Java, gọi `.size()` lên `null` ném NPE
  bình thường như mọi lời gọi method trên `null` khác, không liên quan gì tới
  Session còn mở hay đóng (ở đây Session vẫn đang mở, đang trong transaction).

→ Sửa bằng cách gọi `entityManager.clear()` ngay đầu test — xoá sạch
persistence context hiện tại, ép `findById()` phải `SELECT` lại thật từ DB
(đi theo cột phải ở bảng trên), lúc này Hibernate mới trả về 1 entity **mới**
với `bookings` là 1 proxy `PersistentBag` đúng nghĩa, và vì Session vẫn đang
mở nên `.size()` initialize thành công, trả đúng `1`.

#### 4. Kiểm chứng thêm bằng Hibernate Statistics — đính chính 1 ngộ nhận dễ mắc

Từ phát hiện phụ ở trên, dễ rút ra kết luận sai: "gọi `findById()` cùng 1 ID
2 lần trong cùng Session, lần đầu hit DB, lần 2 trở đi sẽ null". **Vế sau sai.**
"Không hit DB ở lần 2" và "null" là 2 chuyện độc lập — null hay không phụ
thuộc vào **lần đầu tiên entity đó vào persistence context bằng cách nào**
(mục d ở trên), không phải "lần thứ mấy".

Viết thêm 1 test dùng `org.hibernate.stat.Statistics` (bật qua
`@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")`)
để đếm trực tiếp số `PreparedStatement` thật sự chạy xuống DB, thay vì suy
đoán:

```java
@Test
@Transactional
void goiFindByIdCungIdHaiLan_lanDauHitDB_lanSauKhongHitNhungKhongNull() {
    Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    entityManager.clear();
    stats.clear();

    long statementsBefore = stats.getPrepareStatementCount();

    // Lần 1: persistence context đang trống (vừa clear()) -> BẮT BUỘC phải
    // SELECT thật xuống DB để load User -> Hibernate hydrate đúng chuẩn,
    // "bookings" được gán PersistentBag thật (đi theo cột phải ở bảng mục d).
    User first = userRepository.findById(userId).orElseThrow();
    long statementsAfterFirst = stats.getPrepareStatementCount();

    // Lần 2: cùng ID, cùng Session -> identity map trả thẳng lại object của
    // lần 1, KHÔNG bắn thêm SQL nào.
    User second = userRepository.findById(userId).orElseThrow();
    long statementsAfterSecond = stats.getPrepareStatementCount();

    assertSame(first, second); // đúng object của lần 1
    assertTrue(statementsAfterFirst > statementsBefore); // lần 1 có hit DB
    assertEquals(statementsAfterFirst, statementsAfterSecond); // lần 2 không thêm SQL

    // Điểm mấu chốt: KHÔNG null, vì lần 1 đã thật sự SELECT rồi.
    assertNotNull(second.getBookings());
    assertDoesNotThrow(() -> assertEquals(1, second.getBookings().size()));
}
```

**Kết quả chạy thật** (`tests="3" failures="0" errors="0"`, log lấy từ
`build/test-results/test/TEST-....xml`):

```
Số prepared statement: trước=0, sau lần 1=1, sau lần 2=1
```

→ Xác nhận đúng như dự đoán: lần 1 hit DB (0→1), lần 2 không hit thêm (1→1),
**nhưng `second.getBookings()` vẫn không null** — vì lần 1 đã đi qua `SELECT`
thật (do `entityManager.clear()` trước đó), Hibernate đã gắn `PersistentBag`
ngay từ lần 1, lần 2 chỉ đơn giản là trả lại đúng cái proxy đó.

**Bài học rút ra (bản đã đính chính):** "cùng Session thì lazy-load luôn chạy
được" chỉ đúng khi thực sự có 1 lệnh `SELECT` xảy ra để Hibernate gắn proxy
vào field lazy. "Lần thứ mấy gọi `findById()`" và "field lazy có null hay
không" là **2 trục độc lập**:

- Trục 1 — *có hit DB không*: chỉ phụ thuộc "ID này đã có trong persistence
  context của Session hiện tại chưa" — có rồi thì mọi lần gọi sau **luôn**
  không hit DB, bất kể entity đó vào persistence context bằng `persist()` hay
  `SELECT`.
- Trục 2 — *field lazy null hay là proxy dùng được*: chỉ phụ thuộc **entity đó
  lần đầu tiên vào persistence context bằng con đường nào** (`persist()` một
  object Java tự tạo → giữ nguyên giá trị Java gốc, thường là `null`; hay
  `SELECT` + Hibernate hydrate → luôn được gán proxy, không bao giờ null).

Cạm bẫy thực tế hay gặp nhất khi viết test `@Transactional`: dữ liệu setup
trong `@BeforeEach` (qua `save()`) và dữ liệu đọc lại trong `@Test` (qua
`findById()`) tưởng là "đọc lại từ DB" nhưng thực ra là đọc lại đúng object
Java cũ trong bộ nhớ, chưa từng được Hibernate hydrate đầy đủ — nên các field
lazy chưa từng đụng tới vẫn mang giá trị mặc định của Java, không phải trạng
thái "lazy chưa load" đúng nghĩa.

**File liên quan:** `booking/entity/Booking.java`, `booking/repository/BookingRepository.java`,
`user/entity/User.java` (field `bookings` mới), test tại
`src/test/java/com/app/internal/user/UserLazyLoadingTest.java` (3 test case).

---

<!-- Thêm mục Q&A mới cho đoạn code tiếp theo bằng cách copy format phía trên.
     Nếu chủ đề mới không thuộc 3 Phần hiện có, tạo "## Phần N — <tên chủ đề>"
     mới và nhớ thêm dòng tương ứng vào "Mục lục" ở đầu file. -->
