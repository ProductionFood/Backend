package co.edu.corposucre.productionfood.produccion;

import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ApiException;

/**
 * El plan de producción sobre el que se trabaja en su detalle no existe (HU-21 §5
 * {@code PLAN_INEXISTENTE}, 404).
 *
 * <p>Existe como excepción propia del módulo porque el código del contrato de HU-21 es
 * específico: {@link co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException}
 * responde también 404 pero con el código transversal {@code RECURSO_NO_ENCONTRADO}, y el
 * subrecurso de detalle debe distinguir «no existe el plan de producción» de un recurso
 * cualquiera.
 *
 * <p>El mensaje se mantiene idéntico al del 404 transversal para que solo el {@code code}
 * del cuerpo cambie; el manejador global la serializa igual que cualquier otra
 * {@link ApiException}.
 */
public class PlanInexistenteException extends ApiException {

    public PlanInexistenteException(Integer idPlan) {
        super("PLAN_INEXISTENTE", HttpStatus.NOT_FOUND,
              String.format("No se encontró el plan de producción con id %s.", idPlan));
    }
}
