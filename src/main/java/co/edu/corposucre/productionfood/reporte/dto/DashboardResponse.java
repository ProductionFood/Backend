package co.edu.corposucre.productionfood.reporte.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Resumen del día (HU-24). {@code fecha} y {@code generadoEn} son la fecha y la hora de la base
 * de datos (R-01), la única fuente de «hoy» de todas las tarjetas.
 */
public record DashboardResponse(
        LocalDate fecha,
        LocalDateTime generadoEn,
        PedidosHoyResponse pedidosHoy,
        ComprasPendientesResponse comprasPendientes,
        StockBajoResponse stockBajo,
        ProduccionHoyResponse produccionHoy,
        PorVencerResponse porVencer
) {
}
