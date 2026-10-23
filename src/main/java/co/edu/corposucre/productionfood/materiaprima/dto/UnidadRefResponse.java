package co.edu.corposucre.productionfood.materiaprima.dto;

/**
 * Referencia embebida a una unidad de medida dentro de la respuesta de materia prima (HU-08).
 *
 * <p>No reutiliza {@code UnidadMedidaResponse} porque ese DTO es del catálogo HU-07 y trae el
 * bloque {@code usos}, que no aplica a la materia prima; aquí solo hace falta identificar la
 * unidad con su nombre y su abreviatura.
 */
public record UnidadRefResponse(
    Integer idUnidad,
    String nombre,
    String abreviatura) {}
