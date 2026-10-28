package com.hic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InitialAdminSetupRequest {

    @NotBlank(message = "Quraşdırma kodu tələb olunur")
    @Size(max = 256)
    private String setupToken;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters")
    @Pattern(regexp = ".*\\d.*", message = "Password must contain at least one digit")
    private String password;

    @NotBlank(message = "Password confirmation is required")
    @Size(max = 128, message = "Password confirmation must not exceed 128 characters")
    private String confirmPassword;
}
