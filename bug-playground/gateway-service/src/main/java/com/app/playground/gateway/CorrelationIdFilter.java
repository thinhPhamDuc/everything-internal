package com.app.playground.gateway;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Bug #3 (Distributed Tracing bị ngắt đoạn) sống ở đây.
 *
 * propagateEnabled=false (mặc định - BUG): filter LUÔN tự sinh 1
 * correlation_id mới cho MỌI request, kể cả khi header đã có sẵn từ service
 * gọi trước - y hệt lỗi thực tế "quên đọc/forward header" khi mỗi team viết
 * filter riêng mà không thống nhất.
 *
 * propagateEnabled=true (fix): tôn trọng header đã có, chỉ sinh id mới khi
 * đây thực sự là request gốc (không có header đến).
 */
@Component
public class CorrelationIdFilter implements Filter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlation_id";

    private final boolean propagateEnabled;

    public CorrelationIdFilter(@Value("${tracing.propagate.enabled:false}") boolean propagateEnabled) {
        this.propagateEnabled = propagateEnabled;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        String incoming = http.getHeader(HEADER);
        String correlationId = (propagateEnabled && incoming != null && !incoming.isBlank())
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
