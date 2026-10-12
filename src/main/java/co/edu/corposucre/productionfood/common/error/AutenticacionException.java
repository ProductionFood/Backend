package co.edu.corposucre.productionfood.common.error;

import org.springframework.http.HttpStatus;

/**
 * Fallos de autenticación. El status llega explícito desde el punto de origen
 * porque los dos casos se distinguen por el código, no por la clase:
 * 401 CREDENCIALES_INVALIDAS (correo inexistente o contraseña incorrecta) frente a
 * 403 USUARIO_INACTIVO (credenciales correctas, usuario desactivado).
 */
public class AutenticacionException extends ApiException {

    public AutenticacionException(String codigo, HttpStatus status, String mensaje) {
        super(codigo, status, mensaje);
    }
}
