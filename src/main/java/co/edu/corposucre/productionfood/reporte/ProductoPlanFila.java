package co.edu.corposucre.productionfood.reporte;

import java.math.BigDecimal;

/** Fila cruda de una línea de producto de un plan (HU-26): planificado y producido. */
public record ProductoPlanFila(int idPlan, String producto, BigDecimal planificada,
                               BigDecimal producida) {
}
