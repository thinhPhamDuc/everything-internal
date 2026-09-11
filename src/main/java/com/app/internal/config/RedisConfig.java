package com.app.internal.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

// Task 6 Phase B: RedisTemplate<String, Object> để cache List<FlightTicketInventory>
// nguyên entity (xem SearchCacheService). Spring Boot 4 dùng Jackson 3
// (groupId "tools.jackson", KHÔNG PHẢI "com.fasterxml.jackson.databind" cũ) -
// rebuild() từ chính ObjectMapper mà Spring đã autoconfigure (đã có sẵn cấu
// hình serialize LocalDateTime giống các Controller khác), chỉ bật thêm
// default typing để Jackson biết deserialize đúng lớp cụ thể (FlightTicketInventory)
// từ JSON polymorphic lưu trong Redis. allowIfSubType giới hạn type được phép
// deserialize trong đúng package của app - an toàn hơn "unsafe default typing".
@Configuration
@RequiredArgsConstructor
public class RedisConfig {

    private final ObjectMapper objectMapper;

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // "java." bao trọn BigDecimal/LocalDateTime/ArrayList... - field kiểu
        // JDK dùng trong FlightTicketInventory (đã tự bắt lỗi lúc test: thiếu
        // "java.math." làm mọi lần cache hit lỗi deserialize ngầm, rơi về
        // Postgres không báo lỗi rõ ràng, chỉ thấy qua log WARN).
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.app.internal.")
                .allowIfSubType("java.")
                .build();

        JsonMapper.Builder mapperBuilder = ((JsonMapper) objectMapper).rebuild();
        ObjectMapper redisObjectMapper = mapperBuilder
                .activateDefaultTyping(typeValidator, DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY)
                .build();

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJacksonJsonRedisSerializer(redisObjectMapper));
        template.afterPropertiesSet();
        return template;
    }
}
