package co.edu.corposucre.productionfood.produccion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.producto.ProductoRepository;
import co.edu.corposucre.productionfood.produccion.dto.ActualizarPlanificadaRequest;
import co.edu.corposucre.productionfood.produccion.dto.AgregarDetallePlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoTeoricoResponse;
import co.edu.corposucre.productionfood.produccion.dto.DetallePlanResponse;
import co.edu.corposucre.productionfood.produccion.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarProducidaRequest;
import co.edu.corposucre.productionfood.receta.DetalleReceta;
import co.edu.corposucre.productionfood.receta.DetalleRecetaRepository;
import co.edu.corposucre.productionfood.receta.Receta;
import co.edu.corposucre.productionfood.receta.RecetaRepository;

/**
 * Productos (líneas de detalle) de un plan de producción (HU-21): consulta paginada, alta,
 * edición de lo planificado, registro de lo producido y eliminación.
 *
 * <p>{@link #actualizarPlanificada(Integer, Integer, ActualizarPlanificadaRequest)} y
 * {@link #eliminar(Integer, Integer)} solo operan sobre planes {@code PLANIFICADO}, mientras que
 * {@link #registrarProducida(Integer, Integer, RegistrarProducidaRequest)} exige un plan
 * {@code EN_PROCESO}.
 *
 * <p>Los códigos de error son los del contrato: un plan inexistente responde 404
 * {@code PLAN_INEXISTENTE} a través de {@link PlanInexistenteException} —no el transversal
 * {@code RECURSO_NO_ENCONTRADO}—; un conflicto de negocio responde 409
 * ({@link ConflictoNegocioException}) y una cantidad fuera de rango, 400
 * ({@link ParametroInvalidoException}).
 *
 * <p>El alta <strong>no toca inventario ni lotes</strong> (R-07): agregar un producto a un plan
 * solo registra el compromiso de producción. El consumo real y el lote terminado son de HU-22 y
 * HU-23.
 */
@Service
public class DetallePlanService {

    private final PlanProduccionRepository planProduccionRepository;

    private final DetallePlanRepository detallePlanRepository;

    private final ProductoRepository productoRepository;

    private final RecetaRepository recetaRepository;

    private final DetalleRecetaRepository detalleRecetaRepository;

    private final CalculadoraConsumoService calculadoraConsumoService;

    public DetallePlanService(PlanProduccionRepository planProduccionRepository,
                              DetallePlanRepository detallePlanRepository,
                              ProductoRepository productoRepository,
                              RecetaRepository recetaRepository,
                              DetalleRecetaRepository detalleRecetaRepository,
                              CalculadoraConsumoService calculadoraConsumoService) {
        this.planProduccionRepository = planProduccionRepository;
        this.detallePlanRepository = detallePlanRepository;
        this.productoRepository = productoRepository;
        this.recetaRepository = recetaRepository;
        this.detalleRecetaRepository = detalleRecetaRepository;
        this.calculadoraConsumoService = calculadoraConsumoService;
    }

    /**
     * Lista los productos de un plan de forma paginada, con su avance y su consumo teórico
     * estimado.
     *
     * <p>El plan se comprueba primero: si no existe, 404 {@code PLAN_INEXISTENTE}, incluso antes
     * de validar la paginación. El orden se resuelve con la lista blanca
     * {@link DetallePlanRepository#ORDEN} y cae a {@code "producto,asc"} cuando no se informa;
     * {@link PageRequest#of} es quien rechaza una página o un campo inválidos.
     *
     * <p>Las recetas y sus ingredientes se cargan en lote, una sola consulta por conjunto, para
     * que la página no ejecute una consulta por línea (evita el N+1): se reúnen los
     * {@code idProducto} distintos, se buscan sus recetas con
     * {@link RecetaRepository#buscarPorProductos(List)}, se reúnen los {@code idReceta} distintos
     * y se traen todos sus ingredientes con
     * {@link DetalleRecetaRepository#listarPorRecetas(List)}; después se agrupan en memoria.
     *
     * @param idPlan identificador del plan cuyas líneas se listan
     * @param page   página solicitada, desde 0
     * @param size   tamaño de página, entre 1 y 100
     * @param sort   orden "campo,direccion", con campos de la lista blanca {@code producto},
     *               {@code cantidadPlanificada} y {@code avance}; por defecto {@code "producto,asc"}
     * @return página de líneas con producto, cantidades, avance y consumo teórico estimado
     * @throws PlanInexistenteException    si el plan no existe (404)
     * @throws ParametroInvalidoException  si la paginación o el campo de orden son inválidos
     */
    @Transactional(readOnly = true)
    public PageResponse<DetallePlanResponse> listar(Integer idPlan, int page, int size, String sort) {
        buscarPlan(idPlan);

        var orden = PageRequest.of(page, size, sort, DetallePlanRepository.ORDEN, "producto,asc");
        var lineas = detallePlanRepository.listarPorPlan(idPlan, orden);
        var total = detallePlanRepository.contarPorPlan(idPlan);

        var recetasPorProducto = recetasPorProducto(lineas);
        var detallesPorReceta = detallesPorReceta(recetasPorProducto);

        var contenido = lineas.stream()
                .map(linea -> {
                    var receta = recetasPorProducto.get(linea.getIdProducto());
                    return aResponse(linea, receta, detallesDe(receta, detallesPorReceta));
                })
                .toList();

        return PageResponse.of(contenido, orden.page(), orden.size(), total);
    }

