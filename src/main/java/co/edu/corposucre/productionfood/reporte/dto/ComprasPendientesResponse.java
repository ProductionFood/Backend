package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tarjeta «Compras pendientes» del dashboard (HU-24). No lleva filtro de fecha (CA-02).
 *
 * <p>{@code masAntigua} es {@code null} (y se serializa como tal) si no hay compras pendientes.
 */
public record ComprasPendientesResponse(
        long cantidad,
        BigDecimal total,
        @JsonInclude(JsonInclude.Include.ALWAYS) MasAntigua masAntigua
) {

    /** Compra pendiente de menor fecha; {@code diasPendiente} se mide contra el día de la BD. */
    public record MasAntigua(Integer idCompra, LocalDate fecha, long diasPendiente) {
    }
}
