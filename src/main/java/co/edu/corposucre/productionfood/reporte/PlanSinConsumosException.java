package co.edu.corposucre.productionfood.reporte;

import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ApiException;

/**
 * El plan existe pero no tiene ningún consumo registrado, así que no hay nada que medir en su
 * detalle de eficiencia (HU-26 §5 {@code PLAN_SIN_CONSUMOS}, 404, R-05).
 *
 * <p>Se distingue de {@code PLAN_INEXISTENTE}: el plan está en la base, pero un plan sin
 * consumos no forma parte del reporte.
 */
public class PlanSinConsumosException extends ApiException {

    public PlanSinConsumosException(Integer idPlan) {
        super("PLAN_SIN_CONSUMOS", HttpStatus.NOT_FOUND,
              String.format("El plan de producción con id %s no tiene consumos registrados.", idPlan));
    }
}
