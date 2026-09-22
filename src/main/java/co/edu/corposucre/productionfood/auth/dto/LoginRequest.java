package co.edu.corposucre.productionfood.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
    @NotBlank(message = "El correo es obligatorio")
    @Email(message = "Debe ser un correo electrónico válido")
    String correo,

    @NotBlank(message = "La contraseña es obligatoria")
    String password
) {}
