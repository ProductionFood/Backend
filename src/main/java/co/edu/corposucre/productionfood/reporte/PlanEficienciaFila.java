package co.edu.corposucre.productionfood.reporte;

import java.time.LocalDate;

/** Fila cruda de un plan para el reporte de eficiencia (HU-26), sin ningún cálculo. */
public record PlanEficienciaFila(int idPlan, LocalDate fechaProduccion, String estado,
                                 String responsable) {
}
