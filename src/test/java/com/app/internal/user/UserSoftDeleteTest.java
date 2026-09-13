package com.app.internal.user;

import com.app.internal.auth.dto.LoginRequest;
import com.app.internal.auth.repository.UserRepository;
import com.app.internal.auth.service.AuthService;
import com.app.internal.booking.entity.Booking;
import com.app.internal.booking.enums.BookingStatus;
import com.app.internal.booking.repository.BookingRepository;
import com.app.internal.common.exception.UserNotFoundException;
import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.InventoryStatus;
import com.app.internal.inventory.enums.SeatClass;
import com.app.internal.inventory.repository.InventoryRepository;
import com.app.internal.role.repository.RoleRepository;
import com.app.internal.user.entity.User;
import com.app.internal.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Kiểm chứng đúng ghi chú "Soft-delete tốt hơn xoá cứng vì Booking sẽ tham
// chiếu tới User" ở GIAO_AN.md (Task 2) - chạy trên Postgres thật, không mock.
@SpringBootTest
class UserSoftDeleteTest {

    private static final String RAW_PASSWORD = "irrelevant-password";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    private Long userId;
    private Long bookingId;
    private Long inventoryId;
    private String email;

    @BeforeEach
    void setUp() {
        email = "soft-delete-test-" + System.nanoTime() + "@example.com";

        User user = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(RAW_PASSWORD))
                .fullName("Soft Delete Test User")
                .status("ACTIVE")
                .role(roleRepository.findByName("CUSTOMER").orElseThrow())
                .createdAt(LocalDateTime.now())
                .build());
        userId = user.getId();

        FlightTicketInventory inventory = inventoryRepository.save(FlightTicketInventory.builder()
                .provider("MANUAL")
                .flightCode("VN999")
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
        inventoryId = inventory.getId();

        Booking booking = bookingRepository.save(Booking.builder()
                .user(user)
                .inventory(inventory)
                .passengerCount(1)
                .status(BookingStatus.PENDING)
                .totalPrice(inventory.getPrice())
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .build());
        bookingId = booking.getId();
    }

    @AfterEach
    void tearDown() {
        bookingRepository.deleteById(bookingId);
        inventoryRepository.deleteById(inventoryId);
        userRepository.deleteById(userId);
    }

    @Test
    void softDelete_giuNguyenRowVaKhongViPhamFkCuaBooking() {
        assertNull(userRepository.findById(userId).orElseThrow().getDeletedAt());

        userService.softDeleteUser(userId);

        User afterDelete = userRepository.findById(userId).orElseThrow();
        assertNotNull(afterDelete.getDeletedAt(), "deletedAt phải được set sau khi soft-delete");
        assertEquals("INACTIVE", afterDelete.getStatus());

        // Booking vẫn còn nguyên, FK user_id vẫn trỏ đúng userId - không có
        // exception nào về ràng buộc khoá ngoại xảy ra ở bước softDeleteUser().
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        assertEquals(userId, booking.getUser().getId());
    }

    @Test
    void userDaBiSoftDelete_khongTheLoginDuoc() {
        userService.softDeleteUser(userId);

        LoginRequest loginRequest = new LoginRequest(email, RAW_PASSWORD);

        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> authService.login(loginRequest));
        assertEquals("Sai email hoặc mật khẩu", ex.getMessage());
    }

    @Test
    void goiSoftDeleteHaiLan_khongNemLoi_idempotent() {
        userService.softDeleteUser(userId);
        LocalDateTime firstDeletedAt = userRepository.findById(userId).orElseThrow().getDeletedAt();

        assertDoesNotThrow(() -> userService.softDeleteUser(userId));

        LocalDateTime secondDeletedAt = userRepository.findById(userId).orElseThrow().getDeletedAt();
        assertEquals(firstDeletedAt, secondDeletedAt, "Gọi lần 2 không được ghi đè lại deletedAt");
    }

    @Test
    void softDeleteUserKhongTonTai_nemUserNotFoundException() {
        long khongTonTaiId = -1L;

        assertTrue(userRepository.findById(khongTonTaiId).isEmpty());
        assertThrows(UserNotFoundException.class, () -> userService.softDeleteUser(khongTonTaiId));
    }
}
