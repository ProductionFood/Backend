package co.edu.corposucre.productionfood.unidadmedida;

import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.unidadmedida.dto.ActualizarUnidadRequest;
import co.edu.corposucre.productionfood.unidadmedida.dto.CrearUnidadRequest;
import co.edu.corposucre.productionfood.unidadmedida.dto.UnidadEnUsoResponse;
import co.edu.corposucre.productionfood.unidadmedida.dto.UnidadMedidaResponse;

/**
 * Servicio del catálogo de unidades de medida (HU-07).
 *
 * <p>El catálogo es cerrado: la tabla {@code unidades_medida} no tiene columna {@code estado}
 * y ninguna de las tablas que la referencian admite el borrado en cascada, así que una unidad
 * no se desactiva ni se elimina (regla R-03). Solo se crean y se editan, y la edición se
 * limita a nombre y abreviatura.
 *
 * <p>El listado es completo y sin paginación (regla R-01): el catálogo es pequeño y el cliente
 * lo usa para poblar selectores de unidades, donde una página incompleta obligaría a pedir
 * más de una vez lo mismo.
 *
 * <p>La abreviatura se guarda tal como se escribe, sin alterar sus mayúsculas, pero su
 * unicidad no distingue mayúsculas de minúsculas porque la columna usa la collation
 * {@code utf8mb4_0900_ai_ci} (regla R-02): {@code kg} y {@code KG} son la misma abreviatura y
 * no pueden coexistir en el catálogo.
 *
 * <p>Cambiar la abreviatura de una unidad que ya está en uso exige una confirmación explícita
 * (regla R-04): las cantidades registradas se interpretan con la abreviatura vigente, así que
 * cambiarla a ciegas alteraría el significado de datos ya capturados.
 */
@Service
public class UnidadMedidaService {

    private final UnidadMedidaRepository unidadMedidaRepository;

    public UnidadMedidaService(UnidadMedidaRepository unidadMedidaRepository) {
        this.unidadMedidaRepository = unidadMedidaRepository;
    }

    @Transactional
    // En las expresiones de detalle de este servicio los accesores de record se leen como
    // propiedades (#resultado.nombre) y no como llamadas (#resultado.nombre()): el contexto
    // SpEL de solo lectura del aspecto no resuelve la llamada y el detalle se perdería en
    // silencio, que es justo lo que la bitácora no debe hacer.
    @Auditable(accion = "CREAR", tabla = "unidades_medida",
            detalle = "'Unidad creada: ' + #resultado.nombre")
    public UnidadMedidaResponse crear(CrearUnidadRequest req) {
        var nombre = normalizar(req.nombre());
        // La abreviatura se normaliza sin tocar sus mayúsculas: se guarda tal como se escribe.
        var abreviatura = normalizar(req.abreviatura());

        if (unidadMedidaRepository.existsByAbreviatura(abreviatura)) {
            throw new ConflictoNegocioException(
                    "ABREVIATURA_DUPLICADA",
                    mensajeAbreviaturaDuplicada(abreviatura));
        }

        var unidad = new UnidadMedida();
        unidad.setNombre(nombre);
        unidad.setAbreviatura(abreviatura);

        try {
            unidad = unidadMedidaRepository.saveAndFlush(unidad);
        } catch (DuplicateKeyException e) {
            // Dos altas simultáneas pueden pasar la comprobación anterior: la clave única
            // de la base es la que decide, y su rechazo se traduce al mismo 409 de negocio
            // en lugar de dejar que la excepción de infraestructura llegue al cliente.
            throw new ConflictoNegocioException(
                    "ABREVIATURA_DUPLICADA",
                    mensajeAbreviaturaDuplicada(abreviatura));
        }

        return aResponse(unidad);
    }

