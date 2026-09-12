# Kế hoạch chuyển đổi Database: PostgreSQL → MySQL

> Tài liệu này liệt kê **toàn bộ nơi cần sửa** trong repo để đổi từ PostgreSQL
> sang MySQL, theo đúng thứ tự nên làm. Đã rà soát code thật (entity, query,
> config, docker-compose, schema.sql, data.sql) — không phải suy đoán chung
> chung.

## Tin tốt trước tiên: mức độ ảnh hưởng thực tế thấp

Rà lại toàn bộ codebase, dự án **không** dùng bất kỳ tính năng đặc thù nào của
Postgres ở tầng entity/query:

- Tất cả entity đều dùng `@GeneratedValue(strategy = GenerationType.IDENTITY)`
  → map thẳng sang `AUTO_INCREMENT` của MySQL, **không cần sửa entity nào**.
- Không có cột `jsonb`, `TEXT[]`, `UUID`, `columnDefinition` đặc thù Postgres.
- Không có native query dùng cú pháp Postgres (`ILIKE`, `::text`, `RETURNING`,
  `FOR UPDATE SKIP LOCKED`...) — `InventoryRepository.searchAvailable` là JPQL
  thuần, chạy được trên mọi DB.
- Khoá bi quan/lạc quan chỉ dùng `@Version` (optimistic locking chuẩn JPA,
  không phụ thuộc DB).
- `DuplicateInventoryException` bắt `DataIntegrityViolationException` (đã được
  Spring dịch từ exception gốc của driver) — không bắt trực tiếp
  `org.postgresql.util.PSQLException` nên không cần sửa.

→ Việc chuyển đổi chủ yếu nằm ở **4 file hạ tầng/cấu hình**, không đụng tới
logic nghiệp vụ.

---

## Danh sách file cần sửa

| # | File | Thay đổi |
|---|------|----------|
| 1 | `build.gradle` | Đổi driver JDBC |
| 2 | `docker-compose.yml` | Đổi service DB |
| 3 | `src/main/resources/application.properties` | Đổi connection string |
| 4 | `src/main/resources/schema.sql` | Đổi schema Spring Batch (Postgres → MySQL) |
| 5 | `src/main/resources/data.sql` | Đổi cú pháp upsert (`ON CONFLICT` → MySQL) |

---

## Bước 1 — `build.gradle`: đổi JDBC driver

Xoá dòng:

```gradle
runtimeOnly 'org.postgresql:postgresql'
```

Thay bằng driver MySQL chính thức (Oracle, thay cho `mysql-connector-java` cũ
đã deprecated):

```gradle
runtimeOnly 'com.mysql:mysql-connector-j'
```

Không cần khai báo version thủ công — `spring-boot-starter-parent`/BOM của
Spring Boot 4.1.1 đã quản lý version tương thích.

---

## Bước 2 — `docker-compose.yml`: đổi service `postgres` → `mysql`

Thay toàn bộ block `postgres:` bằng:

```yaml
  mysql:
    image: mysql:8.4
    container_name: airline-mysql
    restart: unless-stopped
    environment:
      MYSQL_DATABASE: airline_db
      MYSQL_USER: airline_user
      MYSQL_PASSWORD: airline_pass
      MYSQL_ROOT_PASSWORD: airline_root_pass
    command: >
      --character-set-server=utf8mb4
      --collation-server=utf8mb4_unicode_ci
      --default-time-zone=+00:00
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "airline_user", "-pairline_pass"]
      interval: 5s
      timeout: 5s
      retries: 10
    networks:
      - airline-net
```

Và đổi tên volume `postgres_data` → `mysql_data` ở khối `volumes:` cuối file.

**Lưu ý:**
- `utf8mb4` bắt buộc nếu muốn lưu tiếng Việt có dấu đúng cách (mặc định
  `utf8` của MySQL là bảng mã 3-byte cũ, thiếu một số ký tự) — Postgres mặc
  định đã là UTF-8 nên trước giờ không cần lo việc này, MySQL thì phải khai
  báo tường minh.
