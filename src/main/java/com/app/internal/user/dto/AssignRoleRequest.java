package com.app.internal.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AssignRoleRequest {

    @NotNull(message = "roleId không được để trống")
    private Long roleId;
}
