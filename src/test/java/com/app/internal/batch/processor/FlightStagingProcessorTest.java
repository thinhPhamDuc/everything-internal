package com.app.internal.batch.processor;

import com.app.internal.inventory.entity.FlightTicketInventory;
import com.app.internal.inventory.enums.SeatClass;
import com.app.internal.sync.staging.entity.FlightStagingRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

// Unit test thuần (không cần Spring context) - Processor không có
// dependency nào ngoài chính nó, gọi thẳng process() như TASK5.md gợi ý.
class FlightStagingProcessorTest {

    private final FlightStagingProcessor processor = new FlightStagingProcessor();

    private FlightStagingRecord.FlightStagingRecordBuilder validBuilder() {
        return FlightStagingRecord.builder()
                .batchId("batch-1")
                .provider("PROVIDER_A")
                .rawFlightCode("VN101")
                .rawAirline("Vietnam Airlines")
                .rawOrigin("HAN")
                .rawDestination("SGN")
                .rawDepartureTime(LocalDateTime.now().plusDays(1))
                .rawArrivalTime(LocalDateTime.now().plusDays(1).plusHours(2))
                .rawSeatClass("ECONOMY")
                .rawPrice(new BigDecimal("1200000"))
                .rawSeatsLeft(42)
                .fetchedAt(LocalDateTime.now())
                .processed(false);
    }

    @Test
    void process_mapDungFieldKhiDataHopLe() {
        FlightStagingRecord raw = validBuilder().build();

        FlightTicketInventory result = processor.process(raw);

        assertNotNull(result);
        assertEquals("PROVIDER_A", result.getProvider());
        assertEquals("VN101", result.getFlightCode());
        assertEquals("Vietnam Airlines", result.getAirline());
        assertEquals("HAN", result.getOrigin());
        assertEquals("SGN", result.getDestination());
        assertEquals(SeatClass.ECONOMY, result.getSeatClass());
        assertEquals(new BigDecimal("1200000"), result.getPrice());
        assertEquals(42, result.getTotalSeats());
        assertEquals(42, result.getAvailableSeats());
        assertEquals("SYNC", result.getSourceSystem());
    }

    @Test
    void process_traVeNullKhiGiaAm() {
        FlightStagingRecord raw = validBuilder().rawPrice(new BigDecimal("-100")).build();
        assertNull(processor.process(raw));
    }

    @Test
    void process_traVeNullKhiSeatsLeftAm() {
        FlightStagingRecord raw = validBuilder().rawSeatsLeft(-1).build();
        assertNull(processor.process(raw));
    }

    @Test
    void process_traVeNullKhiDepartureTimeQuaKhu() {
        FlightStagingRecord raw = validBuilder().rawDepartureTime(LocalDateTime.now().minusDays(1)).build();
        assertNull(processor.process(raw));
    }

    @Test
    void process_traVeNullKhiSeatClassKhongHopLe() {
        FlightStagingRecord raw = validBuilder().rawSeatClass("SUPER_VIP").build();
        assertNull(processor.process(raw));
    }
}
