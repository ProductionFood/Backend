package co.edu.corposucre.productionfood.common.error;

import org.springframework.http.HttpStatus;

/**
 * Regla de negocio que los datos no satisfacen, respondida con {@code 422} (HU-18 §8).
 *
 * <p>La distinción con {@link ConflictoNegocioException} —que responde {@code 409}— es la de
 * {@code 04-CONTRATO-API.md} §3: el {@code 409} cubre un conflicto con algo que ya existe (un
 * duplicado, un estado incompatible) y el {@code 422}, un dato sintácticamente correcto —un
 * número positivo en un campo de cantidad— que viola una regla dependiente del estado
 * acumulado del sistema, como no devolver más de lo pedido (R-03).
 *
 * <p>No necesita handler propio: {@code GlobalExceptionHandler} la resuelve por ser subclase de
 * {@link ApiException}.
 */
public class ReglaDeNegocioException extends ApiException {

    public ReglaDeNegocioException(String codigo, String mensaje) {
        super(codigo, HttpStatus.UNPROCESSABLE_ENTITY, mensaje);
    }
}
