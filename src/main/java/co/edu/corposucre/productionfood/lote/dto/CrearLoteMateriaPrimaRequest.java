package co.edu.corposucre.productionfood.lote.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Petición de creación de un lote de materia prima (HU-09).
 *
 * <p>Aquí viven solo las validaciones de forma: obligatoriedad, longitud y dominio de la columna.
 * Las reglas de negocio no se replican en el DTO porque deben responder con los códigos de error
 * de la especificación y no con el genérico {@code VALIDACION_FALLIDA}: el orden de las fechas
 * ({@code FECHAS_INVALIDAS}), que la materia prima exista ({@code MATERIA_PRIMA_INEXISTENTE}), que
 * el código no esté repetido ({@code CODIGO_LOTE_DUPLICADO}) y que la cantidad sea mayor que cero
 * ({@code CANTIDAD_INVALIDA}) las valida el servicio.
 *
 * <p>Por eso {@code cantidad} no lleva {@code @DecimalMin}: rechazar aquí el cero o el negativo
 * devolvería {@code VALIDACION_FALLIDA} en lugar del {@code CANTIDAD_INVALIDA} exigido (CP-12 y
 * CP-13). {@code @Digits} sí se queda, porque replica el dominio {@code decimal(10,2)} de la
 * columna y un valor con más decimales no es representable.
 *
 * <p>{@code idDetalleCompra} es opcional: informarlo indica que el lote nace de una compra ya
 * recibida y no debe volver a sumar al inventario (R-02). Sin él, el lote es un ingreso manual y
 * sí suma (R-01).
 */
public record CrearLoteMateriaPrimaRequest(
    @NotBlank(message = "El código del lote es obligatorio")
    @Size(max = 30, message = "El código del lote no puede superar 30 caracteres")
    String codigoLote,

    @NotNull(message = "La materia prima es obligatoria")
    Integer idMateriaPrima,

    Integer idDetalleCompra,

    @NotNull(message = "La fecha de producción es obligatoria")
    LocalDate fechaProduccion,

    @NotNull(message = "La fecha de vencimiento es obligatoria")
    LocalDate fechaVencimiento,

    @NotNull(message = "La cantidad es obligatoria")
    @Digits(integer = 8, fraction = 2,
            message = "La cantidad admite máximo 8 dígitos enteros y 2 decimales")
    BigDecimal cantidad) {}
