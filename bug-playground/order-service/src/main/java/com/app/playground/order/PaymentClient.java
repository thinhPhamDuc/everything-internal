package com.app.playground.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bug #1 (Cascading Failure) sống ở đây.
 *
 * payment.client.read-timeout-ms=0 (mặc định - BUG): request thread của
 * order-service (Tomcat pool bị giới hạn nhỏ cố ý ở application.properties)
 * sẽ BLOCK đến khi payment-service trả lời xong, dù chậm bao lâu. Bắn ~15-20
 * request đồng thời trong lúc payment-service bị chỉnh chậm (xem
 * payment-service /chaos/config) là đủ ăn hết thread pool -> order-service
 * NGỪNG nhận mọi request khác (kể cả /actuator/health) cho tới khi payment
 * trả lời xong. Sửa bằng cách set PAYMENT_CLIENT_READ_TIMEOUT_MS > 0 - thread
 * bị giải phóng đúng hạn thay vì treo vô thời hạn theo tốc độ downstream.
 */
@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestClient restClient;
    private final boolean propagateEnabled;

    public PaymentClient(
            @Value("${payment.service.base-url}") String baseUrl,
            @Value("${payment.client.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${payment.client.read-timeout-ms:0}") int readTimeoutMs,
            @Value("${tracing.propagate.enabled:false}") boolean propagateEnabled) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs); // 0 = Spring quy ước "không giới hạn"
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.propagateEnabled = propagateEnabled;
        log.info("payment_client_configured base_url={} connect_timeout_ms={} read_timeout_ms={}",
                baseUrl, connectTimeoutMs, readTimeoutMs);
    }

    public String chargeSync(String orderId, double amount) {
        RestClient.RequestBodySpec spec = restClient.post().uri("/payments/{id}/pay-sync", orderId);
        if (propagateEnabled) {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                spec = spec.header(CorrelationIdFilter.HEADER, correlationId);
            }
        }
        long start = System.currentTimeMillis();
        try {
            String body = spec.retrieve().body(String.class);
            log.info("payment_sync_call_ok order_id={} elapsed_ms={}", orderId, System.currentTimeMillis() - start);
            return body;
        } catch (Exception ex) {
            log.warn("payment_sync_call_failed order_id={} elapsed_ms={} error={}",
                    orderId, System.currentTimeMillis() - start, ex.getMessage());
            throw ex;
        }
    }
}
