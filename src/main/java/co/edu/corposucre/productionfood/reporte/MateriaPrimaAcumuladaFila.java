package co.edu.corposucre.productionfood.reporte;

import java.math.BigDecimal;

/**
 * Fila cruda del acumulado de una materia prima en el período (HU-26, R-06): sumas del teórico
 * y del real y cantidad de planes distintos.
 */
public record MateriaPrimaAcumuladaFila(String materiaPrima, String unidad, BigDecimal teorica,
                                        BigDecimal real, long planes) {
}
