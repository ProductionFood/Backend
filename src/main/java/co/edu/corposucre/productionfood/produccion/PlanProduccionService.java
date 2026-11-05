package co.edu.corposucre.productionfood.produccion;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.produccion.dto.ActualizarPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CambiarEstadoPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CrearPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.PlanResponse;
import co.edu.corposucre.productionfood.security.SecurityUtils;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

/**
 * Registro, consulta y ciclo de vida de los planes de producción (HU-20): alta, detalle,
 * listado paginado, edición de cabecera y transiciones de estado.
 *
 * <p>Los códigos de error son los del contrato: un dato de entrada fuera de rango responde 400
 * ({@link ParametroInvalidoException}) y un conflicto de negocio, 409
 * ({@link ConflictoNegocioException}). La fecha de producción nunca se persiste sin haber
 * validado que no precede a la de planificación (R-03).
 *
 * <p>Un plan nace siempre {@code PLANIFICADO} (CA-02/CP-06): el DTO no admite estado y el
 * servicio no lo lee de la petición. El responsable se resuelve a partir del usuario
 * autenticado y solo un ADMIN puede apuntar el plan a otro usuario (R-04).
 *
 * <p>Solo un plan {@code PLANIFICADO} admite edición de cabecera (R-05); las transiciones se
 * validan con la máquina de {@link EstadoPlan} (R-01). Ninguna operación de este servicio mueve
 * inventario (R-06): el consumo y la producción los registran HU-22 y HU-23.
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

        var idUsuario = resolverResponsable(req.idUsuario(), SecurityUtils.idUsuarioActual());

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
     * Edita las fechas, las observaciones y el responsable de un plan mientras siga
     * {@code PLANIFICADO} (R-05, CP-21).
     *
     * <p>Orden exacto de la operación, dentro de una única transacción:
     *
     * <ol>
     *   <li>El plan debe existir; si no, 404.</li>
     *   <li>Debe estar {@code PLANIFICADO} ({@code PLAN_NO_EDITABLE}, 409): un plan en proceso
     *       ya comprometió sus líneas y uno terminal cerró su ciclo.</li>
     *   <li>La fecha de producción no puede ser anterior a la de planificación
     *       ({@code FECHAS_INVALIDAS}, 400, R-03), con la misma regla que el alta.</li>
     *   <li>El responsable se resuelve con R-04: solo un ADMIN que envía {@code idUsuario}
     *       explícito puede cambiarlo, y ese usuario debe existir ({@code USUARIO_INEXISTENTE},
     *       409). <strong>Sin {@code idUsuario}, o con un rol no administrador, la edición
     *       conserva el responsable actual del plan</strong>; no lo reasigna al usuario que
     *       edita, porque una edición no cambia de dueño por el hecho de que otro usuario la
     *       guarde.</li>
     *   <li>El {@code UPDATE} va condicionado a {@code PLANIFICADO}: si otra transacción cambió
     *       el estado entre la lectura y la escritura, afecta cero filas y se responde
     *       {@code PLAN_NO_EDITABLE} en lugar de pisar un plan ya no editable.</li>
     * </ol>
     *
     * <p>La respuesta se relee de la base tras la escritura para que el nombre del responsable
     * y la cantidad de productos sean los reales.
     *
     * @param id  identificador del plan a editar
     * @param req fechas obligatorias, observaciones opcionales e {@code idUsuario} opcional
     * @return el plan releído de la base, con su cabecera nueva
     * @throws RecursoNoEncontradoException si el plan no existe (también si desaparece entre la
     *                                      escritura y la relectura)
     * @throws ConflictoNegocioException si el plan no está {@code PLANIFICADO}, si pierde la
     *                                   carrera del {@code UPDATE} o si el ADMIN designa un
     *                                   usuario inexistente
     * @throws ParametroInvalidoException si la fecha de producción precede a la de planificación
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "planes_produccion",
            detalle = "'Plan de producción editado: ' + #id")
    public PlanResponse actualizar(Integer id, ActualizarPlanRequest req) {
        var plan = planProduccionRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("plan de producción", id));

        if (plan.getEstado() != EstadoPlan.PLANIFICADO) {
            throw new ConflictoNegocioException("PLAN_NO_EDITABLE",
                    String.format("El plan de producción con id %s no es editable: solo los "
                            + "planes PLANIFICADO admiten cambios en su cabecera.", id));
        }

        if (req.fechaProduccion().isBefore(req.fechaPlanificacion())) {
            throw new ParametroInvalidoException("FECHAS_INVALIDAS",
                    "La fecha de producción no puede ser anterior a la fecha de planificación.");
        }

        plan.setFechaPlanificacion(req.fechaPlanificacion());
        plan.setFechaProduccion(req.fechaProduccion());
        plan.setObservaciones(req.observaciones());
        plan.setIdUsuario(resolverResponsable(req.idUsuario(), plan.getIdUsuario()));

        var afectadas = planProduccionRepository.actualizarSiEstado(plan,
                EstadoPlan.PLANIFICADO);
        if (afectadas == 0) {
            throw new ConflictoNegocioException("PLAN_NO_EDITABLE",
                    String.format("El plan de producción con id %s dejó de ser editable durante "
                            + "la operación: ya no está PLANIFICADO.", id));
        }

        return planProduccionRepository.buscarPorId(id)
                .map(PlanProduccionService::aResponse)
                .orElseThrow(() -> new RecursoNoEncontradoException("plan de producción", id));
    }

    /**
     * Cambia el estado de un plan validando la máquina de transiciones (R-01).
     *
     * <p>Orden exacto de la operación, dentro de una única transacción:
     *
     * <ol>
     *   <li>El literal se convierte a {@link EstadoPlan} de forma defensiva: uno no canónico
     *       responde 400 {@code VALIDACION_FALLIDA} y nunca se propaga como 500.</li>
     *   <li>El plan debe existir; si no, 404.</li>
     *   <li>La transición se valida con {@link EstadoPlan#puedeTransicionarA(EstadoPlan)}
     *       ({@code TRANSICION_INVALIDA}, 409): un salto prohibido —por ejemplo
     *       {@code PLANIFICADO} a {@code COMPLETADO}— o una salida de un estado terminal no
     *       pasan.</li>
     *   <li>Solo al destino {@code EN_PROCESO} se exige al menos una línea
     *       ({@code PLAN_SIN_PRODUCTOS}, 409, R-02, CP-14): iniciar un plan vacío no produciría
     *       nada y dejaría el estado sin efecto.</li>
     *   <li>El {@code UPDATE} va condicionado al estado leído: si otra transacción cambió el
     *       plan entre la lectura y la escritura, afecta cero filas y se responde
     *       {@code TRANSICION_INVALIDA}.</li>
     * </ol>
     *
     * <p><strong>No toca inventario (R-06)</strong>: el consumo lo registran HU-22 y HU-23, no
     * el cambio de estado. Por eso este servicio no depende de ningún repositorio de
     * inventario.
     *
     * @param id  identificador del plan
     * @param req estado destino, con el nombre canónico del enum
     * @return el plan releído de la base, con su estado nuevo
     * @throws ParametroInvalidoException si el literal del estado no es canónico
     * @throws RecursoNoEncontradoException si el plan no existe (también si desaparece entre la
     *                                      escritura y la relectura)
     * @throws ConflictoNegocioException si la transición no está permitida, el plan no tiene
     *                                   productos al iniciarse o pierde la carrera del
     *                                   {@code UPDATE}
     */
    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO", tabla = "planes_produccion",
            detalle = "'Plan de producción ' + #id + ' a ' + #req.estado")
    public PlanResponse cambiarEstado(Integer id, CambiarEstadoPlanRequest req) {
        var destino = parsearEstado(req.estado());

        var plan = planProduccionRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("plan de producción", id));

        if (!plan.getEstado().puedeTransicionarA(destino)) {
            throw new ConflictoNegocioException("TRANSICION_INVALIDA",
                    mensajeTransicion(plan.getEstado(), destino));
        }

        if (destino == EstadoPlan.EN_PROCESO && planProduccionRepository.contarDetalles(id) == 0) {
            throw new ConflictoNegocioException("PLAN_SIN_PRODUCTOS",
                    String.format("El plan de producción con id %s no tiene productos; no se "
                            + "puede iniciar sin líneas de detalle.", id));
        }

        var afectadas = planProduccionRepository.cambiarEstado(id, plan.getEstado(), destino);
        if (afectadas == 0) {
            throw new ConflictoNegocioException("TRANSICION_INVALIDA",
                    mensajeTransicion(plan.getEstado(), destino));
        }

        return planProduccionRepository.buscarPorId(id)
                .map(PlanProduccionService::aResponse)
                .orElseThrow(() -> new RecursoNoEncontradoException("plan de producción", id));
    }

    /**
     * Indica si un plan admite cambios de cabecera mientras siga {@code PLANIFICADO} (R-05).
     *
     * <p>Es la consulta que HU-21 hará antes de tocar el detalle: un plan en proceso ya
     * comprometió sus líneas y uno terminal cerró su ciclo, así que ninguno admite cambios.
     *
     * @param idPlan identificador del plan
     * @return {@code true} solo si el plan existe y está {@code PLANIFICADO}
     * @throws RecursoNoEncontradoException si el plan no existe
     */
    @Transactional(readOnly = true)
    public boolean esEditable(Integer idPlan) {
        return planProduccionRepository.buscarPorId(idPlan)
                .map(plan -> plan.getEstado() == EstadoPlan.PLANIFICADO)
                .orElseThrow(() -> new RecursoNoEncontradoException("plan de producción",
                        idPlan));
    }

    /**
     * Resuelve el responsable del plan según R-04.
     *
     * <p>Solo un ADMIN puede designar a otro usuario; cualquier otro rol, o una petición sin
     * {@code idUsuario}, conserva el responsable por defecto que recibe el método. En el alta
     * ese valor por defecto es el usuario autenticado (CP-10/CP-11); en la edición es el
     * responsable actual del plan, de modo que guardar una edición no cambia de dueño el plan.
     * Cuando el ADMIN sí designa a otro, se comprueba que exista antes de escribir.
     *
     * @param idUsuarioSolicitado responsable que envió el cliente, o {@code null}
     * @param idUsuarioPorDefecto responsable que se conserva si la petición no designa a otro
     * @return identificador del usuario que quedará como responsable
     * @throws ConflictoNegocioException si el ADMIN designa un usuario que no existe
     */
    private Integer resolverResponsable(Integer idUsuarioSolicitado, Integer idUsuarioPorDefecto) {
        if (idUsuarioSolicitado != null && "ADMIN".equals(SecurityUtils.rolActual())) {
            if (!usuarioRepository.existsById(idUsuarioSolicitado)) {
                throw new ConflictoNegocioException("USUARIO_INEXISTENTE",
                        String.format("El usuario con id %s no existe.", idUsuarioSolicitado));
            }
            return idUsuarioSolicitado;
        }
        return idUsuarioPorDefecto;
    }

    /**
     * Convierte el literal del contrato a {@link EstadoPlan} sin dejar que un valor inválido
     * escape como excepción de deserialización o {@code 500}.
     *
     * <p>Solo admite el nombre canónico del enum, en mayúsculas: el contrato envía
     * {@code PLANIFICADO}, {@code EN_PROCESO}, {@code COMPLETADO} o {@code CANCELADO}. Un valor
     * fuera de esa lista responde 400 {@code VALIDACION_FALLIDA} con los admitidos en el
     * mensaje.
     *
     * @param literal estado enviado por el cliente, o {@code null}
     * @return el estado del enum correspondiente
     * @throws ParametroInvalidoException si el literal no es un nombre canónico
     */
    private static EstadoPlan parsearEstado(String literal) {
        for (var estado : EstadoPlan.values()) {
            if (estado.name().equals(literal)) {
                return estado;
            }
        }
        var admitidos = Arrays.stream(EstadoPlan.values())
                .map(EstadoPlan::name)
                .sorted()
                .collect(Collectors.joining(", "));
        throw new ParametroInvalidoException("VALIDACION_FALLIDA",
                String.format("El estado '%s' no es válido. Los valores admitidos son: %s.",
                        literal, admitidos));
    }

    /**
     * Mensaje único de {@code TRANSICION_INVALIDA} (contrato §7), con el estado actual y los
     * destinos permitidos, para que la regla se lea igual venga de donde venga.
     *
     * @param actual  estado actual del plan
     * @param destino estado al que se intentó transicionar
     * @return mensaje del contrato con los destinos disponibles desde {@code actual}
     */
    private static String mensajeTransicion(EstadoPlan actual, EstadoPlan destino) {
        var permitidas = actual.transicionesValidas().stream()
                .map(EstadoPlan::name)
                .sorted()
                .collect(Collectors.joining(", "));
        var disponibles = permitidas.isEmpty()
                ? "no hay transiciones disponibles"
                : "las transiciones disponibles son: " + permitidas;
        return String.format("No se puede pasar de %s a %s. Desde %s %s.",
                actual, destino, actual, disponibles);
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
