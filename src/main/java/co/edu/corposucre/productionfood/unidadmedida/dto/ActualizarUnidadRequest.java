package co.edu.corposucre.productionfood.unidadmedida.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ActualizarUnidadRequest(
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 50, message = "El nombre no puede superar 50 caracteres")
    String nombre,

    @NotBlank(message = "La abreviatura es obligatoria")
    @Size(max = 50, message = "La abreviatura no puede superar 50 caracteres")
    String abreviatura) {}