    /**
     * Agrega un producto a un plan {@code PLANIFICADO} (HU-21 CA-01..CA-04, R-05, R-06).
     *
     * <p>Orden exacto de las validaciones, de la especificación §5:
     *
     * <ol>
     *   <li>El plan existe; si no, 404 {@code PLAN_INEXISTENTE}.</li>
     *   <li>El plan está {@code PLANIFICADO}; si no, 409 {@code PLAN_NO_EDITABLE} (CP-04): un
     *       plan en proceso ya comprometió sus líneas y uno terminal cerró su ciclo.</li>
     *   <li>La cantidad, redondeada a escala 2, es mayor que cero; si no, 400
     *       {@code CANTIDAD_INVALIDA} (CP-06, CP-07).</li>
     *   <li>El producto existe y está activo; si no, 409 {@code PRODUCTO_INEXISTENTE}
     *       (CA-02, CP-05).</li>
     *   <li>El producto no es ya una línea del plan; si lo es, 409 {@code PRODUCTO_DUPLICADO}
     *       (R-05, CP-16).</li>
     * </ol>
     *
     * <p>La línea se inserta sin {@code cantidadProducida}: la columna tiene {@code DEFAULT 0.00}
     * y la base la inicializa (CA-04, CP-02). La respuesta se relee de la base y calcula el
     * consumo teórico de esa línea a partir de su receta, si la tiene; un producto sin receta
     * responde {@code tieneReceta=false} y consumo vacío (R-06, CP-19).
     *
     * @param idPlan identificador del plan
     * @param req    producto y cantidad planificada, mayor que cero
     * @return la línea insertada, con su avance inicial en cero y su consumo teórico estimado
     * @throws PlanInexistenteException    si el plan no existe (404)
     * @throws ConflictoNegocioException   si el plan no está {@code PLANIFICADO}, el producto no
     *                                     existe o está inactivo, o ya está en el plan
     * @throws ParametroInvalidoException  si la cantidad no es mayor que cero
     */
    @Transactional
    @Auditable(accion = "CREAR", tabla = "detalle_plan_produccion",
            detalle = "'Producto ' + #req.idProducto + ' agregado al plan ' + #idPlan")
    public DetallePlanResponse agregar(Integer idPlan, AgregarDetallePlanRequest req) {
        var plan = buscarPlan(idPlan);
        if (plan.getEstado() != EstadoPlan.PLANIFICADO) {
            throw new ConflictoNegocioException("PLAN_NO_EDITABLE",
                    String.format("El plan de producción con id %s no es editable: solo los "
                            + "planes PLANIFICADO admiten cambios en su detalle.", idPlan));
        }

        var cantidad = validarCantidad(req.cantidadPlanificada());

        if (!productoRepository.existeActivo(req.idProducto())) {
            throw new ConflictoNegocioException("PRODUCTO_INEXISTENTE",
                    String.format("El producto con id %s no existe o está inactivo; no puede "
                            + "agregarse al plan.", req.idProducto()));
        }

        if (detallePlanRepository.existeProducto(idPlan, req.idProducto())) {
            throw new ConflictoNegocioException("PRODUCTO_DUPLICADO",
                    "El producto indicado ya está en el plan; edite la cantidad de la línea "
                            + "existente.");
        }

        var detalle = new DetallePlanProduccion();
        detalle.setIdPlan(idPlan);
        detalle.setIdProducto(req.idProducto());
        detalle.setCantidadPlanificada(cantidad);

        var idDetalle = detallePlanRepository.insertar(detalle);
        var guardada = detallePlanRepository.buscarPorId(idDetalle)
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle));

        return aResponseDeLinea(guardada);
    }

    /**
     * Edita la cantidad planificada de una línea mientras el plan siga {@code PLANIFICADO}
     * (HU-21 R-02, CP-13).
     *
     * <p>Orden exacto de las validaciones:
     *
     * <ol>
     *   <li>El plan existe; si no, 404 {@code PLAN_INEXISTENTE}.</li>
     *   <li>El plan está {@code PLANIFICADO}; si no, 409 {@code PLAN_NO_EDITABLE}: un plan en
     *       proceso ya comprometió sus líneas y uno terminal cerró su ciclo.</li>
     *   <li>La línea existe y pertenece al plan; si no, 404 {@code RECURSO_NO_ENCONTRADO}.</li>
     *   <li>La cantidad, redondeada a escala 2, es mayor que cero; si no, 400
     *       {@code CANTIDAD_INVALIDA}.</li>
     * </ol>
     *
     * <p>La respuesta se relee de la base para devolver la línea con el nombre del producto y su
     * consumo teórico recalculado sobre la nueva cantidad.
     *
     * @param idPlan    identificador del plan
     * @param idDetalle identificador de la línea a editar
     * @param req       nueva cantidad planificada, mayor que cero
     * @return la línea actualizada
     * @throws PlanInexistenteException    si el plan no existe (404)
     * @throws ConflictoNegocioException   si el plan no está {@code PLANIFICADO}
     * @throws RecursoNoEncontradoException si la línea no existe o no pertenece al plan
     * @throws ParametroInvalidoException  si la cantidad no es mayor que cero
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "detalle_plan_produccion",
            detalle = "'Cantidad planificada de la linea ' + #idDetalle + ' del plan ' + #idPlan")
    public DetallePlanResponse actualizarPlanificada(Integer idPlan, Integer idDetalle,
                                                     ActualizarPlanificadaRequest req) {
        var plan = buscarPlan(idPlan);
        if (plan.getEstado() != EstadoPlan.PLANIFICADO) {
            throw new ConflictoNegocioException("PLAN_NO_EDITABLE",
                    String.format("El plan de producción con id %s no es editable: solo los "
                            + "planes PLANIFICADO admiten cambios en su detalle.", idPlan));
        }

        buscarLineaDelPlan(idPlan, idDetalle);
        var cantidad = validarCantidad(req.cantidadPlanificada());

        if (detallePlanRepository.actualizarPlanificada(idDetalle, cantidad) == 0) {
            throw new RecursoNoEncontradoException("la línea de detalle", idDetalle);
        }

        return aResponseDeLinea(detallePlanRepository.buscarPorId(idDetalle)
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle)));
    }

    /**
     * Registra la cantidad producida de una línea de un plan {@code EN_PROCESO} (HU-21 R-03,
     * R-04; CP-08..CP-12).
     *
     * <p>Orden exacto de las validaciones:
     *
     * <ol>
     *   <li>El plan existe; si no, 404 {@code PLAN_INEXISTENTE}.</li>
     *   <li>El plan está {@code EN_PROCESO}; si no, 409 {@code PLAN_NO_EN_PROCESO}: solo un plan
     *       que ya arrancó produce, y uno terminal ya no admite avance.</li>
     *   <li>La línea existe y pertenece al plan; si no, 404 {@code RECURSO_NO_ENCONTRADO}.</li>
     *   <li>La cantidad producida no es negativa; si lo es, 400 {@code CANTIDAD_INVALIDA}.</li>
     * </ol>
     *
     * <p>El cero es válido —significa que todavía no se produjo nada (CP-11)— y no hay tope
     * superior, porque producir de más es un dato real (R-04). El avance lo calcula
     * {@link #aResponse}: 60 sobre 90 responde {@code 66.67} y 100 sobre 90, {@code 111.11}.
     * <strong>No toca inventario, lotes ni stock</strong> (R-07).
     *
     * @param idPlan    identificador del plan
     * @param idDetalle identificador de la línea
     * @param req       nueva cantidad producida, mayor o igual que cero
     * @return la línea actualizada, con su avance recalculado
     * @throws PlanInexistenteException    si el plan no existe (404)
     * @throws ConflictoNegocioException   si el plan no está {@code EN_PROCESO}
     * @throws RecursoNoEncontradoException si la línea no existe o no pertenece al plan
     * @throws ParametroInvalidoException  si la cantidad producida es negativa
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "detalle_plan_produccion",
            detalle = "'Produccion registrada en la linea ' + #idDetalle + ' del plan ' + #idPlan")
    public DetallePlanResponse registrarProducida(Integer idPlan, Integer idDetalle,
                                                  RegistrarProducidaRequest req) {
        var plan = buscarPlan(idPlan);
        if (plan.getEstado() != EstadoPlan.EN_PROCESO) {
            throw new ConflictoNegocioException("PLAN_NO_EN_PROCESO",
                    String.format("El plan de producción con id %s no admite el registro de "
                            + "producción: solo los planes EN_PROCESO lo permiten.", idPlan));
        }

        buscarLineaDelPlan(idPlan, idDetalle);
        var cantidad = validarProducida(req.cantidadProducida());

        if (detallePlanRepository.actualizarProducida(idDetalle, cantidad) == 0) {
            throw new RecursoNoEncontradoException("la línea de detalle", idDetalle);
        }

        return aResponseDeLinea(detallePlanRepository.buscarPorId(idDetalle)
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle)));
    }

    /**
     * Quita una línea de un plan {@code PLANIFICADO} (HU-21 R-08, CP-22).
     *
     * <p>Orden exacto de la operación:
     *
     * <ol>
     *   <li>El plan existe; si no, 404 {@code PLAN_INEXISTENTE}.</li>
     *   <li>El plan está {@code PLANIFICADO}; si no, 409 {@code PLAN_NO_EDITABLE}: quitar una
     *       línea de un plan ya iniciado alteraría el compromiso y la eficiencia histórica.</li>
     *   <li>La línea se bloquea con {@code SELECT ... FOR UPDATE}; si no existe o no pertenece
     *       al plan, 404 {@code RECURSO_NO_ENCONTRADO}.</li>
     *   <li>La línea no tiene consumos de materia prima; si los tiene, 409
     *       {@code DETALLE_CON_CONSUMOS} y no se borra, porque la llave foránea de
     *       {@code consumo_materia_prima} impediría dejarlos huérfanos.</li>
     * </ol>
     *
     * @param idPlan    identificador del plan
     * @param idDetalle identificador de la línea a quitar
     * @throws PlanInexistenteException    si el plan no existe (404)
     * @throws ConflictoNegocioException   si el plan no está {@code PLANIFICADO} o la línea ya
     *                                     tiene consumos
     * @throws RecursoNoEncontradoException si la línea no existe o no pertenece al plan
     */
    @Transactional
    @Auditable(accion = "ELIMINAR", tabla = "detalle_plan_produccion",
            detalle = "'Linea ' + #idDetalle + ' quitada del plan ' + #idPlan")
    public void eliminar(Integer idPlan, Integer idDetalle) {
        var plan = buscarPlan(idPlan);
        if (plan.getEstado() != EstadoPlan.PLANIFICADO) {
            throw new ConflictoNegocioException("PLAN_NO_EDITABLE",
                    String.format("El plan de producción con id %s no es editable: solo los "
                            + "planes PLANIFICADO admiten cambios en su detalle.", idPlan));
        }

        var linea = detallePlanRepository.buscarPorIdParaActualizar(idDetalle);
        if (linea.isEmpty() || !idPlan.equals(linea.get().getIdPlan())) {
            throw new RecursoNoEncontradoException("la línea de detalle", idDetalle);
        }

        if (detallePlanRepository.tieneConsumos(idDetalle)) {
            throw new ConflictoNegocioException("DETALLE_CON_CONSUMOS",
                    String.format("La línea de detalle con id %s tiene consumos de materia prima "
                            + "registrados y no puede eliminarse.", idDetalle));
        }

        if (detallePlanRepository.eliminar(idDetalle) == 0) {
            throw new RecursoNoEncontradoException("la línea de detalle", idDetalle);
        }
    }

    /**
     * Carga el plan o responde 404 con el código propio de HU-21.
     *
     * <p>No es {@link RecursoNoEncontradoException}: ese responde con el código transversal
     * {@code RECURSO_NO_ENCONTRADO} y aquí el contrato exige {@code PLAN_INEXISTENTE} para
     * distinguir que el que falta es el documento padre.
     *
     * @param idPlan identificador del plan
     * @return el plan existente
     * @throws PlanInexistenteException si el plan no existe
     */
    private PlanProduccion buscarPlan(Integer idPlan) {
        return planProduccionRepository.buscarPorId(idPlan)
                .orElseThrow(() -> new PlanInexistenteException(idPlan));
    }

    /**
     * Carga una línea comprobando que pertenece al plan indicado.
     *
     * <p>La pertenencia no se delega a la base: {@link DetallePlanRepository#buscarPorId} busca
     * por identificador de línea y una línea de otro plan sería un recurso ajeno, así que se
     * responde 404 {@code RECURSO_NO_ENCONTRADO} en lugar de editar o borrar algo que el cliente
     * no pidió.
     *
     * @param idPlan    identificador del plan al que debe pertenecer la línea
     * @param idDetalle identificador de la línea
     * @return la línea existente y del plan
     * @throws RecursoNoEncontradoException si la línea no existe o es de otro plan
     */
    private DetallePlanProduccion buscarLineaDelPlan(Integer idPlan, Integer idDetalle) {
        return detallePlanRepository.buscarPorId(idDetalle)
                .filter(linea -> idPlan.equals(linea.getIdPlan()))
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle));
    }

    /**
     * Segunda barrera tras el {@code @NotNull} del DTO (§5 {@code CANTIDAD_INVALIDA}).
     *
     * <p>Un valor como {@code 0.001} pasa Bean Validation pero, redondeado a la escala 2 de la
     * columna, queda en cero: una línea de cero unidades no es una línea y sin esta comprobación
     * llegaría al {@code CHECK (cantidad_planificada > 0)} como un error de integridad en lugar
     * de un 400 con mensaje.
     *
     * @param cantidad cantidad recibida, posiblemente nula o con más decimales
     * @return la cantidad normalizada a escala 2
     * @throws ParametroInvalidoException si la cantidad es nula o no es mayor que cero tras
     *                                    redondear
     */
    private static BigDecimal validarCantidad(BigDecimal cantidad) {
        var normalizada = cantidad == null ? null : cantidad.setScale(2, RoundingMode.HALF_UP);
        if (normalizada == null || normalizada.signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad planificada debe ser mayor que cero.");
        }
        return normalizada;
    }

    /**
     * Normaliza la cantidad producida y rechaza los negativos (HU-21 R-04, CP-11, CP-12).
     *
     * <p>A diferencia de {@link #validarCantidad(BigDecimal)}, el cero es válido: significa que
     * todavía no se produjo nada (CP-11). Tampoco hay tope superior, porque producir de más es
     * un dato real y el avance puede superar el 100 % (CP-12). Se redondea a escala 2 con
     * {@code HALF_UP} para que coincida con la columna.
     *
     * @param cantidad cantidad recibida, posiblemente nula o con más decimales
     * @return la cantidad normalizada a escala 2
     * @throws ParametroInvalidoException si la cantidad es nula o negativa
     */
    private static BigDecimal validarProducida(BigDecimal cantidad) {
        var normalizada = cantidad == null ? null : cantidad.setScale(2, RoundingMode.HALF_UP);
        if (normalizada == null || normalizada.signum() < 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad producida no puede ser negativa.");
        }
        return normalizada;
    }

    /**
     * Carga en lote las recetas de los productos de la página.
     *
     * <p>Una sola consulta para todos los {@code idProducto} distintos, en lugar de una por
     * línea. El mapa resultante usa {@code idProducto} como clave; el merge defensivo descarta
     * una segunda receta del mismo producto, aunque el esquema ya garantiza una por producto.
     *
     * @param lineas líneas de la página
     * @return recetas indexadas por producto; vacío si la página no tiene líneas
     */
    private Map<Integer, Receta> recetasPorProducto(List<DetallePlanProduccion> lineas) {
        if (lineas.isEmpty()) {
            return Map.of();
        }
        var idsProductos = lineas.stream()
                .map(DetallePlanProduccion::getIdProducto)
                .distinct()
                .toList();
        return recetaRepository.buscarPorProductos(idsProductos).stream()
                .collect(Collectors.toMap(Receta::getIdProducto, receta -> receta,
                        (existente, repetida) -> existente));
    }

    /**
     * Carga en lote los ingredientes de todas las recetas de la página.
     *
     * <p>Una sola consulta para todos los {@code idReceta} distintos. El resultado se agrupa por
     * {@code idReceta} para que cada línea encuentre sus ingredientes en memoria.
     *
     * @param recetasPorProducto recetas de la página indexadas por producto
     * @return ingredientes agrupados por receta; vacío si no hay recetas
     */
    private Map<Integer, List<DetalleReceta>> detallesPorReceta(
            Map<Integer, Receta> recetasPorProducto) {
        if (recetasPorProducto.isEmpty()) {
            return Map.of();
        }
        var idsRecetas = recetasPorProducto.values().stream()
                .map(Receta::getIdReceta)
                .distinct()
                .toList();
        return detalleRecetaRepository.listarPorRecetas(idsRecetas).stream()
                .collect(Collectors.groupingBy(DetalleReceta::getIdReceta));
    }

    /**
     * Ingredientes de la receta de una línea, o lista vacía si el producto no tiene receta.
     *
     * @param receta            receta del producto, o {@code null} si no tiene
     * @param detallesPorReceta ingredientes agrupados por receta
     * @return ingredientes de la receta; vacío si no hay receta o no tiene ingredientes
     */
    private static List<DetalleReceta> detallesDe(
            Receta receta, Map<Integer, List<DetalleReceta>> detallesPorReceta) {
        return receta == null
                ? List.of()
                : detallesPorReceta.getOrDefault(receta.getIdReceta(), List.of());
    }

    /**
     * Convierte una línea en la respuesta del contrato cargando su receta e ingredientes.
     *
     * <p>Es la variante de una sola línea de {@link #aResponse}; la usan las escrituras que
     * releen su fila. Un producto sin receta responde {@code tieneReceta=false} y consumo vacío
     * (R-06, CP-19); si la tiene, se consultan sus ingredientes porque el cálculo teórico los
     * necesita.
     *
     * @param linea línea leída de la base
     * @return la línea en la forma del contrato, con su avance y consumo teórico
     */
    private DetallePlanResponse aResponseDeLinea(DetallePlanProduccion linea) {
        var receta = recetaRepository.buscarPorProducto(linea.getIdProducto()).orElse(null);
        List<DetalleReceta> detalles = receta == null
                ? List.of()
                : detalleRecetaRepository.listarPorReceta(receta.getIdReceta());
        return aResponse(linea, receta, detalles);
    }

    /**
     * Convierte una línea de detalle en la respuesta del contrato.
     *
     * <p>Las cantidades se normalizan a escala 2 con {@code HALF_UP} y el avance se calcula como
     * {@code producida * 100 / planificada}, redondeado a escala 2: 60 sobre 90 responde
     * {@code 66.67} (CP-08) y 100 sobre 90 responde {@code 111.11} (CP-12, R-04). El consumo
     * teórico reutiliza {@link CalculadoraConsumoService} y se devuelve siempre como lista,
     * nunca {@code null}, aunque el producto no tenga receta (R-06, CP-19).
     *
     * @param linea    línea leída de la base
     * @param receta   receta de su producto, o {@code null} si no tiene
     * @param detalles ingredientes de la receta; vacío si no tiene
     * @return la línea en la forma del contrato
     */
    private DetallePlanResponse aResponse(DetallePlanProduccion linea, Receta receta,
                                          List<DetalleReceta> detalles) {
        var planificada = linea.getCantidadPlanificada().setScale(2, RoundingMode.HALF_UP);
        var producida = linea.getCantidadProducida().setScale(2, RoundingMode.HALF_UP);
        var avance = producida.multiply(BigDecimal.valueOf(100))
                .divide(planificada, 2, RoundingMode.HALF_UP);

        var consumoTeorico = calculadoraConsumoService.calcular(planificada, receta, detalles)
                .stream()
                .map(teorico -> new ConsumoTeoricoResponse(teorico.materiaPrima(),
                        teorico.cantidad(), teorico.unidad()))
                .toList();

        return new DetallePlanResponse(
                linea.getIdDetallePlan(),
                new ProductoRefResponse(linea.getIdProducto(), linea.getNombreProducto(),
                        linea.getUnidad()),
                planificada,
                producida,
                avance,
                linea.isTieneReceta(),
                consumoTeorico);
    }
}
