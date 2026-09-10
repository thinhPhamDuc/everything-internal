package com.app.internal.role.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class AssignPermissionRequest {

    @NotEmpty(message = "Danh sách permission không được để trống")
    private List<String> permissionCodes;
}
