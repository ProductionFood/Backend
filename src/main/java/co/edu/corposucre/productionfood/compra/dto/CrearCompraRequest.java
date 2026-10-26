package co.edu.corposucre.productionfood.compra.dto;

import java.time.LocalDate;

import co.edu.corposucre.productionfood.compra.EstadoCompra;
import jakarta.validation.constraints.NotNull;

/**
 * Petición de creación de una compra (HU-13).
 *
 * <p>Aquí viven solo las validaciones de forma: la obligatoriedad del proveedor. Las reglas de
 * negocio no se replican en el DTO porque deben responder con los códigos de error de la
 * especificación y no con el genérico {@code VALIDACION_FALLIDA}: que el proveedor exista y esté
 * activo ({@code PROVEEDOR_INEXISTENTE}, {@code PROVEEDOR_INACTIVO}) las valida el servicio
 * (CP-02, CP-03).
 *
 * <p>{@code fecha} es opcional: si se omite, el servicio registra la compra con la fecha de hoy
 * (CP-04).
 *
 * <p>{@code estado} es opcional: si se omite, el servicio crea la compra en {@code PENDIENTE}. Si
 * se envía, debe ser un valor canónico del enum; un literal inventado como {@code "ENVIADA"} o
 * {@code "pendiente"} falla en la deserialización de Jackson y responde
 * {@code 400 VALIDACION_FALLIDA} (CP-07/CP-08), sin que este DTO tenga que revalidarlo.
 */
public record CrearCompraRequest(
        @NotNull(message = "El proveedor es obligatorio")
        Integer idProveedor,

        LocalDate fecha,

        EstadoCompra estado) {
}