- `--default-time-zone=+00:00` để tránh lệch giờ giữa `LocalDateTime` lưu ở
  tầng app và giờ hệ thống container MySQL (Postgres không có khái niệm
  session timezone ảnh hưởng đến kiểu không-timezone như MySQL).
- Xoá container/volume cũ trước khi đổi hẳn:
  `docker compose down -v` (mất data cũ trong Postgres — backup trước nếu
  cần giữ).

---

## Bước 3 — `application.properties`: đổi connection string

```properties
# Cũ
spring.datasource.url=jdbc:postgresql://localhost:5432/airline_db
spring.datasource.username=airline_user
spring.datasource.password=airline_pass
```

```properties
# Mới
spring.datasource.url=jdbc:mysql://localhost:3306/airline_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=UTF-8
spring.datasource.username=airline_user
spring.datasource.password=airline_pass
```

Không cần set `spring.jpa.database-platform` — Hibernate 6 (đi kèm Spring
Boot 4.1.1) tự nhận diện dialect MySQL qua JDBC metadata.

Các dòng còn lại (`spring.jpa.hibernate.ddl-auto=update`,
`spring.sql.init.mode=always`, `spring.jpa.defer-datasource-initialization=true`)
giữ nguyên, không phụ thuộc loại DB.

---

## Bước 4 — `schema.sql`: đổi schema bảng Spring Batch

File hiện tại (dòng 5-6 tự ghi chú) là copy nguyên văn từ
`org/springframework/batch/core/schema-postgresql.sql` trong
`spring-batch-core` — dùng `CREATE SEQUENCE ... NO CYCLE`, cú pháp **MySQL
không hỗ trợ native sequence** (MySQL 8 không có `CREATE SEQUENCE`).

**Cách làm đúng** (giống cách file hiện tại đã làm với bản Postgres — không tự
bịa schema, lấy đúng bản chính thức của Spring Batch cho MySQL):

```bash
# Tìm file jar spring-batch-core trong cache Gradle, giải nén schema-mysql.sql
find ~/.gradle/caches -name "spring-batch-core-*.jar" | head -1
unzip -p <đường-dẫn-jar-vừa-tìm> org/springframework/batch/core/schema-mysql.sql
```

Copy nội dung đó vào `schema.sql`, thêm `IF NOT EXISTS` vào các `CREATE TABLE`
giống cách file hiện tại đang làm (để chạy lại an toàn mỗi lần start app, vì
`spring.sql.init.mode=always`).

**Điểm khác biệt chính cần biết** giữa bản MySQL và bản Postgres đang có:
- MySQL không có `CREATE SEQUENCE` → bản `schema-mysql.sql` chính thức dùng
  cột `BIGINT NOT NULL AUTO_INCREMENT` trực tiếp trên các bảng
  `BATCH_JOB_INSTANCE` / `BATCH_JOB_EXECUTION` / `BATCH_STEP_EXECUTION` (khác
  với bản Postgres tách riêng 3 `SEQUENCE`), nên không cần (và không thể) giữ
  3 dòng `CREATE SEQUENCE ...` ở cuối file hiện tại.
- Kiểu `TEXT` cho `SERIALIZED_CONTEXT` vẫn tương thích, giữ nguyên.

**Cập nhật comment ở đầu file** (dòng 1-8) để không còn nhắc "copy từ
schema-postgresql.sql" gây hiểu lầm cho người đọc sau này.

---

## Bước 5 — `data.sql`: đổi cú pháp upsert

MySQL không có `ON CONFLICT (...) DO NOTHING` (đó là cú pháp Postgres). Với
MySQL, cách tương đương đơn giản nhất khi đã có unique constraint sẵn
(`roles.name`, `permissions.code` đều `unique = true` ở entity) là dùng
`INSERT IGNORE`:

