package com.app.internal.user.dto;

import com.app.internal.user.enums.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UserUpdateStatusRequest {

    @NotNull(message = "Trạng thái không được để trống")
    private UserStatus status;

}
