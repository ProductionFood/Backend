package co.edu.corposucre.productionfood.usuario.dto;

import jakarta.validation.constraints.NotNull;

public record CambiarEstadoRequest(
    @NotNull(message = "El estado es obligatorio")
    Boolean activo
) {}
