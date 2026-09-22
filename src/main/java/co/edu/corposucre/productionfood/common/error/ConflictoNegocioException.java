package co.edu.corposucre.productionfood.common.error;

import org.springframework.http.HttpStatus;

public class ConflictoNegocioException extends ApiException {

    public ConflictoNegocioException(String codigo, String mensaje) {
        super(codigo, HttpStatus.CONFLICT, mensaje);
    }
}
