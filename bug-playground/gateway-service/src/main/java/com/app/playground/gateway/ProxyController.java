package com.app.playground.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/**
 * Reverse-proxy thủ công, cố tình KHÔNG dùng Spring Cloud Gateway để tự tay
 * kiểm soát chỗ chèn 2 bug:
 *
 * Bug #4 (Routing): "payment-target" mặc định trỏ NHẦM vào order-service
 * (xem application.properties) - y hệt lỗi copy-paste route thật trong đội
 * ngũ vận hành gateway.
 *
 * Bug #3 (Tracing): correlation id chỉ được forward sang downstream khi
 * tracing.propagate.enabled=true - tắt đi là mất dấu vết ngay ở chặng đầu
 * tiên (gateway -> service), dù 2 service phía sau có làm đúng cũng vô ích.
 */
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final RestClient orderServiceClient;
    private final RestClient paymentTargetClient;
    private final boolean propagateEnabled;

    public ProxyController(
            @Value("${gateway.order-service.url}") String orderServiceUrl,
            @Value("${gateway.payment-target.url}") String paymentTargetUrl,
            @Value("${tracing.propagate.enabled:false}") boolean propagateEnabled) {
        this.orderServiceClient = RestClient.create(orderServiceUrl);
        this.paymentTargetClient = RestClient.create(paymentTargetUrl);
        this.propagateEnabled = propagateEnabled;
        log.info("gateway_routes_configured order_service={} payment_target={} tracing_propagate={}",
                orderServiceUrl, paymentTargetUrl, propagateEnabled);
    }

    // Chỉ strip tiền tố "/api" - phần còn lại ("/orders/...", "/payments/...")
    // giữ nguyên vì đó chính là path thật mà order-service/payment-service
    // expose (xem @RequestMapping("/orders") và ("/payments") ở 2 service đó).
    private static final String API_PREFIX = "/api";

    @RequestMapping("/api/orders/**")
    public ResponseEntity<String> proxyOrders(HttpServletRequest request,
                                               @RequestBody(required = false) String body) {
        return forward(orderServiceClient, request, body, API_PREFIX);
    }

    @RequestMapping("/api/payments/**")
    public ResponseEntity<String> proxyPayments(HttpServletRequest request,
                                                 @RequestBody(required = false) String body) {
        return forward(paymentTargetClient, request, body, API_PREFIX);
    }

    private ResponseEntity<String> forward(RestClient client, HttpServletRequest request,
                                            String body, String stripPrefix) {
        String uri = request.getRequestURI().substring(stripPrefix.length());
        if (uri.isEmpty()) {
            uri = "/";
        }
        String queryString = request.getQueryString();
        if (queryString != null) {
            uri = uri + "?" + queryString;
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        RestClient.RequestBodySpec spec = client.method(method).uri(uri);

        if (propagateEnabled) {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                spec = spec.header(CorrelationIdFilter.HEADER, correlationId);
            }
        }

        RestClient.RequestHeadersSpec<?> finalSpec = spec;
        if (body != null && !body.isBlank()) {
            finalSpec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }

        log.info("gateway_forward method={} uri={} target={}", method, uri, client);
        try {
            return finalSpec.exchange((clientRequest, clientResponse) -> {
                byte[] bytes = clientResponse.getBody().readAllBytes();
                return ResponseEntity.status(clientResponse.getStatusCode())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new String(bytes, StandardCharsets.UTF_8));
            });
        } catch (Exception ex) {
            log.warn("gateway_downstream_unreachable uri={} error={}", uri, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"downstream unreachable: " + ex.getMessage() + "\"}");
        }
    }
}
