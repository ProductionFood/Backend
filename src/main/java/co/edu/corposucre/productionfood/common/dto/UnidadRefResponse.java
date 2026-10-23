package co.edu.corposucre.productionfood.common.dto;

/**
 * Referencia embebida a una unidad de medida dentro de las respuestas de los módulos de catálogo
 * (HU-08 materias primas y HU-11 productos).
 *
 * <p>No reutiliza {@code UnidadMedidaResponse} porque ese DTO es del catálogo HU-07 y trae el
 * bloque {@code usos}, que no aplica fuera de ese módulo; aquí solo hace falta identificar la
 * unidad con su nombre y su abreviatura con la que se muestran las cantidades.
 */
public record UnidadRefResponse(
    Integer idUnidad,
    String nombre,
    String abreviatura) {}
