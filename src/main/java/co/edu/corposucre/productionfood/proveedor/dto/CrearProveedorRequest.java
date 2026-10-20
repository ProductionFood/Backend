package co.edu.corposucre.productionfood.proveedor.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CrearProveedorRequest(
    @NotBlank(message = "El nombre es obligatorio")
    @Size(max = 100, message = "El nombre no puede superar 100 caracteres")
    String nombre,

    @Size(max = 100, message = "El contacto no puede superar 100 caracteres")
    String contacto,

    @Size(max = 20, message = "El teléfono no puede superar 20 caracteres")
    String telefono,

    @Email(message = "Ingrese un correo válido")
    @Size(max = 100, message = "El correo no puede superar 100 caracteres")
    String correo) {}
