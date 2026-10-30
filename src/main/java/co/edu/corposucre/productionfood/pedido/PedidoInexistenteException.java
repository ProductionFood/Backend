package co.edu.corposucre.productionfood.pedido;

import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ApiException;

/**
 * El pedido sobre el que se trabaja en su detalle no existe (HU-17 §5
 * {@code PEDIDO_INEXISTENTE}, 404).
 *
 * <p>Existe como excepción propia del módulo porque el código del contrato de HU-17 es
 * específico: {@link co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException}
 * responde también 404 pero con el código transversal {@code RECURSO_NO_ENCONTRADO}, y el
 * subrecurso de detalle debe distinguir «no existe el pedido» de un recurso cualquiera.
 *
 * <p>El mensaje se mantiene idéntico al del 404 transversal para que solo el {@code code}
 * del cuerpo cambie; el manejador global la serializa igual que cualquier otra
 * {@link ApiException}.
 */
public class PedidoInexistenteException extends ApiException {

    public PedidoInexistenteException(Integer idPedido) {
        super("PEDIDO_INEXISTENTE", HttpStatus.NOT_FOUND,
              String.format("No se encontró el pedido con id %s.", idPedido));
    }
}
