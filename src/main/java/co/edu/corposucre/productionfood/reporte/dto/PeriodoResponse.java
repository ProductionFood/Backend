package co.edu.corposucre.productionfood.reporte.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Período consultado en el reporte de eficiencia (HU-26). Ambos extremos son inclusivos y viajan
 * {@code null}, de forma explícita, cuando el cliente no envió el filtro.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PeriodoResponse(LocalDate desde, LocalDate hasta) {
}
