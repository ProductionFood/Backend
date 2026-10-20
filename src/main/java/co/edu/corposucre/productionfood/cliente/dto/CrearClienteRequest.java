package co.edu.corposucre.productionfood.cliente.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CrearClienteRequest(
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    String nombre,

    @Size(max = 100, message = "El contacto no puede superar 100 caracteres")
    String contacto,

    @Size(max = 20, message = "El teléfono no puede superar 20 caracteres")
    String telefono) {}
