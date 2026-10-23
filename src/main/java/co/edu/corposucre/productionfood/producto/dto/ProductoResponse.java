package co.edu.corposucre.productionfood.producto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import co.edu.corposucre.productionfood.common.dto.UnidadRefResponse;
import java.math.BigDecimal;

/**
 * Respuesta de producto del catálogo (HU-11).
 *
 * <p>La unidad de venta se embebe como objeto {@link UnidadRefResponse} y no solo como
 * {@code idUnidad}, de modo que el consumidor no necesita una llamada extra para saber con qué
 * abreviatura se muestra el producto.
 *
 * <p>{@code tieneReceta} y {@code stockDisponible} son calculados y de solo lectura: el primero
 * avisa de un producto que puede venderse pero no planificarse (R-02) y el segundo agrega los
 * lotes vigentes de HU-19.
 *
 * <p>{@code descripcion} lleva {@code @JsonInclude(ALWAYS)} porque la inclusión global de Jackson
 * es {@code non_null}: sin esa anotación, un producto sin descripción perdería la clave del JSON y
 * el contrato de HU-11 exige verla como {@code null} (CP-04). {@code advertencia} queda con la
 * inclusión global, de modo que solo aparece cuando la operación tiene algo que avisar (R-03).
 */
public record ProductoResponse(
    Integer idProducto,
    String nombre,
    @JsonInclude(JsonInclude.Include.ALWAYS) String descripcion,
    BigDecimal precio,
    UnidadRefResponse unidad,
    boolean activo,
    boolean tieneReceta,
    BigDecimal stockDisponible,
    String advertencia) {}
