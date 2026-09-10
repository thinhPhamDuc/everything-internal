package com.app.internal.auth.service;

import com.app.internal.auth.dto.LoginRequest;
import com.app.internal.auth.dto.RegisterRequest;
import com.app.internal.auth.dto.RegisterResponse;
import com.app.internal.auth.dto.TokenResponse;
import com.app.internal.auth.jwt.JwtProvider;
import com.app.internal.auth.repository.UserRepository;
import com.app.internal.common.exception.DuplicateEmailException;
import com.app.internal.common.exception.RoleNotFoundException;
import com.app.internal.notification.EmailService;
import com.app.internal.notification.dto.UserRegisteredEvent;
import com.app.internal.notification.mq.UserEventPublisher;
import com.app.internal.role.entity.Permission;
import com.app.internal.role.entity.Role;
import com.app.internal.role.repository.RoleRepository;
import com.app.internal.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String DEFAULT_ROLE = "CUSTOMER";
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final EmailService emailService; // giữ lại để đối chiếu với cách gọi trực tiếp (đã comment bên dưới)
    private final UserEventPublisher userEventPublisher;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException("Email đã được sử dụng");
        }

        // DEFAULT_ROLE giờ trỏ tới 1 Role thật trong DB (seed ở data.sql) thay
        // vì gán thẳng chuỗi "CUSTOMER" như trước Task 3.
        Role defaultRole = roleRepository.findByName(DEFAULT_ROLE)
                .orElseThrow(() -> new RoleNotFoundException(
                        "Role mặc định \"" + DEFAULT_ROLE + "\" chưa được seed trong DB"));

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .status(STATUS_ACTIVE)
                .role(defaultRole)
                .createdAt(LocalDateTime.now())
                .build();

        User saved = userRepository.save(user);

        // Đăng ký hook theo dõi vòng đời transaction — mail chỉ thật sự được
        // gửi ở afterCommit(), tức sau khi user đã chắc chắn nằm trong DB.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

            @Override
            public void beforeCommit(boolean readOnly) {
                log.info("[TX] beforeCommit(readOnly={}) - userId={} sắp được commit", readOnly, saved.getId());
            }

            @Override
            public void beforeCompletion() {
                log.info("[TX] beforeCompletion() - chuẩn bị đóng transaction cho userId={}", saved.getId());
            }

            @Override
            public void afterCommit() {
                log.info("[TX] afterCommit() - userId={} đã chắc chắn có trong DB, gửi mail chào mừng", saved.getId());

                // --- CÁCH CŨ: gọi thẳng EmailService, chạy ĐỒNG BỘ ngay trên
                // thread đang xử lý transaction này -> nếu SMTP chậm/treo, thread
                // xử lý request /auth/register cũng bị chặn theo tới khi gửi xong.
                // emailService.sendWelcomeEmail(saved.getEmail(), saved.getFullName());

                // --- CÁCH MỚI: chỉ bắn 1 event vào RabbitMQ rồi return ngay,
                // việc gửi mail thật sự được UserRegisteredEventListener xử lý
                // BẤT ĐỒNG BỘ trên thread/consumer riêng (xem package notification.mq).
                userEventPublisher.publishUserRegistered(
                        new UserRegisteredEvent(saved.getId(), saved.getEmail(), saved.getFullName()));
            }

            @Override
            public void afterCompletion(int status) {
                String statusText = switch (status) {
                    case TransactionSynchronization.STATUS_COMMITTED -> "COMMITTED";
                    case TransactionSynchronization.STATUS_ROLLED_BACK -> "ROLLED_BACK";
                    default -> "UNKNOWN";
                };
                log.info("[TX] afterCompletion(status={}) - userId={}", statusText, saved.getId());
            }
        });

        return new RegisterResponse(saved.getId(), saved.getEmail(), saved.getFullName());
    }

    // @Transactional(readOnly = true) bắt buộc từ Task 3 - user.getRole() và
    // role.getPermissions() đều FetchType.LAZY, cần Session còn mở tới lúc
    // đọc permissions bên dưới (cùng lý do đã áp dụng ở UserService/RoleService).
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.email())
                .orElseThrow(() -> new BadCredentialsException("Sai email hoặc mật khẩu"));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Sai email hoặc mật khẩu");
        }

        // Tài khoản bị admin khoá (status != ACTIVE, VD "INACTIVE") vẫn còn
        // deletedAt == null nên qua được check ở trên - phải chặn riêng ở đây,
        // dùng CHUNG message với case sai mật khẩu để không lộ việc tài khoản
        // tồn tại và đang bị khoá.
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BadCredentialsException("Sai email hoặc mật khẩu");
        }

        List<String> roles = List.of(user.getRole().getName());
        List<String> permissions = user.getRole().getPermissions().stream()
                .map(Permission::getCode)
                .toList();
        String token = jwtProvider.generateToken(user.getId(), roles, permissions);

        return new TokenResponse(token);
    }
}
