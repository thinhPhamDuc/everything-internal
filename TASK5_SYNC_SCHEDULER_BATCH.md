# Task 5 — Đồng Bộ Dữ Liệu Tự Động: Giáo Án Tự Làm (Step by Step)

> Cùng tinh thần `TASK2_CRUD_USERS.md`/`TASK3_CRUD_ROLES.md`: mình **không
> implement hộ business logic** — chỉ đưa method signature + TODO + gợi ý,
> còn code bên trong bạn tự viết để nhớ. Vài chỗ thuần cú pháp framework
> (khai báo Queue/Exchange, cấu hình `Job`/`Step` của Spring Batch...) mình
> cho code mẫu thật vì đó là thứ để tra cứu, không phải thứ cần tự nghĩ ra.
>
> Đây là task **khó nhất** trong `GIAO_AN.md` — nhiều thành phần rời rạc
> (Scheduler, 2 lượt RabbitMQ, bảng staging, Spring Batch) phải ráp đúng thứ
> tự mới chạy được. Đọc kỹ gợi ý ở cuối mỗi bước: **làm nửa đầu trước** (tới
> hết Bước 6 — có dữ liệu nằm trong bảng staging) rồi mới làm nửa sau (Batch
> đọc staging → ghi Inventory). Đừng viết hết 10 bước rồi mới chạy thử.

---

## Trạng thái hiện có (đọc trước khi bắt đầu)

Từ Task 1-4, project đã có sẵn — Task 5 sẽ **dùng lại**, không phát minh lại:

- RabbitMQ đã chạy thật (`docker-compose.yml`, port `5672`) và **đã có 1 luồng
  producer/consumer mẫu hoàn chỉnh** ở package `notification/mq/`
  (`RabbitMQConfig`, `UserEventPublisher`, `UserRegisteredEventListener`) —
  dùng cho việc gửi mail chào mừng bất đồng bộ sau khi đăng ký. **Đọc kỹ 4
  file này trước khi code Task 5** — đây chính là pattern mẫu cho cách khai
  báo `Exchange`/`Queue`/`Binding`, publish message, và `@RabbitListener`,
  Task 5 chỉ lặp lại đúng pattern này 2 lần (1 lần cho mỗi chặng queue).
  `MessageConverter` JSON (`JacksonJsonMessageConverter`) đã cấu hình sẵn,
  dùng chung được, không cần khai báo lại.
- `FlightTicketInventory` (Task 4, package `inventory/`) — bảng đích cuối
  cùng mà Batch Writer sẽ ghi vào, với unique key
  `flightCode + departureTime + seatClass` đã có sẵn (xem
  `TASK4_CRUD_INVENTORY.md` Bước 1) — **quan trọng cho Bước 9 (Writer
  upsert)**.
- **Chưa có:** dependency Spring Batch, `@EnableScheduling`, package
  `sync/`/`batch/`, bảng staging, mock API bên thứ 3.
- `application.properties`: đã có sẵn cấu hình `spring.rabbitmq.*` và
  `spring.jpa.defer-datasource-initialization=true` (Task 3).

> **Cảnh báo phiên bản (đọc trước khi code Bước 7-10):** project đang chạy
> **Spring Boot 4.1.1 / Spring Batch 6.0.5** — bản này khác khá nhiều so
> với hầu hết tài liệu/tutorial Spring Batch cũ (5.x trở xuống) mà bạn có
> thể tra cứu thêm trên mạng:
> - Package đổi chỗ: `Job`/`Step` chuyển sang `org.springframework.batch.core.job`/`.step`;
>   `ItemReader`/`ItemProcessor`/`ItemWriter`/`Chunk`/`ExecutionContext` chuyển
>   hẳn sang `org.springframework.batch.infrastructure.item.*` (không còn
>   `org.springframework.batch.item.*` như bản cũ); `JobParameters`/`JobParametersBuilder`
>   chuyển sang `org.springframework.batch.core.job.parameters`.
> - `JobLauncher.run()` bị **deprecated** — dùng `JobOperator.start()` (xem
>   Bước 10).
> - `StepBuilder.chunk(int, PlatformTransactionManager)` bị deprecated — API
>   mới tách `transactionManager(...)` ra riêng (xem Bước 7).
> - **Quan trọng nhất:** Spring Boot **KHÔNG còn tự tạo bảng
>   `BATCH_JOB_EXECUTION`...** khi thêm dependency — mặc định dùng
>   `ResourcelessJobRepository` (in-memory, chỉ nhớ đúng 1 lần chạy gần
>   nhất, mất hết khi restart app). Phải tự cấu hình `JobRepository` dùng
>   Postgres thật (xem Bước 7) mới có lịch sử Job và đúng hành vi
>   "tự chặn JobInstance trùng" mà `GIAO_AN.md` mô tả ở phần Idempotency.
>
> Các đoạn code mẫu dưới đây đã cập nhật đúng theo bản 6.0.5 thật đang chạy
> trong project (đã tự tay chạy thử, không phải chép nguyên tutorial cũ).

**Vấn đề Task 5 phải giải quyết:** hiện tại `FlightTicketInventory` chỉ có
dữ liệu do admin **tự nhập tay** (Task 4). Task 5 mô phỏng việc dữ liệu vé
đến từ 1 hệ thống đối tác bên ngoài, tự động chạy định kỳ, không cần con
người nhập tay.

---

## Bước 0 — Quyết định phạm vi (đọc kỹ trước khi code)

Luồng đầy đủ theo `GIAO_AN.md`:

