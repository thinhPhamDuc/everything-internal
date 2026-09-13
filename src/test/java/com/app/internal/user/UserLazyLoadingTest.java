package com.app.internal.user;

import com.app.internal.auth.repository.UserRepository;
import com.app.internal.booking.entity.Booking;
import com.app.internal.booking.enums.BookingStatus;
import com.app.internal.booking.repository.BookingRepository;
import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.InventoryStatus;
import com.app.internal.inventory.enums.SeatClass;
import com.app.internal.inventory.repository.InventoryRepository;
import com.app.internal.role.repository.RoleRepository;
import com.app.internal.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.LazyInitializationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Test này KHÔNG kiểm tra business logic — mục đích duy nhất là tự tay tái hiện
// đúng tình huống mô tả trong QA_KIEN_THUC.md (mục "Câu 5 mở rộng"): 1 entity
// được load bởi 1 Session đã đóng, rồi bị truy cập lazy collection sau đó.
//
// Vì sao KHÔNG cần dựng thật controller + RabbitMQ consumer 2 thread để test:
// SimpleJpaRepository (lớp Spring Data JPA dùng để implement UserRepository)
// tự mang sẵn @Transactional(readOnly = true) ở cấp class. Nếu gọi
// userRepository.findById(...) mà KHÔNG có transaction nào bao ngoài (test
// method không có @Transactional, và đây không phải request HTTP nên không có
// OSIV mở sẵn Session), Spring tự mở 1 transaction + Session MỚI chỉ cho riêng
// lệnh gọi đó, rồi đóng lại NGAY khi findById() return — mô phỏng chính xác
// "Session A đã đóng" trước khi dòng user.getBookings() chạy tới.
@Slf4j
@SpringBootTest
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class UserLazyLoadingTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    private Long userId;
    private Long bookingId;
    private Long inventoryId;

    @BeforeEach
    void setUp() {
        User user = userRepository.save(User.builder()
                .email("lazy-test-" + System.nanoTime() + "@example.com")
                .password("irrelevant-hash")
                .fullName("Lazy Test User")
                .status("ACTIVE")
                .role(roleRepository.findByName("CUSTOMER").orElseThrow())
                .createdAt(LocalDateTime.now())
                .build());

        FlightTicketInventory inventory = inventoryRepository.save(FlightTicketInventory.builder()
                .provider("MANUAL")
                .flightCode("VN123")
                .airline("Vietnam Airlines")
                .origin("HAN")
                .destination("SGN")
                .departureTime(LocalDateTime.now().plusDays(1))
                .arrivalTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .seatClass(SeatClass.ECONOMY)
                .price(BigDecimal.valueOf(1_000_000))
                .totalSeats(10)
                .availableSeats(9)
                .status(InventoryStatus.OPEN)
                .sourceSystem("MANUAL")
                .createdAt(LocalDateTime.now())
                .build());

        Booking booking = bookingRepository.save(Booking.builder()
                .user(user)
                .inventory(inventory)
                .passengerCount(1)
                .status(BookingStatus.PENDING)
                .totalPrice(inventory.getPrice())
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .build());

        userId = user.getId();
        bookingId = booking.getId();
        inventoryId = inventory.getId();
    }

    @AfterEach
    void tearDown() {
        bookingRepository.deleteById(bookingId);
        inventoryRepository.deleteById(inventoryId);
        userRepository.deleteById(userId);
    }

    @Test
    void truyCapLazyCollectionSauKhiSessionDaDong_nemLazyInitializationException() {
        // findById() ở đây tự mở rồi tự đóng Session ngay khi return - đúng
        // như "Session A ĐÃ ĐÓNG" trong QA_KIEN_THUC.md.
        User user = userRepository.findById(userId).orElseThrow();

        LazyInitializationException ex = assertThrows(
                LazyInitializationException.class,
                () -> user.getBookings().size());

        log.info("Bắt đúng exception mong đợi: {}", ex.getMessage());
    }

    @Test
    @Transactional
    void truyCapLazyCollectionTrongCungTransaction_khongNemLoi() {
        // Spring test framework chạy @BeforeEach VÀ method này trong CHUNG 1
        // transaction/Session (vì @Transactional đặt trên chính @Test method).
        // Hệ quả không ngờ: userRepository.findById() bên dưới sẽ KHÔNG query
        // lại DB - Hibernate thấy user này đã nằm sẵn trong persistence context
        // (identity map, cùng Session với @BeforeEach) nên trả thẳng lại đúng
        // cái object Lombok .builder() đã tạo, mà object đó có field "bookings"
        // = null thô (chưa từng qua Hibernate proxy). Gọi entityManager.clear()
        // để ép Hibernate quên hết object cũ, buộc findById() phải SELECT lại
        // thật từ DB -> lần này mới nhận về 1 proxy PersistentBag đúng nghĩa,
        // và vì Session vẫn đang mở (đang trong transaction) nên initialize
        // thành công, không ném lỗi.
        entityManager.clear();

        User user = userRepository.findById(userId).orElseThrow();

        assertDoesNotThrow(() -> {
            int size = user.getBookings().size();
            assertEquals(1, size);
        });
    }

    @Test
    @Transactional
    void goiFindByIdCungIdHaiLan_lanDauHitDB_lanSauKhongHitNhungKhongNull() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        entityManager.clear();
        stats.clear();

        long statementsBefore = stats.getPrepareStatementCount();

        // Lần 1: persistence context đang trống (vừa clear()) -> BẮT BUỘC phải
        // SELECT thật xuống DB để load User.
        User first = userRepository.findById(userId).orElseThrow();
        long statementsAfterFirst = stats.getPrepareStatementCount();

        // Lần 2: cùng ID, cùng Session/transaction -> Hibernate thấy đã có sẵn
        // trong identity map, trả thẳng lại đúng cái object đã load ở lần 1,
        // KHÔNG bắn thêm câu SQL nào.
        User second = userRepository.findById(userId).orElseThrow();
        long statementsAfterSecond = stats.getPrepareStatementCount();

        log.info("Số prepared statement: trước={}, sau lần 1={}, sau lần 2={}",
                statementsBefore, statementsAfterFirst, statementsAfterSecond);

        assertSame(first, second, "Lần 2 phải trả về CHÍNH object của lần 1 (identity map)");
        assertTrue(statementsAfterFirst > statementsBefore, "Lần 1 phải có ít nhất 1 câu SQL chạy xuống DB");
        assertEquals(statementsAfterFirst, statementsAfterSecond, "Lần 2 không được bắn thêm câu SQL nào");

        // Điểm mấu chốt cần đính chính: "không hit DB" KHÔNG đồng nghĩa với
        // "null". Vì lần 1 đã thực sự SELECT (khác hẳn kịch bản save() ở test
        // phía trên), Hibernate đã gắn đúng 1 proxy collection cho "bookings"
        // ngay từ lần 1 rồi - lần 2 chỉ là trả lại đúng cái proxy đó, vẫn dùng
        // được bình thường vì Session vẫn đang mở.
        assertNotNull(second.getBookings());
        assertDoesNotThrow(() -> assertEquals(1, second.getBookings().size()));
    }
}