```sql
INSERT IGNORE INTO roles (name, description) VALUES
    ('ADMIN', 'Toan quyen quan tri he thong'),
    ('STAFF', 'Nhan vien van hanh - duoc cap quyen quan ly user'),
    ('CUSTOMER', 'Khach hang - khong co quyen quan tri');

INSERT IGNORE INTO permissions (code, description) VALUES
    ('USER_READ', 'Xem danh sach / chi tiet user'),
    ('USER_MANAGE', 'Sua, doi status, xoa, gan role cho user'),
    ('ROLE_MANAGE', 'CRUD role va gan permission cho role'),
    ('INVENTORY_READ', 'Xem danh sach / chi tiet ve may bay trong kho'),
    ('INVENTORY_MANAGE', 'Tao, sua, doi status, dong ve may bay trong kho');

-- ADMIN: full quyen
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'ADMIN' AND p.code IN ('USER_READ', 'USER_MANAGE', 'ROLE_MANAGE', 'INVENTORY_READ', 'INVENTORY_MANAGE');

-- STAFF: quan ly user + quan ly inventory
INSERT IGNORE INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name = 'STAFF' AND p.code IN ('USER_READ', 'USER_MANAGE', 'INVENTORY_READ', 'INVENTORY_MANAGE');
```

**Lưu ý:** bảng `role_permissions` là bảng join tự sinh bởi
`@ManyToMany` — cần đảm bảo nó có unique constraint (composite key
`role_id, permission_id`) thì `INSERT IGNORE` mới chặn trùng đúng như
`ON CONFLICT DO NOTHING` cũ. Kiểm tra lại `@JoinTable` trên entity `Role`
xem đã có `uniqueConstraints` chưa; nếu Hibernate tự tạo bảng join qua
`ddl-auto=update` thì mặc định nó **đã** có composite primary key
`(role_id, permission_id)` nên `INSERT IGNORE` hoạt động đúng ngay.

---

## Thứ tự thực hiện đề xuất

1. Backup data hiện tại nếu cần giữ (`pg_dump` trước khi tắt Postgres).
2. Sửa `build.gradle` (Bước 1) → `./gradlew build` để tải driver mới.
3. Sửa `docker-compose.yml` (Bước 2) → `docker compose down -v && docker compose up -d mysql rabbitmq redis`.
4. Sửa `application.properties` (Bước 3).
5. Lấy `schema-mysql.sql` thật từ jar, thay vào `schema.sql` (Bước 4).
6. Sửa `data.sql` (Bước 5).
7. Chạy app (`./gradlew bootRun`), kiểm tra:
   - App start không lỗi, bảng được Hibernate tạo qua `ddl-auto=update`.
   - `roles`/`permissions`/`role_permissions` có data seed đúng (Bước 5).
   - Bảng `BATCH_JOB_*` được tạo đúng, chạy thử luồng Task 5 (sync →
     staging → batch) xem `JobRepository` ghi log được không.
8. Chạy lại toàn bộ test: `./gradlew test` — các test ở
   `RoleServiceTest`, `UserSoftDeleteTest`, `UserStatusTest`,
   `FlightStagingReaderConfigTest` đang comment "chạy trên Postgres thật" —
   về bản chất chúng chỉ cần **một DB thật đang chạy** (không dùng H2), nên
   sẽ tự chạy đúng trên MySQL miễn container MySQL đã `up`. Có thể cập nhật
   lại comment trong các file này cho khớp thực tế (không bắt buộc, chỉ để
   tránh gây hiểu lầm).
9. Test riêng luồng concurrency ở Task 7 (đặt vé đồng thời) — cơ chế
   `@Version` optimistic locking hoạt động giống nhau trên MySQL/InnoDB nên
   không cần đổi logic, nhưng nên chạy lại test thực tế 1 lần để chắc chắn.

---

## Việc không bắt buộc nhưng nên làm

- Cập nhật các dòng comment nhắc "Postgres" trong code hiện tại
  (`BatchJdbcConfig.java`, `schema.sql`, các file `TASK*.md`,
  `GIAO_AN.md` mục docker-compose) để tài liệu không bị lệch với hạ tầng
  thật — tìm nhanh bằng `grep -rn "postgres\|Postgres" .` sau khi migrate
  xong.
- Cân nhắc thêm Flyway/Liquibase thay cho `schema.sql`/`data.sql` +
  `ddl-auto=update` — vì đang đổi DB là thời điểm hợp lý để chuẩn hoá luôn
  quản lý migration, tránh phải làm lại việc "đổi tay 2 file SQL" nếu sau
  này đổi DB lần nữa.
