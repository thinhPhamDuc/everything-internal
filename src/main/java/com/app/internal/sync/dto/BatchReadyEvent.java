package com.app.internal.sync.dto;

import java.io.Serializable;

// Payload của message chặng 2 (Consumer#1 -> Consumer#2) - chỉ chứa batchId,
// KHÔNG chứa dữ liệu vé (dữ liệu đã nằm trong bảng staging rồi, Consumer#2
// chỉ cần biết đọc staging theo batchId nào).
public record BatchReadyEvent(String batchId) implements Serializable {
}
