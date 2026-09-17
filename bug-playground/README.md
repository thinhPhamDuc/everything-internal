# Bug Playground — Tái Hiện Bug Microservices (Nhóm 1: Common)

> Playground **riêng biệt**, không đụng vào code monolith ở `GIAO_AN.md`. Mục
> tiêu: cho bạn 1 môi trường thật (3 service Spring Boot riêng container,
> Kafka, ELK) để **tự tay bấm nút gây ra bug**, đọc log JSON qua Kibana, tìm
> nguyên nhân — thay vì chỉ đọc lý thuyết.
>
> Phạm vi lượt này: **4 bug thuộc Nhóm "Thường gặp"**. 3 nhóm còn lại (Ít gặp,
> Cực hiếm) làm ở lượt sau khi bạn báo đã "va chạm" xong nhóm này.

---

## 1. Kiến trúc

```
                         ┌─────────────────────┐
   curl / trình duyệt ──▶│  gateway-service     │  :9080  (reverse-proxy thủ công
                         │  (Bug #3, #4)        │          + CORS config + trace filter)
                         └──────────┬───────────┘
                        /api/orders/**   \api/payments/**
                        ▼                 ▼
              ┌──────────────────┐   ┌──────────────────┐
              │  order-service   │──▶│  payment-service  │
              │  (Bug #1, #2)    │   │  (Bug #1, #2)      │
              │  :9081           │   │  :9082             │
              └────────┬─────────┘   └─────────┬──────────┘
                       ▲ Kafka "payments.events"│
                       └─────────────────────────┘
                                  │
                            ┌─────▼─────┐
                            │  Kafka    │ :39092 (host, optional debug)
                            │  KRaft    │  + kafka-ui :9091
                            └───────────┘

  3 service đều bắn log JSON (logstash-logback-encoder, TCP) ──▶ Logstash :5000
  ──▶ Elasticsearch :9200 ──▶ Kibana :5601 (xem/lọc theo correlation_id, service)
```

Mỗi bug có 1 **toggle** để bật/tắt (biến môi trường trong `docker-compose.yml`,
hoặc REST endpoint `/chaos/config` của `payment-service` cho các bug cần đổi
runtime không cần restart). **Mặc định = TRẠNG THÁI CÓ BUG** — đúng ý đồ, để
bạn thấy bug trước rồi mới so sánh với bản đã fix.

---

## 2. Chạy lần đầu

Yêu cầu: Docker Desktop rảnh ít nhất ~4-6GB RAM (Kafka + Elasticsearch +
Kibana + Logstash + 3 JVM app cùng lúc khá nặng).

```bash
cd bug-playground

# 1. Build 3 JAR ở host trước (Dockerfile chỉ COPY jar có sẵn, không tự build
#    trong image — nhanh hơn và không phụ thuộc mạng lúc docker build)
./gradlew build -x test

# 2. Dựng toàn bộ hạ tầng + 3 service
docker compose up -d --build

# 3. Đợi ~30-60s cho Kafka/Elasticsearch/Kibana sẵn sàng, rồi tạo index
#    pattern cho Kibana (làm 1 lần):
curl -s -X POST "http://localhost:5601/api/index_patterns/index_pattern" \
  -H "kbn-xsrf: true" -H "Content-Type: application/json" \
  -d '{"index_pattern":{"title":"playground-logs-*","timeFieldName":"@timestamp"}}'
```

Kiểm tra nhanh mọi thứ sống:

```bash
curl -s http://localhost:9080/api/orders -X POST -H "Content-Type: application/json" \
  -d '{"item":"VN-SGN-HAN","amount":1500000}'
# -> {"id":"...","item":"VN-SGN-HAN","amount":1500000.0,"status":"PENDING_PAYMENT",...}
```

Mở Kibana tại **http://localhost:5601** → Analytics → Discover → chọn index
pattern `playground-logs-*` → bạn sẽ thấy log JSON có field `service`,
`correlation_id`, `level`, `message`.

