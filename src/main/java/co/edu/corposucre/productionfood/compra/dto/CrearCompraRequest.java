package co.edu.corposucre.productionfood.compra.dto;

import java.time.LocalDate;

import co.edu.corposucre.productionfood.compra.EstadoCompra;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Petición de creación de una compra (HU-13).
 *
 * <p>Aquí viven solo las validaciones de forma: la obligatoriedad del proveedor y del estado. Las
 * reglas de negocio no se replican en el DTO porque deben responder con los códigos de error de
 * la especificación y no con el genérico {@code VALIDACION_FALLIDA}: que el proveedor exista y
 * esté activo ({@code PROVEEDOR_INEXISTENTE}, {@code PROVEEDOR_INACTIVO}) las valida el servicio
 * (CP-02, CP-03).
 *
 * <p>{@code fecha} es opcional: si se omite, el servicio registra la compra con la fecha de hoy
 * (CP-04, CA-02). Un {@code null} explícito sí se rechaza con {@code 400 VALIDACION_FALLIDA}
 * señalando el campo {@code fecha}: Bean Validation no distingue la omisión de un null escrito a
 * mano, así que esa distinción la hace la deserialización en
 * {@link LocalDateObligatoriaDeserializer}, que solo castiga el token {@code null}.
 *
 * <p>{@code estado} es obligatorio (CA-03). Si se envía, debe ser {@code PENDIENTE}: un literal
 * no canónico como {@code "ENVIADA"} o {@code "pendiente"} falla en la deserialización de Jackson
 * y responde {@code 400 VALIDACION_FALLIDA} (CP-07/CP-08), y un estado canónico distinto de
 * {@code PENDIENTE} lo rechaza el servicio con {@code 400 VALIDACION_FALLIDA}, porque una compra
 * solo nace {@code PENDIENTE} (R-02).
 */
public record CrearCompraRequest(
        @NotNull(message = "El proveedor es obligatorio")
        Integer idProveedor,

        @JsonDeserialize(using = LocalDateObligatoriaDeserializer.class)
        LocalDate fecha,

        @NotNull(message = "El estado es obligatorio")
        EstadoCompra estado) {
}
