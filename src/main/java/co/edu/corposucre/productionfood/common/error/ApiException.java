package co.edu.corposucre.productionfood.common.error;

import org.springframework.http.HttpStatus;

public abstract class ApiException extends RuntimeException {

    private final String codigo;
    private final HttpStatus status;

    protected ApiException(String codigo, HttpStatus status, String mensaje) {
        super(mensaje);
        this.codigo = codigo;
        this.status = status;
    }

    public String getCodigo() {
        return codigo;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
