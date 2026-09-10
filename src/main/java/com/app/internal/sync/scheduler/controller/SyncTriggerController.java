package com.app.internal.sync.scheduler.controller;

import com.app.internal.sync.scheduler.FlightSyncScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Endpoint test tay - gọi thẳng logic publish trigger, không phải đợi đúng
// lịch cron (app.sync.cron) mới test được luồng.
// Tái dùng permission INVENTORY_MANAGE (Task 4/3) - sync đổ dữ liệu vào
// đúng domain Inventory, chưa cần tách riêng permission SYNC_MANAGE cho MVP.
@RestController
@RequestMapping("/admin/sync")
@PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
@RequiredArgsConstructor
public class SyncTriggerController {

    private final FlightSyncScheduler flightSyncScheduler;

    @PostMapping("/trigger")
    public ResponseEntity<Void> triggerManually() {
        flightSyncScheduler.triggerSync();
        return ResponseEntity.accepted().build();
    }
}
