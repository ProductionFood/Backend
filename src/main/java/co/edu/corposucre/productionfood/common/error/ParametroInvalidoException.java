package co.edu.corposucre.productionfood.common.error;

import org.springframework.http.HttpStatus;

public class ParametroInvalidoException extends ApiException {

    public ParametroInvalidoException(String codigo, String mensaje) {
        super(codigo, HttpStatus.BAD_REQUEST, mensaje);
    }
}
