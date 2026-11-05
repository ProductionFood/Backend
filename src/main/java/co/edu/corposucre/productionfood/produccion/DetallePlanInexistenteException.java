package co.edu.corposucre.productionfood.produccion;

import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ApiException;

/**
 * La línea de detalle del plan de producción no existe (HU-22 §5
 * {@code DETALLE_PLAN_INEXISTENTE}, 404).
 *
 * <p>Existe como excepción propia del módulo porque el código del contrato de HU-22 es
 * específico: {@link co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException}
 * responde también 404 pero con el código transversal {@code RECURSO_NO_ENCONTRADO}, y la
 * consulta de consumo teórico debe distinguir «no existe la línea del plan» de un recurso
 * cualquiera.
 *
 * <p>El mensaje se mantiene en el mismo tono que el del 404 transversal para que solo el
 * {@code code} del cuerpo cambie; el manejador global la serializa igual que cualquier otra
 * {@link ApiException}.
 */
public class DetallePlanInexistenteException extends ApiException {

    public DetallePlanInexistenteException(Integer idDetallePlan) {
        super("DETALLE_PLAN_INEXISTENTE", HttpStatus.NOT_FOUND,
              String.format("No se encontró la línea de detalle del plan de producción con id %s.",
                      idDetallePlan));
    }
}