    @Transactional(readOnly = true)
    public List<UnidadMedidaResponse> listar() {
        // Listado completo y sin paginación (regla R-01): la consulta nativa ya lo ordena
        // por nombre, de modo que aquí no hace falta ninguna capa de orden ni de filtros.
        return unidadMedidaRepository.listarConUsos().stream()
                .map(UnidadMedidaService::aResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UnidadMedidaResponse obtener(Integer id) {
        var fila = unidadMedidaRepository.buscarConUsos(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la unidad de medida", id));
        return aResponse(fila);
    }

    @Transactional
    @Auditable(accion = "EDITAR", tabla = "unidades_medida",
            detalle = "'Unidad editada: ' + #resultado.nombre")
    public UnidadMedidaResponse actualizar(Integer id, ActualizarUnidadRequest req,
                                            boolean confirmar) {
        var unidad = unidadMedidaRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la unidad de medida", id));

        var nombre = normalizar(req.nombre());
        var abreviatura = normalizar(req.abreviatura());
        var abreviaturaActual = normalizar(unidad.getAbreviatura());

        // Solo se revalida la abreviatura cuando cambia. Editar el nombre de una unidad que
        // ya está en uso es válido (CP-10), y una abreviatura que no cambia no puede
        // reportarse como duplicada contra sí misma (CP-11).
        if (!abreviatura.equals(abreviaturaActual)) {
            if (unidadMedidaRepository.existsByAbreviaturaAndIdUnidadNot(abreviatura, id)) {
                throw new ConflictoNegocioException(
                        "ABREVIATURA_DUPLICADA",
                        mensajeAbreviaturaDuplicada(abreviatura));
            }
            validarAbreviaturaEnUso(unidad, id, confirmar);
        }

        unidad.setNombre(nombre);
        unidad.setAbreviatura(abreviatura);

        try {
            unidadMedidaRepository.saveAndFlush(unidad);
        } catch (DuplicateKeyException e) {
            throw new ConflictoNegocioException(
                    "ABREVIATURA_DUPLICADA",
                    mensajeAbreviaturaDuplicada(abreviatura));
        }

        // La respuesta se vuelve a leer de la base para que refleje el estado real tras
        // el guardado y no la entidad en memoria.
        var fila = unidadMedidaRepository.buscarConUsos(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la unidad de medida", id));
        return aResponse(fila);
    }

    /**
     * Exige confirmación explícita cuando la abreviatura que se cambia es la de una unidad
     * que ya aparece en alguna de las tablas que la referencian.
     *
     * <p>Con la confirmación dada, el cambio se aplica: es el requisito de la tarea-backend
     * para que quien edita asuma el efecto sobre las cantidades ya registradas.
     *
     * @param unidad    unidad que se está editando
     * @param id        identificador de la unidad
     * @param confirmar {@code true} si el cliente confirma expresamente el cambio
     */
    private void validarAbreviaturaEnUso(UnidadMedida unidad, Integer id, boolean confirmar) {
        var fila = unidadMedidaRepository.buscarConUsos(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la unidad de medida", id));
        var usos = UnidadEnUsoResponse.de(
                fila.getEnMateriasPrimas(),
                fila.getEnProductos(),
                fila.getEnRecetas());
        if (usos.total() > 0 && !confirmar) {
            throw new ConflictoNegocioException(
                    "UNIDAD_EN_USO",
                    mensajeUnidadEnUso(unidad, usos));
        }
    }

    /**
     * Mensaje del conflicto por abreviatura repetida.
     *
     * <p>Explica que la comparación no distingue mayúsculas de minúsculas porque la
     * columna {@code abreviatura} usa la collation {@code utf8mb4_0900_ai_ci}, que las da
     * por iguales (regla R-02). Sin esa explicación el cliente ve un rechazo por {@code kg}
     * cuando cree haber escrito {@code KG}, que es justo lo que impide entender el error.
     *
     * @param abreviatura abreviatura que se ha rechazado
     * @return el mensaje del conflicto
     */
    private static String mensajeAbreviaturaDuplicada(String abreviatura) {
        return "Ya existe una unidad de medida con la abreviatura '" + abreviatura + "'. "
                + "Las abreviaturas no se repiten aunque cambien las mayúsculas o minúsculas: "
                + "la comparación no distingue mayúsculas de minúsculas porque la columna "
                + "abreviatura usa la collation utf8mb4_0900_ai_ci.";
    }

    /**
     * Mensaje del conflicto por cambio de abreviatura en una unidad en uso.
     *
     * @param unidad unidad que se está editando
     * @param usos   conteo de referencias por tabla
     * @return el mensaje del conflicto
     */
    private static String mensajeUnidadEnUso(UnidadMedida unidad, UnidadEnUsoResponse usos) {
        return "La unidad '" + unidad.getNombre() + " (" + unidad.getAbreviatura() + ")' se usa en "
                + usos.enMateriasPrimas() + " materias primas, "
                + usos.enProductos() + " productos y "
                + usos.enRecetas() + " recetas. Cambiar su abreviatura modificaría cómo se "
                + "interpretan esas cantidades. Repita la petición con ?confirmar=true para "
                + "confirmar el cambio.";
    }

    /**
     * Normaliza el texto de entrada quitando los espacios sobrantes de los extremos.
     *
     * @param texto texto a normalizar
     * @return el texto sin espacios sobrantes
     */
    private static String normalizar(String texto) {
        return texto.trim();
    }

    /**
     * Mapea la fila de la consulta nativa a la respuesta de la unidad de medida.
     *
     * <p>El total de usos no se calcula aquí: lo aporta {@link UnidadEnUsoResponse#de(long,
     * long, long)}, que suma los tres conteos por tabla.
     *
     * @param fila fila con la unidad y sus conteos de uso
     * @return la respuesta de la unidad de medida
     */
    private static UnidadMedidaResponse aResponse(UnidadMedidaConUsos fila) {
        return new UnidadMedidaResponse(
                fila.getIdUnidad(),
                fila.getNombre(),
                fila.getAbreviatura(),
                UnidadEnUsoResponse.de(
                        fila.getEnMateriasPrimas(),
                        fila.getEnProductos(),
                        fila.getEnRecetas()));
    }

    /**
     * Mapea una unidad recién guardada a la respuesta de la unidad de medida.
     *
     * <p>Una unidad recién creada no puede tener referencias: el conteo de uso es cero.
     *
     * @param unidad unidad guardada
     * @return la respuesta de la unidad de medida
     */
    private static UnidadMedidaResponse aResponse(UnidadMedida unidad) {
        return new UnidadMedidaResponse(
                unidad.getIdUnidad(),
                unidad.getNombre(),
                unidad.getAbreviatura(),
                UnidadEnUsoResponse.de(0, 0, 0));
    }
}
