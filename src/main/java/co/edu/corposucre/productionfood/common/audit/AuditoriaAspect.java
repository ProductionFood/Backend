package co.edu.corposucre.productionfood.common.audit;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Component;

import co.edu.corposucre.productionfood.security.SecurityUtils;

/**
 * Único punto del sistema donde se decide QUIÉN se audita y CUÁNDO se audita.
 *
 * <p>La decisión no está en cada anotación sino aquí, y por eso las consultas (GET) no se
 * anotan: una bitácora de cambios con las lecturas intercaladas genera volumen que nadie
 * va a revisar y, peor, mezcla "ocurrió" con "se miró". Anotar un GET no añade trazabilidad
 * de cambios, solo filas. Quien quiera trazar una lectura ya tiene los logs de acceso.
 *
 * <p>Se audita DESPUÉS del retorno y nunca antes (regla R-02). Con {@code @Before}
 * una operación que después falla queda registrada como si se hubiera hecho, y en una
 * bitácora indistinguishable entre "se intentó y se rechazó" y "ocurrió", el registro
 * mentiroso es peor que el registro ausente.
 *
 * <p>Ventana conocida: al auditar en el RETORNO con {@code REQUIRES_NEW}, un rollback de la
 * transacción de negocio al commit (una clave foránea que solo aparece al cerrar) deja en la
 * bitácora una fila de un cambio que NUNCA ocurrió. Es el precio consciente de REQUIRES_NEW,
 * que es lo que hace real la garantía de que un fallo de auditoría no tumba la operación; se
 * mitigaría contrastando con el kardex (transaccional) o auditando después del commit.
 */
@Aspect
@Component
public class AuditoriaAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaAspect.class);

    private final BitacoraService bitacoraService;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    public AuditoriaAspect(BitacoraService bitacoraService) {
        this.bitacoraService = bitacoraService;
    }

    /**
     * Registra la acción de un método anotado con {@link Auditable} que termina con éxito.
     *
     * <p>Ante una expresión de {@code detalle} mal escrita se registra la acción SIN detalle
     * en lugar de no registrar nada: la acción, la tabla y el autor ya son el hecho auditado,
     * y el detalle es solo un resumen legible. Una expresión rota es un error de escritura
     * en el código, no un dato de negocio, así que perder el resumen es un coste aceptable
     * mientras que perder la entrada entera dejaría el cambio sin rastro justo cuando la
     * regla se rompió.
     *
     * <p>El detalle se evalúa en un {@link SimpleEvaluationContext} de solo lectura y no con
     * un {@code StandardEvaluationContext} con Beans resueltos: la expresión la escribe código
     * propio, así que no hay entrada externa que sanear, pero tampoco hay ninguna razón para
     * darle a esa expresión acceso al contenedor de Spring. El contexto de solo lectura
     * permite interpolar los datos del método y nada más, de modo que un descuido al escribir
     * la anotación no puede invocar servicios ni tocar el estado de la aplicación.
     *
     * <p>El {@code try/catch} alrededor de {@code registrar()} NO es redundante con el del
     * servicio: con {@code REQUIRES_NEW}, si esa transacción llega a marcarse
     * {@code rollback-only}, Spring relanza la excepción al cerrar el proxy del
     * {@code @Transactional}, es decir POR FUERA del try interno de {@code registrar()}. Este
     * catch es la red que cierra ese hueco y completa la garantía de R-03.
     *
     * <p>Esa red no es indiscriminada: {@link IllegalArgumentException} se relanza a propósito,
     * porque la validación de {@code accion} y {@code tabla} solo puede dispararse por una
     * {@link Auditable} mal escrita. Un error de ese tipo debe verse donde se introdujo, así que
     * capturarlo y loguearlo convertiría un fallo de compilación diferido en un hueco silencioso.
     *
     * <p>El {@code JoinPoint} va PRIMERO a propósito: con proxies de Spring, si el advice
     * declara la anotación enlazada antes que el {@code JoinPoint}, el aspecto se registra
     * pero nunca intercepta, sin error ni aviso. El orden de los parámetros forma parte
     * del contrato del punto de corte, no una cuestión de estilo.
     *
     * @param joinPoint  para obtener los argumentos y sus nombres reales
     * @param auditable anotación leída del método, con la acción y la tabla ya escritas
     * @param resultado valor retornado por el método, expuesto a SpEL como {@code #resultado}
     */
    @AfterReturning(pointcut = "@annotation(auditable)", returning = "resultado")
    public void auditar(JoinPoint joinPoint, Auditable auditable, Object resultado) {
        var detalle = auditable.detalle().isBlank()
                ? null
                : resolverDetalle(auditable.detalle(), joinPoint, resultado);

        try {
            bitacoraService.registrar(
                    SecurityUtils.idUsuarioActual(), auditable.accion(), auditable.tabla(), detalle);
        } catch (IllegalArgumentException e) {
            // Única excepción que SÍ sale del advise: la validación de accion y tabla, que son
            // literales escritos a mano en la @Auditable. No es un dato de negocio ni un fallo
            // de infraestructura, sino un error de programación que existe desde que se
            // escribió la anotación; tragárselo aquí lo convertiría en un hueco silencioso
            // dentro de una bitácora donde todo lo demás sí deja rastro.
            throw e;
        } catch (RuntimeException e) {
            // El servicio ya absorbe sus propios fallos de infraestructura; lo que se captura
            // aquí es lo que escapa a ese try, sobre todo la excepción de rollback que Spring
            // lanza al cerrar el proxy de REQUIRES_NEW. Sin esta red, un fallo de auditoría
            // podría tumbar una operación de negocio que ya se ejecutó con éxito (R-03).
            log.error("No se pudo auditar la acción {} sobre {}: la operación de negocio "
                    + "continúa sin su registro en bitácora", auditable.accion(), auditable.tabla(), e);
        }
    }

    /**
     * Evalúa el detalle contra los argumentos del método y su valor de retorno.
     *
     * <p>Cada argumento se expone dos veces: por su nombre real, que es lo legible de escribir
     * en la anotación, y por su alias posicional {@code p0, p1, ...} por si el nombre no llega
     * compilado ({@code -parameters}) o no es válido como variable de SpEL.
     *
     * <p>Combinar varios datos exige el operador {@code +}: {@code "#codigo #estado"} es una
     * expresión inválida, porque SpEL solo concatena con operadores explícitos.
     */
    private String resolverDetalle(String expresion, JoinPoint joinPoint, Object resultado) {
        var contexto = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        var nombres = ((MethodSignature) joinPoint.getSignature()).getParameterNames();
        var argumentos = joinPoint.getArgs();

        for (int i = 0; i < argumentos.length; i++) {
            if (nombres != null && i < nombres.length) {
                contexto.setVariable(nombres[i], argumentos[i]);
            }
            contexto.setVariable("p" + i, argumentos[i]);
        }
        contexto.setVariable("resultado", resultado);

        try {
            return parser.parseExpression(expresion).getValue(contexto, String.class);
        } catch (RuntimeException e) {
            // Expresión mal escrita: el error se ve en el log y la acción se registra sin
            // detalle, en lugar de propagar y tumbar la operación ya ejecutada.
            log.error("No se pudo evaluar el detalle de la auditoría con la expresión '{}': "
                    + "se registrará la acción sin detalle", expresion, e);
            return null;
        }
    }
}