Mở kafka-ui tại **http://localhost:9091** để xem topic `payments.events` +
consumer group `order-service` (hữu ích cho Bug #2).

**Dừng:** `docker compose down` (giữ data Kafka/ES). **Dọn sạch hoàn toàn:**
`docker compose down -v`.

---

## 3. Kịch bản Bug #1 — Cascading Failure

**Cơ chế:** `order-service` gọi `payment-service` **đồng bộ** (block cả
request thread) không có timeout (`payment.client.read-timeout-ms=0`), Tomcat
thread pool của `order-service` cố tình để nhỏ (`max=10`). Khi
`payment-service` chậm, N request đồng thời ăn hết 10 thread → `order-service`
**ngừng nhận MỌI request khác**, kể cả `/actuator/health`.

**Trigger:**

```bash
# B1: làm payment-service chậm 8s mỗi request
curl -X POST http://localhost:9082/chaos/config \
  -H "Content-Type: application/json" -d '{"latencyMs":8000}'

# B2: tạo 1 order
ORDER_ID=$(curl -s -X POST http://localhost:9080/api/orders \
  -H "Content-Type: application/json" -d '{"item":"VN-SGN-HAN","amount":1500000}' \
  | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
echo "ORDER_ID=$ORDER_ID"

# B3: bắn 20 request checkout đồng thời (nhiều hơn 10 thread order-service có)
for i in $(seq 1 20); do
  curl -s -o /dev/null -w "req $i -> http=%{http_code} time=%{time_total}s\n" \
    -X POST "http://localhost:9080/api/orders/$ORDER_ID/checkout-sync" &
done
wait
```

**Trong lúc B3 đang chạy**, mở terminal khác thử:

```bash
curl -s -o /dev/null -w "health check -> %{http_code} (%{time_total}s)\n" \
  http://localhost:9081/actuator/health
```

→ Sẽ **treo/timeout** dù `/actuator/health` chẳng liên quan gì tới
payment-service — đây chính là "cascading": lỗi ở service B lan sang **mọi
chức năng khác** của service A, không chỉ chức năng gọi tới B.

**Quan sát Kibana:** filter `service: "order-service"` — sẽ thấy nhiều dòng
`checkout_sync_started` dồn lại gần như cùng lúc, còn `checkout_sync_finished`
chỉ xuất hiện rải rác sau ~8s (đúng bằng latency đã set) theo từng đợt 10
request một (vì pool chỉ có 10 thread).

**Fix để so sánh:** mở `docker-compose.yml`, bỏ comment dòng
`PAYMENT_CLIENT_READ_TIMEOUT_MS: "3000"` trong `order-service`, rồi:

```bash
docker compose up -d --build order-service
```

Lặp lại B3 — lần này request lỗi (timeout) **nhanh và có kiểm soát** thay vì
treo vô thời hạn, health check vẫn phản hồi bình thường trong lúc đó. Đây
chính là lý do timeout + (ý tưởng nâng cấp) bulkhead/circuit-breaker luôn là
việc đầu tiên phải làm khi gọi 1 service khác đồng bộ.

**Reset trước khi sang bug khác:**

```bash
curl -X POST http://localhost:9082/chaos/config -H "Content-Type: application/json" -d '{"latencyMs":0}'
```

---

## 4. Kịch bản Bug #2 — Data Inconsistency (độ trễ event-driven)

**Cơ chế:** `POST /payments/{id}/pay-async` trả lời **"PAID" ngay lập tức**,
rồi mới publish `PaymentCompletedEvent` lên Kafka **sau một khoảng trễ**
(`eventPublishDelayMs`, mô phỏng consumer lag/network delay thật). Trong
khoảng trễ đó, `order-service` chưa nhận được event nên `GET /orders/{id}`
vẫn trả `PENDING_PAYMENT`.

**Trigger:**

```bash
# B1: set độ trễ publish 8s
curl -X POST http://localhost:9082/chaos/config \
  -H "Content-Type: application/json" -d '{"eventPublishDelayMs":8000}'

# B2: tạo order mới
ORDER_ID=$(curl -s -X POST http://localhost:9080/api/orders \
  -H "Content-Type: application/json" -d '{"item":"VN-SGN-DAD","amount":900000}' \
  | grep -o '"id":"[^"]*"' | cut -d'"' -f4)

# B3: thanh toán - GỌI THẲNG payment-service (port 9082), KHÔNG qua gateway
# (:9080/api/payments/** mặc định bị Bug #4a - routing sai - chặn mất, xem
# mục 6; kịch bản Bug #2 này không liên quan gì tới gateway nên bỏ qua nó)
curl -s -X POST "http://localhost:9082/payments/$ORDER_ID/pay-async" \
  -H "Content-Type: application/json" -d '{"amount":900000}'
echo

# B4: kiểm tra order NGAY LẬP TỨC
curl -s "http://localhost:9080/api/orders/$ORDER_ID"; echo
# -> status vẫn "PENDING_PAYMENT" dù payment vừa báo PAID ở B3

# B5: đợi > 8s rồi kiểm tra lại
sleep 9
curl -s "http://localhost:9080/api/orders/$ORDER_ID"; echo
# -> status đã thành "CONFIRMED"
```

**Quan sát Kibana:** so 2 timestamp —
`service:"payment-service" AND message:"payment_async_ack_sent*"` (thời điểm
client coi như đã xong) với
`service:"order-service" AND message:"order_status_updated_from_event*"`
(thời điểm order thật sự đổi trạng thái) cho cùng `order_id` — độ lệch giữa 2
mốc chính là "cửa sổ inconsistency" mà người dùng có thể va phải. Mở kafka-ui
→ Consumer Groups → `order-service` trong lúc B3-B5 để thấy **lag** tăng lên
rồi về 0 khi consumer xử lý xong.

**Không có toggle "fix 1 dòng"** cho bug này — đây không phải lỗi cấu hình
sai mà là **đánh đổi kiến trúc** (event-driven = decouple nhưng chấp nhận độ
trễ). Cách xử lý thật trong thực tế: (1) set `eventPublishDelayMs=0` để thấy
"trường hợp tốt" (độ trễ rất nhỏ, gần như không nhận ra) và so sánh, (2) hoặc
đổi UX: hiển thị trạng thái trung gian rõ ràng ("Đang xác nhận thanh toán...")
thay vì im lặng để user tưởng đã xong. Thử:

```bash
curl -X POST http://localhost:9082/chaos/config -H "Content-Type: application/json" -d '{"eventPublishDelayMs":0}'
```

rồi lặp lại B2-B5 để cảm nhận sự khác biệt.

---

## 5. Kịch bản Bug #3 — Distributed Tracing bị ngắt đoạn

**Cơ chế:** mỗi service có `CorrelationIdFilter` — mặc định
(`tracing.propagate.enabled=false`) **luôn tự sinh 1 `correlation_id` mới**,
bỏ qua header `X-Correlation-Id` đến từ service gọi trước (kể cả khi có),
và **không forward** header đó khi gọi tiếp sang service/Kafka message khác.

**Trigger (dùng lại đường Bug #1, đi đủ 3 chặng gateway → order → payment):**

```bash
curl -X POST http://localhost:9082/chaos/config -H "Content-Type: application/json" -d '{"latencyMs":0}'  # đảm bảo không dính Bug #1

ORDER_ID=$(curl -s -X POST http://localhost:9080/api/orders \
  -H "Content-Type: application/json" -d '{"item":"VN-SGN-HAN","amount":1500000}' \
  | grep -o '"id":"[^"]*"' | cut -d'"' -f4)

curl -s -X POST "http://localhost:9080/api/orders/$ORDER_ID/checkout-sync"; echo
```

**Quan sát Kibana:** Discover, lọc theo khoảng thời gian vừa gọi (vài giây
gần nhất), thêm cột `service` và `correlation_id`. Bạn sẽ thấy **3 dòng log
của 3 service khác nhau cho CÙNG 1 request nhưng có 3 `correlation_id` khác
nhau hoàn toàn** — thử copy 1 giá trị bất kỳ rồi search
`correlation_id: "<giá trị đó>"` → chỉ ra đúng 1 service, không thể lần theo
toàn bộ hành trình request. Đây chính là "bất khả thi" mà đề bài mô tả, tái
hiện bằng dữ liệu thật thay vì chỉ đọc mô tả.

**Fix để so sánh:** bỏ comment `TRACING_PROPAGATE_ENABLED: "true"` ở **CẢ 3
service** trong `docker-compose.yml` (thiếu 1 service là mất dấu ngay tại
chặng đó), rồi:

```bash
docker compose up -d --build gateway-service order-service payment-service
```

Lặp lại request checkout-sync, quay lại Kibana search đúng `correlation_id`
in ra ở dòng log của `gateway-service` (dòng đầu tiên trong chuỗi) — lần này
sẽ thấy đủ cả 3 dòng log của cả 3 service, đúng 1 id xuyên suốt.

---

## 6. Kịch bản Bug #4 — CORS & Routing ở Gateway

### 4a. Routing sai (copy-paste route)

`gateway.payment-target.url` mặc định trỏ **nhầm** vào `order-service` thay
vì `payment-service`.

```bash
curl -s -o /dev/null -w "http=%{http_code}\n" \
  -X POST "http://localhost:9080/api/payments/$ORDER_ID/pay-async" \
  -H "Content-Type: application/json" -d '{"amount":100000}'
# -> 404 (request bị forward sang order-service, không có route /payments/**)
```

**Fix:** bỏ comment `GATEWAY_PAYMENT_TARGET_URL: http://payment-service:8080`
trong `docker-compose.yml` phần `gateway-service`, rồi
`docker compose up -d --build gateway-service`, lặp lại lệnh trên → `200`.

### 4b. CORS

```bash
open cors-test-client/index.html   # macOS; mở thẳng file, KHÔNG cần http server
```

Mở DevTools Console (F12) **trước** khi bấm nút, bấm lần lượt 3 nút trên
trang. Với origin `null` (mở qua `file://`) và
`gateway.cors.allowed-origin` mặc định trỏ vào 1 origin không tồn tại, request
sẽ bị chặn — Console hiện lỗi dạng
`has been blocked by CORS policy: ...`, khung log trên trang chỉ hiện chung
chung `fetch() FAILED: Failed to fetch` (browser cố tình giấu chi tiết ở tầng
JS, đây là hành vi bảo mật chuẩn của mọi trình duyệt).

**Đã verify bằng curl (đã test thật, KHÔNG như đồn đại "curl không thấy được
CORS")** — Spring MVC tự kiểm tra `Origin` ở phía **server**, không chỉ dựa
vào trình duyệt tự chặn:

```bash
curl -i "http://localhost:9080/api/orders/$ORDER_ID" -H "Origin: http://localhost:5500"
# -> HTTP/1.1 403
#    Invalid CORS request
```

Tức là `curl` **thấy được** lỗi CORS rõ ràng hơn cả trình duyệt (thấy đúng
status `403` + body `Invalid CORS request`), còn trình duyệt lại giấu chi
tiết sau thông báo mơ hồ `Failed to fetch` ở tầng JS (đây là điểm hay để nhớ:
**khi debug CORS thật, dùng `curl -i` kèm `-H "Origin: ..."` để xem SERVER có
trả 403/thiếu header gì, đừng chỉ đoán qua thông báo lấp lửng trong Console**).

**Fix để so sánh:**

```bash
# terminal riêng, đứng trong bug-playground/cors-test-client
python3 -m http.server 5500
```

Mở `http://localhost:5500` (không phải `file://` nữa → origin thật là
`http://localhost:5500`). Sửa `docker-compose.yml`, bỏ comment
`GATEWAY_CORS_ALLOWED_ORIGIN: http://localhost:5500` trong `gateway-service`,
rồi `docker compose up -d --build gateway-service`. Reload trang, bấm lại 3
nút → không còn lỗi CORS trong Console.

---

## 7. Dọn dẹp

```bash
docker compose down          # dừng, giữ data Kafka/Elasticsearch
docker compose down -v       # dừng + xoá sạch volume (bắt đầu lại từ đầu)
```

## 8. Bước tiếp theo

Đã "va chạm" xong 4 bug ở trên thì báo lại — mình dựng tiếp **Nhóm "Ít gặp"**
(Duplicate Message Processing, Out-of-order Events, Breaking Changes/Contract
Violation) trên cùng playground này (thêm topic/consumer group mới, không
cần đập lại hạ tầng đã có).
