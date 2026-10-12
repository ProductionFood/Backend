package co.edu.corposucre.productionfood.common.audit;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.dto.BitacoraResponse;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;

/**
 * Punto de entrada único para dejar constancia de una acción en la bitácora de auditoría.
 *
 * <p>Toda la política de la bitácora vive aquí y no en los servicios que la usan: recortar el
 * detalle, normalizar lo vacío y tragarse los fallos son decisiones transversales, y
 * duplicarlas en cada anotación haría que dos servicios se auditen con reglas distintas.
 *
 * <p>Por diseño la auditoría NUNCA propaga una excepción de infraestructura (regla R-03): si el
 * registro falla, la operación de negocio que ya se ejecutó debe seguir adelante y responder al
 * usuario. El precio de esa garantía es que un fallo deja un hueco en la bitácora, y por eso
 * todo error se escribe en el log: sin registro, el hueco sería invisible hasta que alguien
 * investigue una ausencia en lugar de un error visible.
 */
@Service
public class BitacoraService {

    private static final Logger log = LoggerFactory.getLogger(BitacoraService.class);

    /**
     * Límite físico de {@code bitacora.accion} y {@code bitacora.tabla_afectada} (50) y de
     * {@code bitacora.detalle} (255). Se fijan aquí y no leyéndolos del esquema porque son
     * parte del contrato de la auditoría: si el esquema cambia, el recorte y la validación
     * deben cambiar con él, no romperse en producción.
     */
    private static final int MAX_CODIGO = 50;
    private static final int MAX_DETALLE = 255;

    /**
     * Lista blanca de ordenamiento del listado: traduce el campo que pide el cliente al nombre de
     * columna SQL. {@code PageRequest.of} rechaza cualquier campo que no esté aquí, y la columna
     * resuelta es la única que llega al repositorio, de modo que el orden nunca es SQL arbitrario.
     */
    private static final Map<String, String> ORDEN = Map.of(
            "fecha", "fecha", "id", "id_bitacora", "accion", "accion", "tabla", "tabla_afectada");

    private static final String ORDEN_POR_DEFECTO = "fecha,desc";

    private final BitacoraRepository bitacoraRepository;

    public BitacoraService(BitacoraRepository bitacoraRepository) {
        this.bitacoraRepository = bitacoraRepository;
    }

    /**
     * Registra una acción de auditoría sin poder tumbar la operación que la invoca.
     *
     * <p>{@code REQUIRES_NEW} es lo que hace que la garantía de R-03 sea real y no solo
     * aparente: sin ella el INSERT comparte transacción con la de negocio, de modo que un
     * fallo al auditar dejaría esa transacción marcada como rollback-only y el commit
     * posterior reventaría con una excepción que el propio servicio nunca lanzó.
     *
     * <p>Un {@code detalle} nulo, vacío o solo con espacios se persiste como {@code null} y no
     * como cadena vacía: una fila con {@code ''} sería indistinguible de un resumen ausente.
     *
     * @param idUsuario autor de la acción, o {@code null} si la ejecutó el sistema
     * @param accion    código de la acción, de hasta 50 caracteres
     * @param tabla     tabla afectada, de hasta 50 caracteres
     * @param detalle   resumen legible, recortado a 255 caracteres, o {@code null} si no aplica
     * @throws IllegalArgumentException si {@code accion} o {@code tabla} superan los
     *                                  50 caracteres o vienen vacíos
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registrar(Integer idUsuario, String accion, String tabla, String detalle) {
        validarCodigo(accion, "accion");
        validarCodigo(tabla, "tabla");

        var detalleAjustado = normalizarDetalle(detalle);
        try {
            bitacoraRepository.insertar(idUsuario, accion, tabla, detalleAjustado);
        } catch (RuntimeException e) {
            // Se captura RuntimeException y no solo DataAccessException a propósito: lo que
            // llega hasta aquí puede ser cualquier fallo en tiempo de ejecución (conexión
            // perdida, consulta rechazada por el modo estricto, error de traducción del
            // driver). Filtrar por el tipo más conocido dejaría fuera justo los casos que
            // R-03 debe absorber, y el repositorio es la última línea donde aún se puede
            // decidir no propagar.
            log.error("No se pudo registrar la acción {} sobre {}: la operación de negocio "
                    + "continúa sin su registro en bitácora", accion, tabla, e);
        }
    }

    /**
     * Lista la bitácora de forma paginada y filtrada.
     *
     * <p>El rango de fechas se valida antes de tocar la base: un rango invertido no tiene
     * resultados posibles y responder 400 explica el error mejor que una página vacía.
     *
     * <p>El total sale de {@code contar} y no del tamaño de la página: el listado puede traer 20
     * filas y haber miles.
     *
     * @param filtros filtros opcionales; un campo nulo no restringe
     * @param page    página solicitada, desde 0
     * @param size    tamaño de página, entre 1 y 100
     * @param sort    orden "campo,direccion"; por defecto "fecha,desc"
     * @throws ParametroInvalidoException si el rango de fechas está invertido, o la paginación o
     *                                    el campo de orden son inválidos
     */
    @Transactional(readOnly = true)
    public PageResponse<BitacoraResponse> listar(BitacoraFiltros filtros, int page, int size,
                                                 String sort) {
        if (filtros.fechaInicio() != null && filtros.fechaFin() != null
                && filtros.fechaInicio().isAfter(filtros.fechaFin())) {
            throw new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                    "La fecha de inicio no puede ser posterior a la fecha de fin.");
        }

