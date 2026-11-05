package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Tarjeta «Producción de hoy» del dashboard (HU-24, CA-04).
 *
 * <p>{@code cantidad} cuenta todos los planes del día, cancelados incluidos; {@code porEstado}
 * trae siempre las cuatro claves de {@code EstadoPlan}. {@code avancePromedio} (escala 2) es el
 * promedio del avance de los planes no cancelados.
 */
public record ProduccionHoyResponse(long cantidad, Map<String, Long> porEstado,
                                    BigDecimal avancePromedio) {
}
