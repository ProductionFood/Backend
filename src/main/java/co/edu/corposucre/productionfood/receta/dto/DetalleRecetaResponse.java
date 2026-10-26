package co.edu.corposucre.productionfood.receta.dto;

import java.math.BigDecimal;

/**
 * Respuesta de una línea de detalle de receta (HU-12).
 *
 * <p>{@code cantidad} lleva escala 3 (R-06) y {@code costoEstimado} escala 2. El costo usa el
 * costo unitario de referencia de la materia prima (HU-08 R-03), no el costo real de cada compra.
 */
public record DetalleRecetaResponse(
    Integer idDetalleReceta,
    MateriaPrimaRefResponse materiaPrima,
    BigDecimal cantidad,
    BigDecimal costoEstimado) {}
