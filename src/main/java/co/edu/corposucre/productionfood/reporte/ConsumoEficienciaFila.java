package co.edu.corposucre.productionfood.reporte;

import java.math.BigDecimal;

/**
 * Fila cruda de un consumo registrado (HU-26) con la planificada y la producida de la línea de
 * producto a la que pertenece, que se necesitan para el rendimiento ajustado (R-02).
 */
public record ConsumoEficienciaFila(int idPlan, String materiaPrima, String unidad,
                                    BigDecimal teorica, BigDecimal real,
                                    BigDecimal planificada, BigDecimal producida) {
}
