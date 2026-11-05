package co.edu.corposucre.productionfood.produccion;

import java.time.LocalDate;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.produccion.dto.CrearPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.PlanResponse;
import co.edu.corposucre.productionfood.security.SecurityUtils;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

/**
 * Registro y consulta de planes de producción (HU-20): alta, detalle y listado paginado.
 *
 * <p>La edición de cabecera y las transiciones de estado viven en un componente posterior
 * (PR3); este servicio solo expone las operaciones de lectura y el alta.
 *
 * <p>Los códigos de error son los del contrato: un dato de entrada fuera de rango responde 400
 * ({@link ParametroInvalidoException}) y un conflicto de negocio, 409
 * ({@link ConflictoNegocioException}). La fecha de producción nunca se persiste sin haber
 * validado que no precede a la de planificación (R-03).
 *
 * <p>Un plan nace siempre {@code PLANIFICADO} (CA-02/CP-06): el DTO no admite estado y el
 * servicio no lo lee de la petición. El responsable se resuelve a partir del usuario
 * autenticado y solo un ADMIN puede apuntar el plan a otro usuario (R-04).
 */
@Service
public class PlanProduccionService {

    private final PlanProduccionRepository planProduccionRepository;

    private final UsuarioRepository usuarioRepository;

    public PlanProduccionService(PlanProduccionRepository planProduccionRepository,
                                 UsuarioRepository usuarioRepository) {
        this.planProduccionRepository = planProduccionRepository;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * Lista planes de producción de forma paginada y filtrada, con su responsable y sus líneas
     * contadas.
     *
     * <p>Los cuatro filtros son opcionales y viajan tal cual al repositorio; un valor nulo no
     * restringe. El orden se resuelve con la lista blanca
     * {@link PlanProduccionRepository#ORDEN} y cae al orden por defecto
     * {@link PlanProduccionRepository#ORDEN_POR_DEFECTO} cuando no se informa. El total sale de
     * {@link PlanProduccionRepository#contar} y no del tamaño de la página.
     *
     * <p>La validación de paginación y de la propiedad de orden la hace {@link PageRequest#of}:
     * este método no la reimplementa.
     *
     * @param estado      estado a filtrar, o {@code null} sin filtrar
     * @param idUsuario   responsable a filtrar, o {@code null} sin filtrar
     * @param fechaInicio primer día incluido del rango de producción, o {@code null} sin límite
     *                    inferior
     * @param fechaFin    último día incluido del rango de producción, o {@code null} sin límite
     *                    superior
     * @param page        página solicitada, desde 0
     * @param size        tamaño de página, entre 1 y 100
     * @param sort        orden "campo,direccion", con campos de la lista blanca
     *                    {@code fechaProduccion}, {@code fechaPlanificacion}, {@code estado} e
     *                    {@code id}; por defecto "fechaProduccion,desc"
     * @return página de planes con su responsable y sus líneas contadas
     * @throws ParametroInvalidoException si la paginación o el campo de orden son inválidos
     */
    @Transactional(readOnly = true)
    public PageResponse<PlanResponse> listar(EstadoPlan estado, Integer idUsuario,
                                             LocalDate fechaInicio, LocalDate fechaFin,
                                             int page, int size, String sort) {
        var orden = PageRequest.of(page, size, sort, PlanProduccionRepository.ORDEN,
                PlanProduccionRepository.ORDEN_POR_DEFECTO);

        var contenido = planProduccionRepository
                .buscar(estado, idUsuario, fechaInicio, fechaFin, orden)
                .stream()
                .map(PlanProduccionService::aResponse)
                .collect(Collectors.toList());
        var total = planProduccionRepository.contar(estado, idUsuario, fechaInicio, fechaFin);

        return PageResponse.of(contenido, orden.page(), orden.size(), total);
    }

    /**
     * Devuelve un plan de producción por su identificador, con su responsable y sus líneas
     * contadas.
     *
     * @param id identificador del plan
     * @return el plan en la forma del contrato
     * @throws RecursoNoEncontradoException si el plan no existe
     */
    @Transactional(readOnly = true)
    public PlanResponse obtener(Integer id) {
        return planProduccionRepository.buscarPorId(id)
                .map(PlanProduccionService::aResponse)
                .orElseThrow(() -> new RecursoNoEncontradoException("plan de producción", id));
    }

    /**
     * Registra un plan nuevo, siempre en estado {@code PLANIFICADO} (CA-02/CP-06).
     *
     * <p>Validaciones y resolución del responsable, en orden:
     *
     * <ol>
     *   <li>La fecha de producción no puede ser anterior a la fecha de planificación
     *       ({@code FECHAS_INVALIDAS}, 400, R-03). Ambas fechas pueden coincidir.</li>
     *   <li>El responsable sale del usuario autenticado si la petición no trae {@code idUsuario}
     *       (CP-10) o si el rol actual no es {@code ADMIN} (CP-11): un usuario no administrador
     *       no puede apuntar el plan a otro. Un ADMIN que sí informa {@code idUsuario} lo
     *       conserva, pero solo si ese usuario existe ({@code USUARIO_INEXISTENTE}, 409,
     *       R-04).</li>
     * </ol>
     *
     * <p>La respuesta se relee de la base tras el {@code INSERT} para que el nombre del
     * responsable y la cantidad de productos sean los reales y no una proyección del DTO.
     *
     * @param req fechas obligatorias, observaciones opcionales e {@code idUsuario} opcional
     * @return el plan releído de la base, en estado {@code PLANIFICADO}, con cero productos
     * @throws ParametroInvalidoException si la fecha de producción precede a la de planificación
     * @throws ConflictoNegocioException si el ADMIN apunta el plan a un usuario inexistente
     */
    @Transactional
    @Auditable(accion = "CREAR", tabla = "planes_produccion",
            detalle = "'Plan de producción creado: ' + #resultado.idPlan")
    public PlanResponse crear(CrearPlanRequest req) {
        if (req.fechaProduccion().isBefore(req.fechaPlanificacion())) {
            throw new ParametroInvalidoException("FECHAS_INVALIDAS",
                    "La fecha de producción no puede ser anterior a la fecha de planificación.");
        }

        var idUsuario = resolverResponsable(req.idUsuario());

        var plan = new PlanProduccion();
        plan.setFechaPlanificacion(req.fechaPlanificacion());
        plan.setFechaProduccion(req.fechaProduccion());
        plan.setObservaciones(req.observaciones());
        plan.setIdUsuario(idUsuario);
        plan.setEstado(EstadoPlan.PLANIFICADO);

        var idPlan = planProduccionRepository.insertar(plan);

        return planProduccionRepository.buscarPorId(idPlan)
                .map(PlanProduccionService::aResponse)
                .orElseThrow(() -> new RecursoNoEncontradoException("plan de producción",
                        idPlan));
    }

    /**
     * Resuelve el responsable del plan según R-04.
     *
     * <p>Solo un ADMIN puede designar a otro usuario; cualquier otro rol, o una petición sin
     * {@code idUsuario}, queda a nombre del usuario autenticado. Cuando el ADMIN sí designa a
     * otro, se comprueba que exista antes de escribir el {@code INSERT}.
     *
     * @param idUsuarioSolicitado responsable que envió el cliente, o {@code null}
     * @return identificador del usuario que quedará como responsable
     * @throws ConflictoNegocioException si el ADMIN designa un usuario que no existe
     */
    private Integer resolverResponsable(Integer idUsuarioSolicitado) {
        if (idUsuarioSolicitado != null && "ADMIN".equals(SecurityUtils.rolActual())) {
            if (!usuarioRepository.existsById(idUsuarioSolicitado)) {
                throw new ConflictoNegocioException("USUARIO_INEXISTENTE",
                        String.format("El usuario con id %s no existe.", idUsuarioSolicitado));
            }
            return idUsuarioSolicitado;
        }
        return SecurityUtils.idUsuarioActual();
    }

    /**
     * Convierte una fila de {@link PlanProduccionRepository} en la respuesta del contrato.
     *
     * <p>{@code nombreResponsable} y {@code cantidadProductos} llegan ya calculados de la base y
     * {@code editable} se deriva de la máquina de estados: solo un plan {@code PLANIFICADO}
     * admite cambios en su cabecera (R-05).
     */
    private static PlanResponse aResponse(PlanProduccion plan) {
        return new PlanResponse(
                plan.getIdPlan(),
                plan.getFechaPlanificacion(),
                plan.getFechaProduccion(),
                plan.getEstado().name(),
                plan.getObservaciones(),
                new PlanResponse.ResponsableRef(plan.getIdUsuario(), plan.getNombreResponsable()),
                plan.getCantidadProductos(),
                plan.getEstado() == EstadoPlan.PLANIFICADO);
    }
}
