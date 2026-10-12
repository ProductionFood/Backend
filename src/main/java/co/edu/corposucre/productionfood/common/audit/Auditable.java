package co.edu.corposucre.productionfood.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un método de servicio como sujeto de registro en la bitácora de auditoría.
 *
 * <p>Solo debe aplicarse sobre métodos de SERVICIO, nunca sobre controllers: el registro
 * describe una regla de negocio ("se crea un usuario", "se desactiva una compra"), y
 * anotar la capa HTTP duplicaría entradas y ataría la bitácora a la forma de la API en
 * lugar de al comportamiento real del sistema.
 *
 * <p>La entrada se escribe DESPUÉS de que el método termine con éxito, nunca antes: una
 * bitácora que registra intentos fallidos no distingue "se intentó y se rechazó" de
 * "ocurrió", y para una auditoría eso es peor que no tener registro.
 *
 * <p>{@link #detalle()} es una expresión SpEL opcional que se evalúa contra los argumentos
 * del método y el valor que este retorna; por ejemplo {@code "Usuario creado: #req.correo()"}.
 * Si se omite, la acción queda registrada sin resumen.
 *
 * <p>NUNCA deben incluirse contraseñas, hashes ni tokens en {@code detalle} (regla R-05):
 * la bitácora se conserva y se consulta con frecuencia, y es el lugar donde un dato
 * sensible acaba siendo legible por quien no debería verlo.
 *
 * <p>La expresión se escribe a mano de forma deliberada y no se genera por reflexión sobre
 * los argumentos: un resumen automático terminaría volcando en la bitácora campos que nadie
 * revisó, y el secreto quedaría registrado sin que nadie lo notase.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {

    String accion();

    String tabla();

    String detalle() default "";
}
