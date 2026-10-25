package co.edu.corposucre.productionfood.receta.dto;

import java.math.BigDecimal;
import java.util.List;

import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;

/**
 * Respuesta de una receta con su detalle completo (HU-12).
 *
 * <p>{@code cantidadProducir} es el rendimiento de <strong>una tanda completa</strong>, no de una
 * unidad (R-02); {@code unidad} es la unidad de producción (R-04) y por eso se embebe como
 * {@link UnidadRefResponse} en lugar de solo {@code idUnidad}. {@code cantidadProducir} lleva
 * escala 2.
 *
 * <p>{@code completa} indica si la receta tiene al menos una línea de detalle. Una receta vacía se
 * permite crear (R-05), pero no sirve para planificar producción.
 *
 * <p>{@code costoTotalEstimado} y {@code costoPorUnidad} son calculados con el costo de referencia
 * de las materias primas (HU-08 R-03), no con el costo real, y llevan escala 2: el primero es la
 * suma de las líneas y el segundo es esa suma dividida entre {@code cantidadProducir}.
 */
public record RecetaResponse(
    Integer idReceta,
    ProductoRefResponse producto,
    String nombre,
    BigDecimal cantidadProducir,
    UnidadRefResponse unidad,
    boolean activo,
    boolean completa,
    List<DetalleRecetaResponse> detalles,
    BigDecimal costoTotalEstimado,
    BigDecimal costoPorUnidad) {}
