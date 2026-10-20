package co.edu.corposucre.productionfood.unidadmedida.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Respuesta de unidad de medida del catálogo de unidades (HU-07).
 *
 * <p>La anotación {@code @JsonInclude(ALWAYS)} es un override por DTO: la configuración global
 * {@code spring.jackson.default-property-inclusion: non_null} hace que un campo nulo
 * desaparezca del JSON en lugar de aparecer como {@code null}, y el contrato de HU-07 exige que
 * {@code usos} se vea siempre, incluso cuando la unidad todavía no está en uso en ninguna tabla.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnidadMedidaResponse(
    Integer idUnidad,
    String nombre,
    String abreviatura,
    UnidadEnUsoResponse usos) {}
