package co.edu.corposucre.productionfood.reporte.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Reporte de eficiencia de producción de un período (HU-26): resumen, planes con consumo
 * registrado y productos que quedaron fuera del análisis por no tener receta (R-07).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record EficienciaResponse(PeriodoResponse periodo, ResumenEficienciaResponse resumen,
                                 List<EficienciaPlanResponse> planes,
                                 List<SinRecetaResponse> sinReceta) {
}
