package co.edu.corposucre.productionfood.common.error;

import org.springframework.http.HttpStatus;

public class RecursoNoEncontradoException extends ApiException {

    public RecursoNoEncontradoException(String recurso, Object id) {
        super("RECURSO_NO_ENCONTRADO", HttpStatus.NOT_FOUND,
              String.format("No se encontró %s con id %s.", recurso, id));
    }
}