```
[Scheduler] --(1) trigger--> [RabbitMQ Q1] --(2)--> [Consumer#1: fetch mock API]
                                                            │
                                                    (3) lưu staging
                                                            │
                                                    (4) publish batchId
                                                            ▼
                                                    [RabbitMQ Q2] --(5)--> [Consumer#2: JobOperator]
                                                                                    │
                                                                            [Spring Batch Job]
                                                                        Reader → Processor → Writer
                                                                                    │
                                                                                    ▼
                                                                        [FlightTicketInventory]
```

**Sơ đồ chi tiết — ai thật sự làm gì (đọc kỹ, dễ hiểu nhầm nhất chỗ này):**

`RabbitMQ` **không tự gọi API, không tự lưu DB** — nó chỉ là 1 kho chứa
message + router, không có logic nghiệp vụ. Toàn bộ hành động thật (gọi
HTTP, ghi DB) đều nằm trong code Java của bạn (Consumer#1, Consumer#2),
chỉ là code đó được **kích hoạt** khi có message mới xuất hiện trong queue
nó đang lắng nghe. Có **2 lần lưu DB khác nhau**, đừng gộp làm 1:

```
1. Scheduler (code Java, @Scheduled)
      → gọi rabbitTemplate.convertAndSend(...)
      → publish 1 message TÍN HIỆU (rỗng/timestamp) vào exchange,
        routing key "sync.trigger"

2. RabbitMQ (broker - CHỈ giữ message, không xử lý gì)
      → route message vào queue "flight.sync.trigger", message nằm chờ

3. Consumer #1 (code Java của BẠN, @RabbitListener queues="flight.sync.trigger")
      → RabbitMQ "đánh thức" method này khi có message mới trong queue
      → BÊN TRONG method, code bạn tự viết mới thật sự:
        a. Gọi HTTP GET tới mock API bên thứ 3 (RestClient) -> List<MockFlightDto>
        b. Sinh batchId mới (UUID)
        c. LƯU DB LẦN 1: map từng dòng -> FlightStagingRecord, lưu vào
           bảng staging (stagingRepository.saveAll(...))
        d. Publish tiếp 1 message MỚI (chứa batchId) sang exchange,
           routing key "sync.batch"

4. RabbitMQ
      → route message (chứa batchId) vào queue "flight.batch.trigger"

5. Consumer #2 (code Java của BẠN, @RabbitListener queues="flight.batch.trigger")
      → nhận batchId từ message
      → gọi jobOperator.start(flightImportJob, jobParameters chứa batchId)

6. Spring Batch Job chạy (Reader → Processor → Writer)
      → Reader: đọc staging theo batchId (processed=false)
      → Processor: validate + map raw -> FlightTicketInventory
      → Writer: LƯU DB LẦN 2: findBy(flightCode+departureTime+seatClass)
                rồi insert/update vào bảng FlightTicketInventory thật
```

Ý đồ thiết kế: mỗi mũi tên `→` là 1 ranh giới tách rời (decouple) —
Scheduler không biết Consumer#1 làm gì, Consumer#1 không biết Batch Job xử
lý ra sao, mỗi thành phần chỉ biết "việc của mình" + "gửi tín hiệu cho bước
sau qua RabbitMQ".

**Quyết định cần chốt trước khi code (tự chọn, có gợi ý):**

1. **Làm cả 2 nửa cùng lúc, hay làm nửa đầu trước?** Gợi ý mạnh: làm nửa đầu
   trước (Bước 1-6, dừng ở "dữ liệu nằm trong staging"), test chắc chắn
   nửa đầu chạy đúng, rồi mới làm nửa sau (Bước 7-9). `GIAO_AN.md` note rõ
   điều này — 2 nửa có thể test độc lập.
2. **Tần suất cron lúc dev:** `GIAO_AN.md` đề xuất chạy thật là 12h trưa mỗi
   ngày, nhưng lúc dev đừng đợi thật tới giờ đó — tự đặt cron ngắn hơn (VD
   mỗi 2-5 phút) để test nhanh, nhớ đổi lại giờ thật trước khi coi là "xong".
3. **Mock API trả cố định hay random mỗi lần gọi?** Gợi ý: random nhẹ
   (`seatsLeft`, `price` dao động) — để thấy rõ tác dụng thật của việc
   "đồng bộ" (dữ liệu thay đổi qua mỗi lần chạy), thay vì luôn giống hệt lần
   trước.

**Tự kiểm tra:** chưa test được ở bước này — làm tiếp Bước 1.

---

## Bước 1 — Mock API bên thứ 3

**Việc cần làm:** tạo package `sync/mock/`, 1 controller
`ThirdPartyFlightMockController` — endpoint `GET /mock/third-party/flights`,
trả về `List<MockFlightDto>` (tự thiết kế field: `flightCode, airline,
origin, destination, departureTime, arrivalTime, seatClass, price,
seatsLeft` — cố ý đặt tên field **khác 1 chút** so với
`FlightTicketInventory` thật, VD `seatsLeft` thay vì `availableSeats`, để
Bước 8 (Processor) có việc "map" thật sự để làm, không phải copy nguyên).

**Endpoint này để public, không cần `@PreAuthorize`** — vai trò là giả lập
API của hãng thứ 3, không thuộc hệ thống mình quản lý quyền.

**Câu hỏi tự trả lời:** vì sao mock nên trả **random 1 chút** mỗi lần gọi
(VD `seatsLeft` dao động ±10%, `price` dao động nhẹ) thay vì cố định? (Gợi ý:
để mô phỏng đúng thực tế — dữ liệu đối tác luôn "sống", và để bạn tự mắt thấy
Inventory thay đổi qua mỗi lần Job chạy, xác nhận luồng thật sự chạy chứ
không phải do cache/coincidence.)

**Tự kiểm tra:** `curl http://localhost:8080/mock/third-party/flights` vài
lần liên tiếp, thấy dữ liệu dao động nhẹ.

---

## Bước 2 — Bảng/entity Staging

**Tại sao cần bảng riêng, không ghi thẳng vào Inventory:** tách "nơi nhận dữ
liệu thô từ ngoài" ra khỏi "bảng nghiệp vụ thật" — nếu dữ liệu thô lỗi/format
sai, Batch (Bước 8) có thể reject riêng từng dòng mà không làm hỏng
Inventory đang chạy tốt, đồng thời giữ lại lịch sử fetch để debug khi cần
soi lại "lúc đó API bên thứ 3 trả cái gì".

**Việc cần làm:** tạo `sync/staging/entity/FlightStagingRecord.java`. Tự
quyết định field, gợi ý tối thiểu: `id, batchId (String), rawFlightCode,
rawAirline, rawOrigin, rawDestination, rawDepartureTime, rawArrivalTime,
rawSeatClass, rawPrice, rawSeatsLeft, fetchedAt, processed (boolean, default
false)`.

**Câu hỏi tự trả lời:** vì sao nên tiền tố `raw` cho các cột dữ liệu thô,
thay vì đặt tên giống hệt cột bên `FlightTicketInventory`? (Gợi ý: nhấn mạnh
đây là dữ liệu **chưa qua validate/chuẩn hoá** — tên khác giúp người đọc code
phân biệt ngay 2 tầng dữ liệu, tránh nhầm lẫn tưởng có thể dùng trực tiếp.)

**Tự kiểm tra:** chưa test được — cần Repository (Bước 3).

---

## Bước 3 — Repository cho Staging

**Việc cần làm:** tạo `sync/staging/repository/FlightStagingRepository.java`,
thuần derived query:

```java
public interface FlightStagingRepository extends JpaRepository<FlightStagingRecord, Long> {

    // Dùng cho query đơn giản (VD test, service không cần phân trang).
    List<FlightStagingRecord> findByBatchIdAndProcessedFalse(String batchId);

    // Overload RIÊNG cho RepositoryItemReader ở Bước 8 - reader gọi method
    // này qua reflection và TỰ ĐỘNG thêm 1 tham số Pageable vào cuối lời
    // gọi để tự phân trang (bạn sẽ không tự gọi overload này ở đâu cả).
    // Thiếu overload này: NoSuchMethodException lúc runtime. Kiểu trả về
    // PHẢI là Slice/Page (không phải List) - reader tự cast kết quả sang
    // Slice để biết "còn trang tiếp không", List thường gây
    // ClassCastException lúc runtime. Cả 2 điều này chỉ lộ ra khi CHẠY THẬT
    // Bước 8, không thấy được nếu chỉ đọc code - xem giải thích ở Bước 8.
    Slice<FlightStagingRecord> findByBatchIdAndProcessedFalse(String batchId, Pageable pageable);
}
```

**Tự kiểm tra:** viết 1 test JUnit nhỏ — save vài `FlightStagingRecord` với
`batchId` khác nhau, `findByBatchIdAndProcessedFalse` chỉ trả đúng nhóm của
1 `batchId`. Dùng overload không-`Pageable` cho test này là đủ; overload
`Pageable` sẽ được verify riêng ở Bước 8 (cần `RepositoryItemReader` thật để
test có ý nghĩa).

---

## Bước 4 — RabbitMQ config cho 2 queue

**Việc cần làm:** tạo `sync/mq/SyncRabbitMQConfig.java` — khai báo **1
exchange dùng chung** với **2 routing key khác nhau** (đúng gợi ý `GIAO_AN`:
"có thể dùng cùng 1 exchange với 2 routing key khác nhau cho gọn"). Copy
đúng pattern từ `notification/mq/RabbitMQConfig.java` đã đọc ở đầu file, chỉ
đổi tên hằng số:

```java
@Configuration
public class SyncRabbitMQConfig {

    public static final String EXCHANGE = "app.sync.exchange";
    public static final String QUEUE_TRIGGER = "flight.sync.trigger";
    public static final String QUEUE_BATCH = "flight.batch.trigger";
    public static final String ROUTING_KEY_TRIGGER = "sync.trigger";
    public static final String ROUTING_KEY_BATCH = "sync.batch";

    // TODO: tự khai báo bean TopicExchange, 2 Queue, 2 Binding - giống hệt
    // pattern userRegisteredQueue()/userRegisteredBinding() ở
    // notification/mq/RabbitMQConfig.java, chỉ đổi tên biến/hằng số.
}
```

**Dead Letter Queue (DLQ) — đọc kỹ trước khi code:** `GIAO_AN.md` yêu cầu
thêm DLQ cho mỗi queue, để message lỗi liên tục không retry vô hạn. Cú pháp
mẫu (thuần framework, cho code thật vì hay bị quên đúng property name):

```java
@Bean
public Queue flightSyncTriggerQueue() {
    return QueueBuilder.durable(QUEUE_TRIGGER)
            .withArgument("x-dead-letter-exchange", "") // "" = default exchange
            .withArgument("x-dead-letter-routing-key", QUEUE_TRIGGER + ".dlq")
            .build();
}

@Bean
public Queue flightSyncTriggerDlq() {
    return QueueBuilder.durable(QUEUE_TRIGGER + ".dlq").build();
}
```

Tự áp dụng tương tự cho `QUEUE_BATCH`. **Câu hỏi tự trả lời:** vì sao DLQ
dùng `""` (default exchange) làm dead-letter-exchange thay vì exchange chính
`app.sync.exchange`? (Gợi ý: default exchange tự route message tới đúng
queue có tên trùng `routing-key` — cách đơn giản nhất để "message lỗi rơi
thẳng vào 1 queue DLQ cụ thể" mà không cần khai báo thêm binding riêng.)

**Tự kiểm tra:** start app, mở RabbitMQ Management UI
(`http://localhost:15672`, user/pass đã cấu hình trong
`application.properties`), xác nhận thấy đủ 4 queue
(`flight.sync.trigger`, `flight.sync.trigger.dlq`, `flight.batch.trigger`,
`flight.batch.trigger.dlq`) và đúng exchange.

---

## Bước 5 — Scheduler publish trigger

**Việc cần làm:** thêm `@EnableScheduling` vào 1 class `@Configuration`
(tạo `config/SchedulerConfig.java` theo đúng cây thư mục `GIAO_AN.md` đã đề
xuất). Tạo `sync/scheduler/FlightSyncScheduler.java`:

```java
@Component
@RequiredArgsConstructor
public class FlightSyncScheduler {

    private final RabbitTemplate rabbitTemplate;

    // Lúc dev: đổi cron thành mỗi vài phút để test nhanh (VD "0 */3 * * * *"),
    // nhớ đổi lại "0 0 12 * * *" (12h trưa mỗi ngày) trước khi coi là xong.
    @Scheduled(cron = "${app.sync.cron:0 0 12 * * *}")
    public void triggerSync() {
        // TODO: publish 1 message rỗng (hoặc chỉ chứa timestamp) vào
        // SyncRabbitMQConfig.EXCHANGE với ROUTING_KEY_TRIGGER - tham khảo
        // UserEventPublisher.publishUserRegistered() đã đọc ở đầu file.
    }
}
```

**Tự thêm 1 endpoint gọi tay để test** (không đợi cron), VD
`POST /admin/sync/trigger` — gọi thẳng
`flightSyncScheduler.triggerSync()` hoặc logic publish tương tự — để test
không phải chờ tới đúng lịch cron.

**Tự kiểm tra:** gọi endpoint test tay, mở RabbitMQ Management UI, xác nhận
thấy 1 message xuất hiện (và biến mất ngay) ở queue `flight.sync.trigger`.

---

## Bước 6 — Consumer #1: fetch mock API → lưu staging → publish batchId

**Việc cần làm:** tạo `sync/service/FlightFetchService.java` (hoặc đặt trong
`sync/mq/` tuỳ bạn) với `@RabbitListener`:

```java
@Component
@RequiredArgsConstructor
public class FlightSyncTriggerListener {

    private final RestClient restClient; // hoặc WebClient - tự chọn, xem gợi ý bên dưới
    private final FlightStagingRepository stagingRepository;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = SyncRabbitMQConfig.QUEUE_TRIGGER)
    public void handleTrigger(/* tự quyết định kiểu payload nhận vào */) {
        // TODO:
        // 1. Gọi GET /mock/third-party/flights (Bước 1) bằng RestClient/WebClient
        // 2. Sinh 1 batchId mới (VD UUID.randomUUID().toString())
        // 3. Map từng phần tử response -> FlightStagingRecord, gán batchId,
        //    fetchedAt = now, processed = false, lưu bằng stagingRepository.saveAll(...)
        // 4. Publish message chứa batchId sang SyncRabbitMQConfig.QUEUE_BATCH
    }
}
```

**Câu hỏi tự trả lời:** dùng `RestClient` (đồng bộ, đơn giản, Spring 6.1+)
hay `WebClient` (bất đồng bộ/reactive)? Gợi ý: `RestClient` — đủ dùng cho 1
lời gọi HTTP đơn giản trong `@RabbitListener` (đã chạy trên thread riêng của
consumer, không cần non-blocking), tránh phức tạp hoá không cần thiết.

**Acknowledgment mode:** đọc thêm về **manual ack** của RabbitMQ (gợi ý
`GIAO_AN.md`) — nếu consumer crash **giữa lúc** đang lưu staging (VD lưu
được 50/100 dòng thì lỗi), mặc định auto-ack có thể làm mất message dù dữ
liệu chưa lưu xong hết. Tự tìm hiểu cấu hình
`spring.rabbitmq.listener.simple.acknowledge-mode=manual` và cách gọi
`channel.basicAck(...)` thủ công trong listener — không bắt buộc cho MVP
chạy được, nhưng nên hiểu trước khi coi Task 5 là "chạy đúng, an toàn".

**Tự kiểm tra:** trigger tay (Bước 5), kiểm tra bảng staging có dữ liệu mới
(`SELECT * FROM flight_staging_record ORDER BY fetched_at DESC LIMIT 20;`
trong `psql`), và message đã sang được queue `flight.batch.trigger` (xem
RabbitMQ Management UI, hoặc log Consumer #2 nếu đã code xong Bước 10).

---

## Bước 7 — Spring Batch: thêm dependency + khai báo Job/Step

**Việc cần làm:** thêm vào `build.gradle`:

```gradle
implementation 'org.springframework.boot:spring-boot-starter-batch'
testImplementation 'org.springframework.batch:spring-batch-test'
```

Tạo `batch/job/FlightImportJobConfig.java`. Phần khai báo `Job`/`Step` thuần
cú pháp framework, cho code mẫu (bạn tự điền tên bean Reader/Processor/Writer
ở Bước 8-9). Import đúng package Spring Batch 6 (xem cảnh báo phiên bản ở
đầu file), và `chunk(int)` API mới (không truyền `transactionManager` ngay,
set riêng bằng `.transactionManager(...)`):

```java
@Configuration
@RequiredArgsConstructor
public class FlightImportJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    // 2 Step nối tiếp: flightImportStep COMPLETED (toàn bộ chunk đã commit
    // vào Inventory) rồi mới tới markStagingProcessedStep - xem giải thích
    // ở Bước 9 vì sao KHÔNG đánh dấu processed=true ngay trong Writer.
    @Bean
    public Job flightImportJob(Step flightImportStep, Step markStagingProcessedStep) {
        return new JobBuilder("flightImportJob", jobRepository)
                .start(flightImportStep)
                .next(markStagingProcessedStep)
                .build();
    }

    @Bean
    public Step flightImportStep(
            ItemReader<FlightStagingRecord> reader,
            ItemProcessor<FlightStagingRecord, FlightTicketInventory> processor,
            ItemWriter<FlightTicketInventory> writer) {
        return new StepBuilder("flightImportStep", jobRepository)
                .<FlightStagingRecord, FlightTicketInventory>chunk(100) // 100 dòng/lần, đúng gợi ý GIAO_AN
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .transactionManager(transactionManager)
                .build();
    }

    // TODO (tự viết, xem Bước 9): Step dạng Tasklet, @StepScope, đọc
    // batchId từ jobParameters giống Reader - bulk update
    // FlightStagingRecord.processed=true cho toàn bộ batchId, SAU khi
    // flightImportStep đã COMPLETED.
    @Bean
    public Step markStagingProcessedStep(Tasklet markStagingProcessedTasklet) {
        return new StepBuilder("markStagingProcessedStep", jobRepository)
                .tasklet(markStagingProcessedTasklet, transactionManager)
                .build();
    }
}
```

**Import cần dùng** (đúng Spring Batch 6.0.5 - khác nhiều tutorial cũ):
`org.springframework.batch.core.job.Job`,
`org.springframework.batch.core.step.Step`,
`org.springframework.batch.core.job.builder.JobBuilder`,
`org.springframework.batch.core.step.builder.StepBuilder`,
`org.springframework.batch.core.repository.JobRepository`,
`org.springframework.batch.core.step.tasklet.Tasklet`,
`org.springframework.batch.infrastructure.item.{ItemReader,ItemProcessor,ItemWriter}`.

**Tắt Job tự chạy lúc khởi động app — dễ bị bỏ sót:** Spring Boot Batch mặc
định **tự chạy Job** lúc app start nếu chỉ có 1 `Job` bean trong context
(`JobLauncherApplicationRunner`), với `jobParameters` **rỗng** — Job này cần
`batchId` nên sẽ crash ngay (`NullPointerException` trong Reader ở Bước 8)
mỗi lần bạn khởi động app, TRƯỚC CẢ KHI bạn kịp test gì. Thêm vào
`application.properties`:

```properties
spring.batch.job.enabled=false
```

Job chỉ nên chạy khi Consumer#2 (Bước 10) gọi tay với đúng `batchId`.

**Tự kiểm tra:** start app, xác nhận **không** có exception `Job:
[SimpleJob: [name=flightImportJob]] completed... status: [FAILED]` trong
log (nếu còn thấy dòng này, chưa tắt được auto-run ở trên).

**JobRepository dùng Postgres thật (bắt buộc, không tuỳ chọn):** mặc định
Spring Boot Batch 6 dùng `ResourcelessJobRepository` (in-memory, KHÔNG tạo
bảng `BATCH_*`, KHÔNG giữ lịch sử qua nhiều lần chạy) — không đủ cho
`GIAO_AN.md` yêu cầu tra `BATCH_JOB_EXECUTION` và tính năng "tự chặn
JobInstance trùng". Tạo `config/BatchJdbcConfig.java`:

```java
@Configuration
public class BatchJdbcConfig extends JdbcDefaultBatchConfiguration {
}
```

`JdbcDefaultBatchConfiguration` tự dùng `DataSource`/`PlatformTransactionManager`
đã có sẵn trong context (không cần cấu hình gì thêm) — nhưng **không tự tạo
bảng** (khác các bản Spring Boot cũ trước đây có `spring.batch.jdbc.initialize-schema`,
property này không còn tồn tại trong bản 4.1.1). Tạo
`src/main/resources/schema.sql`, copy nguyên nội dung từ
`org/springframework/batch/core/schema-postgresql.sql` bên trong jar
`spring-batch-core` (mở jar bằng bất kỳ tool giải nén nào, hoặc tìm trong
`~/.gradle/caches/modules-2/...`), thêm `IF NOT EXISTS` vào mỗi `CREATE
TABLE`/`CREATE SEQUENCE` để chạy lại an toàn mỗi lần start app (đúng
`spring.sql.init.mode=always` đã có sẵn từ Task 3).

**Tự kiểm tra:** start app, `\dt` trong `psql` thấy đủ `batch_job_instance`,
`batch_job_execution`, `batch_job_execution_params`, `batch_step_execution`,
`batch_step_execution_context`, `batch_job_execution_context`.

---

## Bước 8 — Reader + Processor

**Reader** — đọc staging theo `batchId` (nhận qua `JobParameters`, xem Bước
10). Tự viết `sync/batch/reader/` hoặc `batch/reader/`. Phần khai báo bean
+ constructor `RepositoryItemReader` thuần cú pháp framework, cho code mẫu
thật (đã tự chạy thử, KHÔNG phải chép tutorial - xem "2 điều dễ vỡ" ngay
dưới):

```java
@Configuration
public class FlightStagingReaderConfig {

    @Bean
    @StepScope // BẮT BUỘC - để đọc được jobParameters (batchId) tại thời điểm Step chạy, không phải lúc app khởi động
    public RepositoryItemReader<FlightStagingRecord> flightStagingReader(
            FlightStagingRepository repository,
            @Value("#{jobParameters['batchId']}") String batchId) {

        RepositoryItemReader<FlightStagingRecord> reader =
                new RepositoryItemReader<>(repository, Map.of("id", Sort.Direction.ASC));
        reader.setMethodName("findByBatchIdAndProcessedFalse");
        reader.setArguments(List.of(batchId));
        return reader;
    }
}
```

**Câu hỏi tự trả lời:** vì sao `@StepScope` bắt buộc ở đây, thiếu nó sẽ lỗi
gì? (Gợi ý: không có `@StepScope`, Spring tạo bean này **1 lần duy nhất** lúc
context khởi động — lúc đó `jobParameters['batchId']` chưa tồn tại (batchId
chỉ có khi `JobOperator.start()` được gọi ở Bước 10) → ném lỗi hoặc luôn đọc
`batchId` rỗng/của lần chạy đầu tiên cho mọi lần chạy sau.)

**2 điều chỉ lộ ra khi CHẠY THẬT, không thấy được nếu chỉ đọc code (đã tự
verify bằng test, không phải đoán):** khi `RepositoryItemReader` cần trang
tiếp theo, nó dùng **reflection** gọi lại đúng `methodName` bạn khai báo,
nhưng **tự động thêm 1 tham số `Pageable` vào cuối** lời gọi — tức là thực
tế nó gọi `repository.findByBatchIdAndProcessedFalse(batchId, pageable)`,
**2 tham số**, không phải 1 như bạn khai báo ở `setArguments`. Vì vậy:
1. `FlightStagingRepository` (Bước 3) **bắt buộc** có overload nhận thêm
   `Pageable` — thiếu overload này, chạy thử ăn ngay
   `NoSuchMethodException` lúc `reader.read()`.
2. Overload đó **bắt buộc trả về `Slice<>`/`Page<>`**, không phải `List<>`
   thường — reader tự `cast` kết quả sang `Slice` để biết "còn trang tiếp
   không" (`hasNext()`); trả `List` thường thì cast lỗi ngay
   `ClassCastException`.

Nói cách khác: khác với CRUD thường (bạn chủ động gọi, tự biết chữ ký
method), ở đây bạn cấu hình cho **framework tự gọi hộ** qua reflection, nên
chữ ký method interface phải khớp đúng với cái framework **sẽ** gọi, chứ
không phải cái bạn thấy tiện khi viết — chỉ chạy thử thật (viết 1 test gọi
`reader.open()`/`reader.read()` trực tiếp) mới bắt được lệch pha này.

**Processor** — validate + map sang `FlightTicketInventory`:

```java
@Component
public class FlightStagingProcessor
        implements ItemProcessor<FlightStagingRecord, FlightTicketInventory> {

    @Override
    public FlightTicketInventory process(FlightStagingRecord raw) {
        // TODO:
        // 1. Validate: giá > 0, seatsLeft >= 0, departureTime hợp lệ (không
        //    null, ở tương lai) - dòng lỗi thì return null (Spring Batch tự
        //    bỏ qua item null, không throw làm fail cả chunk)
        // 2. Map raw.rawXxx -> field tương ứng của FlightTicketInventory
        //    (nhớ: totalSeats/availableSeats lấy từ đâu? seatsLeft của mock
        //    map vào field nào - tự quyết định, xem lại Bước 1)
        // 3. status = OPEN, sourceSystem = "SYNC" (khác "MANUAL" của Task 4)
    }
}
```

**Tự kiểm tra:** chưa chạy được full Job (cần Writer) — viết unit test JUnit
gọi thẳng `processor.process(...)` với 1 `FlightStagingRecord` giả, assert
map đúng field, và assert trả `null` khi input có giá âm. Lưu ý
`totalSeats`/`availableSeats` map cùng lấy từ `seatsLeft` của mock (mock
không có khái niệm "tổng ghế" riêng) — Writer ở Bước 9 mới là nơi quyết
định có ghi đè `totalSeats` hay không khi update.

---

## Bước 9 — Writer: upsert vào Inventory theo unique key

**Đây là phần quan trọng nhất Task 5** — quyết định "insert mới hay update
bản ghi cũ" dựa theo đúng unique key đã thiết kế ở Task 4
(`flightCode + departureTime + seatClass`).

```java
@Component
@RequiredArgsConstructor
public class FlightInventoryWriter implements ItemWriter<FlightTicketInventory> {

    private final InventoryRepository inventoryRepository;

    @Override
    public void write(Chunk<? extends FlightTicketInventory> chunk) {
        for (FlightTicketInventory incoming : chunk) {
            // TODO:
            // 1. Tìm bản ghi cũ bằng
            //    inventoryRepository.findByFlightCodeAndDepartureTimeAndSeatClass(...)
            //    (method đã có sẵn từ Task 4, dùng lại luôn)
            // 2. Nếu CÓ -> đây là UPDATE: chỉ cập nhật field "sống" (price,
            //    availableSeats, lastSyncedAt = now) - KHÔNG tạo row mới,
            //    KHÔNG động vào id/status/totalSeats hiện tại (VD nếu admin
            //    đã tay CLOSED 1 vé, sync không nên tự mở lại - tự quyết
            //    định có đúng nghiệp vụ này không, hay sync luôn override)
            // 3. Nếu KHÔNG có -> INSERT mới, set sourceSystem = "SYNC"
            //    (đã set ở Processor), lastSyncedAt = now, createdAt = now
        }
    }
}
```

`Chunk` import từ `org.springframework.batch.infrastructure.item.Chunk`
(Spring Batch 6 - xem cảnh báo phiên bản ở đầu file).

**Câu hỏi tự trả lời:** vì sao Writer nên tự query `findBy...` rồi quyết định
insert/update trong code (thay vì để Hibernate tự "upsert" qua
`saveOrUpdate`)? (Gợi ý: `save()` của JPA chỉ insert-vs-update dựa theo `id`
đã có hay chưa — dữ liệu từ staging **không có `id` của Inventory**, chỉ có
tổ hợp 3 field định danh nghiệp vụ, nên phải tự tra cứu bằng đúng unique key
đó trước, không thể để JPA tự đoán.)

**Đánh dấu `FlightStagingRecord.processed = true` — KHÔNG làm trong Writer
này (khác gợi ý ban đầu, đọc kỹ lý do):** `Processor` (Bước 8) trả về
`FlightTicketInventory`, không mang theo `id` của `FlightStagingRecord` gốc
— nên `Writer` (chỉ nhận `Chunk<FlightTicketInventory>`) **không có cách
nào** biết chính xác dòng staging nào tương ứng để đánh dấu riêng lẻ. Thay
vào đó, tạo 1 **Step riêng dạng `Tasklet`** chạy SAU khi `flightImportStep`
COMPLETED (đã khai báo ở `flightImportJob` Bước 7), bulk update cả
`batchId` 1 lần:

```java
@Bean
@StepScope // cùng lý do Reader - cần đúng batchId của lần chạy Job này
public Tasklet markStagingProcessedTasklet(
        FlightStagingRepository stagingRepository,
        @Value("#{jobParameters['batchId']}") String batchId) {
    return (contribution, chunkContext) -> {
        List<FlightStagingRecord> remaining = stagingRepository.findByBatchIdAndProcessedFalse(batchId);
        remaining.forEach(record -> record.setProcessed(true));
        stagingRepository.saveAll(remaining);
        return RepeatStatus.FINISHED;
    };
}
```

(`Tasklet` từ `org.springframework.batch.core.step.tasklet.Tasklet`,
`RepeatStatus` từ `org.springframework.batch.infrastructure.repeat.RepeatStatus`.)

Vì sao vẫn AN TOÀN dù tách rời (không đánh dấu ngay trong cùng chunk như
dự tính ban đầu): Step 2 chỉ chạy khi Step 1 **COMPLETED toàn bộ** (mọi
chunk đã commit) — nên lúc bulk update chạy, chắc chắn dữ liệu đã nằm
trong Inventory thật. Nếu Step 1 fail giữa chừng, Step 2 không chạy,
`processed` cả batch vẫn `false` — lần retry sẽ xử lý lại TOÀN BỘ batch
(không phải chỉ phần chưa xong), tốn thêm chút công việc thừa nhưng vẫn
đúng, nhờ Writer đã upsert idempotent theo unique key ở trên (ghi lại lần 2
chỉ là 1 update vô hại, không tạo dòng trùng).

**Tự kiểm tra:** chưa chạy Job được (cần Consumer #2, Bước 10) — có thể viết
test JUnit gọi thẳng `writer.write(...)` với 1 `Chunk` chứa dữ liệu giả
(`new Chunk<>(List.of(candidate))`), assert Inventory được insert/update
đúng — đặc biệt assert case UPDATE **không** ghi đè `status`/`totalSeats`
của dòng cũ.

---

## Bước 10 — Consumer #2: nhận batchId → chạy Job

**Việc cần làm:** tạo `sync/mq/FlightBatchTriggerListener.java`:

```java
@Component
@RequiredArgsConstructor
public class FlightBatchTriggerListener {

    private final JobOperator jobOperator; // KHÔNG dùng JobLauncher - deprecated ở Spring Batch 6, xem cảnh báo phiên bản đầu file
    private final Job flightImportJob;

    @RabbitListener(queues = SyncRabbitMQConfig.QUEUE_BATCH)
    public void handleBatchTrigger(/* payload chứa batchId, tự định nghĩa DTO */) {
        // TODO: build JobParameters bằng JobParametersBuilder, addString("batchId", ...)
        // - THÊM addLong("timestamp", System.currentTimeMillis()) nữa, xem
        // câu hỏi tự trả lời bên dưới - rồi gọi jobOperator.start(flightImportJob, params)
    }
}
```

`JobOperator` (kế thừa `JobLauncher`, có `start(Job, JobParameters)` không
bị deprecated) từ `org.springframework.batch.core.launch.JobOperator` —
bean này tự có sẵn trong context nhờ `config/BatchJdbcConfig.java` (Bước 7),
không cần khai báo thêm gì.

**Câu hỏi tự trả lời:** vì sao nên thêm 1 `JobParameter` phụ như
`timestamp` bên cạnh `batchId`, dù Batch không thật sự dùng field đó để xử
lý? (Gợi ý: đọc phần "Idempotency" ngay dưới trước khi trả lời.)

**Idempotency:** `GIAO_AN.md` lưu ý Spring Batch **tự chặn chạy lại** 1
`JobInstance` với **đúng y hệt** `JobParameters` (coi là đã chạy rồi, ném
`JobInstanceAlreadyCompleteException` nếu cố chạy lại) — đây là tính năng có
sẵn, không cần tự code. **Chỉ hoạt động đúng nếu đã làm phần
`config/BatchJdbcConfig.java` ở Bước 7** — với `JobRepository` mặc định
(`ResourcelessJobRepository`, in-memory), tính năng chặn trùng này gần như
vô nghĩa vì nó chỉ nhớ được đúng 1 `JobInstance` gần nhất, không phải toàn
bộ lịch sử. Nhưng nếu bạn **cố ý muốn** chạy lại đúng `batchId`
đó (VD lần đầu chạy lỗi giữa chừng, muốn thử lại) mà chỉ truyền mỗi
`batchId`, `JobParameters` sẽ trùng hệt lần trước → bị chặn nhầm. Đây là lý
do 1 số thiết kế thêm `timestamp` vào `JobParameters` (mỗi lần gọi
`JobOperator.start()` luôn là 1 `JobInstance` mới) — tự cân nhắc đánh đổi:
thêm `timestamp` thì mất tính năng "tự chặn trùng" của Spring Batch (đổi
lại phải tự đảm bảo idempotency ở tầng Writer bằng unique key, việc bạn đã
làm ở Bước 9 rồi); không thêm thì được lợi "tự chặn trùng" miễn phí nhưng
không tự retry lại đúng batchId cũ được. Không có đáp án tuyệt đối.

**Tự kiểm tra:** đây là lúc **chạy full luồng lần đầu**. Gọi endpoint test
tay ở Bước 5, theo dõi:
1. RabbitMQ Management UI — thấy message đi qua lần lượt 2 queue.
2. `psql`: bảng `flight_staging_record` có dữ liệu mới, `processed` chuyển
   từ `false` sang `true` sau khi Job chạy xong.
3. `psql`: bảng `flight_ticket_inventory` có dòng mới (hoặc dòng cũ được
   cập nhật `lastSyncedAt`, `price`, `availableSeats`).
4. `psql`: bảng `batch_job_execution` (Postgres tự hạ chữ thường tên bảng
   không quote), xem `status = 'COMPLETED'`; `batch_step_execution` xem
   `read_count`/`write_count` đúng số dòng mock trả về.

---

## Bước 11 — Test toàn luồng thủ công (checklist)

Tự đi qua từng dòng, tick khi pass:

- [ ] Gọi tay trigger → thấy dữ liệu mới trong staging, `processed=true` sau
      khi Job chạy xong.
- [ ] `flight_ticket_inventory` có dòng `sourceSystem='SYNC'` mới xuất hiện.
- [ ] Chạy trigger **lần 2** với cùng dữ liệu mock gần giống lần 1 (VD chỉ
      giá/seatsLeft đổi nhẹ) → Inventory **update** đúng dòng cũ (theo unique
      key), **không tạo dòng trùng**.
- [ ] Vé đã bị admin `CLOSED` tay qua Task 4 → sync chạy lại → tự kiểm tra
      status có bị ghi đè không, xác nhận đúng ý đồ thiết kế đã chọn ở Bước 9.
- [ ] Dừng RabbitMQ (`docker compose stop rabbitmq`) rồi trigger tay → xác
      nhận app **không crash**, message ở lại chờ (hoặc lỗi log rõ ràng) —
      bật lại RabbitMQ, xác nhận message được xử lý tiếp (không mất).
- [ ] Cố ý cho staging 1 dòng dữ liệu xấu (giá âm) → xác nhận dòng đó bị
      Processor reject (`return null`), các dòng còn lại trong cùng chunk
      vẫn được import bình thường, Job vẫn `COMPLETED` (không fail cả batch).
- [ ] `batch_job_execution` ghi nhận đủ lịch sử các lần chạy Job (cần
      `config/BatchJdbcConfig.java` + `schema.sql` ở Bước 7 mới có bảng này).

---

## Bước 12 (tuỳ chọn) — Test tự động

Viết test theo pattern đã dùng ở Task 2-4: unit test cho Processor (validate
reject dòng xấu), unit test cho Writer (insert vs update theo unique key,
dùng `spring-batch-test` để test `ItemWriter` độc lập không cần chạy cả
`Job`), và 1 integration test chạy `JobLauncherTestUtils` (tiện ích của
`spring-batch-test`) launch thẳng `flightImportJob` với `batchId` giả, assert
kết quả cuối trong `flight_ticket_inventory`.

Không bắt buộc để coi là "xong Task 5", nhưng rất đáng làm — luồng nhiều
thành phần rời rạc thế này rất dễ vỡ âm thầm khi sửa code sau này mà không
có test giữ lại hành vi đúng.

---

## Khi nào coi là xong Task 5?

Tick hết checklist Bước 11, tự tin giải thích được (không cần nhìn code) 5
câu hỏi sau:

1. Vì sao cần bảng staging riêng thay vì Consumer #1 ghi thẳng vào
   `FlightTicketInventory`?
2. Vì sao Reader cần `@StepScope`, thiếu nó lỗi gì xảy ra?
3. Vì sao Writer phải tự `findBy...` rồi quyết định insert/update, không
   dùng thẳng `save()` để JPA tự "upsert"?
4. Đánh đổi giữa việc thêm `timestamp` vào `JobParameters` (mất tính năng
   "tự chặn chạy trùng" của Spring Batch) là gì, và bạn đã chọn hướng nào?
5. Nếu RabbitMQ chết giữa lúc Consumer #1 đang xử lý, dữ liệu có bị mất
   không? Vì sao (liên hệ tới acknowledgment mode đã đọc ở Bước 6)?

Xong thì quay lại báo mình như thường lệ để review trước khi sang Task 6
(tìm kiếm vé — lúc này `TASK6_SEARCH_REDIS_CACHE.md` đã có sẵn design chốt
từ trước, dữ liệu thật từ Task 5 sẽ dùng để test luồng search + cache đó).