        var orden = PageRequest.of(page, size, sort, ORDEN, ORDEN_POR_DEFECTO);
        // El offset se calcula en long: page no tiene cota superior, así que page * size
        // desbordaría int a un valor negativo y el OFFSET del SQL fallaría.
        long offset = (long) orden.page() * orden.size();
        var filas = bitacoraRepository.buscar(filtros, orden.propiedad(), orden.ascendente(),
                orden.size(), offset);
        var total = bitacoraRepository.contar(filtros);

        return PageResponse.of(
                filas.stream().map(BitacoraService::aResponse).toList(),
                orden.page(), orden.size(), total);
    }

    /**
     * Devuelve el catálogo de acciones ya registradas, para poblar el filtro del cliente.
     */
    @Transactional(readOnly = true)
    public List<String> listarAcciones() {
        return bitacoraRepository.listarAcciones();
    }

    /**
     * Convierte la fila a su forma de respuesta.
     *
     * <p>Con {@code idUsuario} nulo el autor es nulo (acción del sistema). El JOIN garantiza que un
     * id no nulo trae nombre, así que si {@code usuarioNombre} viniera nulo se expone tal cual: no
     * se inventa un nombre que la consulta no aportó.
     */
    private static BitacoraResponse aResponse(Bitacora fila) {
        var usuario = fila.idUsuario() == null
                ? null
                : new BitacoraResponse.Usuario(fila.idUsuario(), fila.usuarioNombre());
        return new BitacoraResponse(
                fila.idBitacora(),
                usuario,
                fila.accion(),
                fila.tablaAfectada(),
                fila.detalle(),
                fila.fecha());
    }

    /**
     * Exige que un código de auditoría sea utilizable como tal, no solo que exista.
     *
     * <p>Superado el límite se lanza una excepción en lugar de recortar porque {@code accion}
     * y {@code tabla} son literales escritos por una persona en el código ({@link Auditable}),
     * no datos de negocio: truncar convertiría un nombre de acción mal escrito en un registro
     * con otro nombre, que es justo lo que una bitácora no debe producir. El error existe
     * desde que se escribió la anotación y debe verse ahí.
     *
     * <p>Vacío también se rechaza: un código en blanco no identifica ninguna regla de negocio,
     * y una fila con el código vacío es indistinguible de un fallo de auditoría.
     */
    private void validarCodigo(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(
                    "El campo '" + campo + "' es obligatorio y no puede ir vacío.");
        }
        if (valor.length() > MAX_CODIGO) {
            throw new IllegalArgumentException(
                    "El campo '" + campo + "' supera los " + MAX_CODIGO
                            + " caracteres de bitacora; acórtalo en el código.");
        }
    }

    /**
     * Recorta el detalle al ancho de la columna antes del INSERT.
     *
     * <p>El recorte es obligatorio y no una comodidad: con el {@code sql_mode} estricto de
     * MySQL 8 un texto más largo que la columna hace FALLAR el INSERT en lugar de truncar, y
     * como ese fallo se traga en silencio (R-03) la acción quedaría sin auditar justo en los
     * casos más ricos en detalle. El corte es por el final del texto y sin elipsis: un carácter
     * añadido falsearía el resumen, y el recorte ya es detectable por el propio límite.
     */
    private String normalizarDetalle(String detalle) {
        if (detalle == null || detalle.isBlank()) {
            return null;
        }
        return detalle.length() > MAX_DETALLE ? detalle.substring(0, MAX_DETALLE) : detalle;
    }
}