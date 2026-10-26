package co.edu.corposucre.productionfood.compra.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

/**
 * Petición de edición de los campos editables de la cabecera de una compra (HU-13).
 *
 * <p>La compra puede cambiar de proveedor y de fecha mientras esté {@code PENDIENTE}; el estado
 * no se edita por aquí porque tiene su propio endpoint con la máquina de transiciones. Aquí solo
 * viven las validaciones de forma; que el proveedor exista y esté activo lo valida el servicio
 * ({@code PROVEEDOR_INEXISTENTE}, CP-08).
 */
public record ActualizarCompraRequest(
        @NotNull(message = "El proveedor es obligatorio")
        Integer idProveedor,

        @NotNull(message = "La fecha es obligatoria")
        LocalDate fecha) {
}
