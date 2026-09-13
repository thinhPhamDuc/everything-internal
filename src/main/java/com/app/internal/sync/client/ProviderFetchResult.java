package com.app.internal.sync.client;

import com.app.internal.sync.mock.dto.MockFlightDto;

import java.util.List;

// Kết quả fetch của 1 provider - success=false kèm errorMessage khi
// timeout/exception, KHÔNG ném exception ra ngoài fetchAll() (xem
// ThirdPartyFlightFetchService) để 1 provider lỗi không làm hỏng kết quả
// của các provider khác.
public record ProviderFetchResult(
        String providerName,
        boolean success,
        List<MockFlightDto> flights,
        String errorMessage) {

    public static ProviderFetchResult success(String providerName, List<MockFlightDto> flights) {
        return new ProviderFetchResult(providerName, true, flights, null);
    }

    public static ProviderFetchResult failure(String providerName, String errorMessage) {
        return new ProviderFetchResult(providerName, false, List.of(), errorMessage);
    }
}
