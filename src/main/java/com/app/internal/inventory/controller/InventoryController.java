package com.app.internal.inventory.controller;

import com.app.internal.inventory.dto.InventoryCreateRequest;
import com.app.internal.inventory.dto.InventoryResponse;
import com.app.internal.inventory.dto.InventoryUpdateRequest;
import com.app.internal.inventory.dto.InventoryUpdateStatusRequest;
import com.app.internal.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

// hasAuthority thay vì hasRole - theo đúng hướng permission-based đã áp dụng
// từ Task 3 cho UserController; INVENTORY_MANAGE được seed cho cả ADMIN và
// STAFF (nhân viên vận hành CRUD inventory - đúng overview gốc của dự án).
@RestController
@RequestMapping("/admin/inventory")
@PreAuthorize("hasAuthority('INVENTORY_MANAGE')")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    public ResponseEntity<Page<InventoryResponse>> list(
            @RequestParam(defaultValue = "") String origin,
            @RequestParam(defaultValue = "") String destination,
            @PageableDefault(size = 20, sort = "departureTime") Pageable pageable) {
        return ResponseEntity.ok(inventoryService.listInventory(origin, destination, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InventoryResponse> getById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(inventoryService.getInventoryById(id));
    }

    @PostMapping
    public ResponseEntity<InventoryResponse> create(@Valid @RequestBody InventoryCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(inventoryService.createInventory(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<InventoryResponse> update(
            @PathVariable("id") Long id,
            @Valid @RequestBody InventoryUpdateRequest request) {
        return ResponseEntity.ok(inventoryService.updateInventory(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<InventoryResponse> updateStatus(
            @PathVariable("id") Long id,
            @Valid @RequestBody InventoryUpdateStatusRequest request) {
        return ResponseEntity.ok(inventoryService.changeStatus(id, request.getStatus()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> close(@PathVariable("id") Long id) {
        inventoryService.closeInventory(id);
        return ResponseEntity.noContent().build();
    }
}
