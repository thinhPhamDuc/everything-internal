package com.app.internal.sync.dto;

import java.io.Serializable;
import java.time.Instant;

// Payload "tín hiệu" của message trigger - CỐ Ý không chứa dữ liệu vé, chỉ
// timestamp để log/debug lúc nào trigger được publish. Dữ liệu vé thật chỉ
// xuất hiện sau, khi Consumer#1 tự gọi mock API (Bước 6).
public record SyncTriggerEvent(Instant triggeredAt) implements Serializable {
}
