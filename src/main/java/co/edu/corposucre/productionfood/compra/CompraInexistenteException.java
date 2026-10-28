package co.edu.corposucre.productionfood.compra;

import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ApiException;

/**
 * La compra sobre la que se trabaja no existe (HU-14 §5 {@code COMPRA_INEXISTENTE}, 404).
 *
 * <p>Existe como excepción propia del módulo porque el código del contrato de HU-14 es
 * específico: {@link co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException}
 * responde también 404 pero con el código transversal {@code RECURSO_NO_ENCONTRADO}, y CP-02
 * exige que el subrecurso de detalle distinga «no existe la compra» de un recurso cualquiera.
 *
 * <p>El mensaje se mantiene idéntico al del 404 transversal para que solo el {@code code}
 * del cuerpo cambie; el manejador global la serializa igual que cualquier otra
 * {@link ApiException}.
 */
public class CompraInexistenteException extends ApiException {

    public CompraInexistenteException(Integer idCompra) {
        super("COMPRA_INEXISTENTE", HttpStatus.NOT_FOUND,
              String.format("No se encontró la compra con id %s.", idCompra));
    }
}
