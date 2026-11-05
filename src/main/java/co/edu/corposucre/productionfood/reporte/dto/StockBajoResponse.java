package co.edu.corposucre.productionfood.reporte.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tarjeta «Stock bajo» del dashboard (HU-24, CA-03). Reutiliza las alertas de HU-27:
 * {@code cantidad} y {@code agotadas} se derivan de la misma lista que {@code items}, que
 * conserva su orden de urgencia.
 */
public record StockBajoResponse(long cantidad, long agotadas, List<Item> items) {

    /** Materia prima en alerta; {@code nivel} es {@code AGOTADO} o {@code BAJO}. */
    public record Item(String nombre, BigDecimal disponible, BigDecimal minimo,
                       BigDecimal faltante, String nivel) {
    }
}
