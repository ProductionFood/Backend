package co.edu.corposucre.productionfood.lote;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.inventario.SaldosInventario;
import co.edu.corposucre.productionfood.inventario.TipoMovimiento;
import co.edu.corposucre.productionfood.lote.dto.CrearLoteMateriaPrimaRequest;
import co.edu.corposucre.productionfood.lote.dto.LoteMateriaPrimaResponse;
import co.edu.corposucre.productionfood.lote.dto.MateriaPrimaRefResponse;
import co.edu.corposucre.productionfood.lote.dto.SaldosInventarioResponse;
import co.edu.corposucre.productionfood.materiaprima.MateriaPrimaRepository;

/**
 * Reglas de negocio de los lotes de materia prima (HU-09).
 *
 * <p>El registro de un lote es uno de los dos puntos de entrada de stock del sistema (R-01):
 * inserta la fila en {@code lotes}, suma la cantidad en {@code inventario} y deja el movimiento
 * {@code ENTRADA_LOTE} en el kardex. Las tres escrituras ocurren en la transacción que abre
 * {@link #crear}, de modo que o se aplican todas o no se aplica ninguna. La suma <strong>nunca</strong>
 * se hace aquí: se delega siempre en {@link InventarioService#registrarEntrada}, que es el único
 * componente autorizado a ejecutar {@code UPDATE inventario}. Duplicar ese {@code UPDATE} rompería
 * la conciliación entre el saldo y el kardex que exige el estándar de QA.
 *
 * <p>La protección contra la doble suma (R-02) es la bifurcación central de esta clase: un lote
 * creado desde la recepción de una compra llega con {@code idDetalleCompra} informado y
 * <strong>no vuelve a sumar</strong>, porque el stock ya entró con esa recepción (HU-13). Solo el
 * alta manual, sin {@code idDetalleCompra}, suma. Si se sumara en ambos caminos, el inventario
 * mostraría el doble de lo físico sin lanzar ningún error: el fallo es silencioso y solo se
 * descubre al ir a la bodega.
 */
@Service
public class LoteService {

    private static final Map<String, String> ORDEN = Map.of(
        "codigoLote", "codigoLote",
        "fechaProduccion", "fechaProduccion",
        "fechaVencimiento", "fechaVencimiento",
        "cantidad", "cantidad",
        "id", "idLote");

    private final LoteRepository loteRepository;
    private final MateriaPrimaRepository materiaPrimaRepository;
    private final InventarioService inventarioService;

    public LoteService(LoteRepository loteRepository,
                       MateriaPrimaRepository materiaPrimaRepository,
                       InventarioService inventarioService) {
        this.loteRepository = loteRepository;
        this.materiaPrimaRepository = materiaPrimaRepository;
        this.inventarioService = inventarioService;
    }

    /**
     * Registra un lote de materia prima y, si es un alta manual, lo suma al inventario.
     *
     * <p>El orden de las comprobaciones busca el error más barato y más probable primero: código
     * duplicado, materia prima inexistente, fechas y cantidad. El código se normaliza a mayúsculas
     * antes de consultarlo porque la collation es <em>case-insensitive</em> y dos variantes de
     * capitalización colisionarían de todos modos (R-06). Las fechas iguales son válidas (R-04) y
     * un lote ya vencido también se registra y suma (R-05): puede ser inventario existente que se
     * va a dar de baja formalmente.
     *
     * <p>El {@code INSERT} se hace con {@link LoteRepository#insertar} y no con un {@code save}
     * diferido: el kardex necesita el id que la base asigna con {@code IDENTITY}. Si
     * {@code idDetalleCompra} viene informado no se toca el inventario (R-02); si viene
     * {@code null}, {@link InventarioService#registrarEntrada} suma la cantidad y devuelve los
     * saldos anterior y posterior que viajan en la respuesta.
     *
     * @param req datos del lote a crear
     * @return el lote releído de la base con sus saldos de inventario
     * @throws ConflictoNegocioException si el código ya existe o la materia prima no existe
     * @throws ParametroInvalidoException si el vencimiento precede a la producción o la cantidad
     *                                    no es mayor que cero
     */
    @Transactional
    // En las expresiones de detalle los accesores de record se leen como propiedades
    // (#resultado.codigoLote) y no como llamadas: el contexto SpEL de solo lectura del aspecto
    // no resuelve la llamada y el detalle se perdería en silencio.
    @Auditable(accion = "CREAR", tabla = "lotes",
            detalle = "'Lote registrado: ' + #resultado.codigoLote")
    public LoteMateriaPrimaResponse crear(CrearLoteMateriaPrimaRequest req) {
        var codigo = req.codigoLote().trim().toUpperCase(Locale.ROOT);

        if (loteRepository.existsByCodigoLote(codigo)) {
            throw new ConflictoNegocioException("CODIGO_LOTE_DUPLICADO",
                    "Ya existe un lote con el código " + codigo + ".");
        }
        // CA-02 solo exige que la materia prima exista: no se mira el estado, porque registrar
        // inventario de un insumo desactivado sigue siendo una operación legítima.
        if (!materiaPrimaRepository.existsById(req.idMateriaPrima())) {
            throw new ConflictoNegocioException("MATERIA_PRIMA_INEXISTENTE",
                    "La materia prima indicada no existe.");
        }
        if (req.fechaVencimiento().isBefore(req.fechaProduccion())) {
            throw new ParametroInvalidoException("FECHAS_INVALIDAS",
                    "La fecha de vencimiento no puede ser anterior a la fecha de producción.");
        }
        // El DTO solo valida @NotNull y @Digits a propósito: el cero y el negativo deben
        // responder CANTIDAD_INVALIDA y no el genérico VALIDACION_FALLIDA (CP-12 y CP-13).
        if (req.cantidad() == null || req.cantidad().signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad debe ser un número mayor que cero.");
        }

        var lote = new Lote();
        lote.setCodigoLote(codigo);
        // R-03: en esta historia el lote es de materia prima y el CHECK de exclusividad de V2
        // exige que id_producto quede NULL.
        lote.setIdProducto(null);
        lote.setIdMateriaPrima(req.idMateriaPrima());
        lote.setIdDetalleCompra(req.idDetalleCompra());
        lote.setFechaProduccion(req.fechaProduccion());
        lote.setFechaVencimiento(req.fechaVencimiento());
        lote.setCantidad(req.cantidad());

        var creado = loteRepository.insertar(lote);

        SaldosInventarioResponse saldos = null;
        // R-02: el lote nacido de una compra ya recibida no suma stock; solo el alta manual suma.
        if (req.idDetalleCompra() == null) {
            SaldosInventario movimiento = inventarioService.registrarEntrada(
                    req.idMateriaPrima(), req.cantidad(), TipoMovimiento.ENTRADA_LOTE,
                    "lotes", creado.getIdLote(), "Lote " + codigo);
            saldos = new SaldosInventarioResponse(movimiento.anterior(), movimiento.posterior());
        }

        var fila = loteRepository.buscarDetallePorId(creado.getIdLote())
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "el lote", creado.getIdLote()));
        return aResponse(fila, saldos);
    }

    /**
     * Lista los lotes de materia prima con sus filtros opcionales y paginación.
     *
     * <p>{@code soloVencidos} se normaliza a {@code TRUE} o {@code null}: el repositorio solo
     * filtra cuando recibe {@code TRUE}, y un {@code FALSE} significaría «exigir no vencido»,
     * que excluiría también los lotes vigentes. {@code proximosAVencer} son los días de la
     * ventana de vencimiento próximo y debe ser mayor que cero.
     *
     * @param idMateriaPrima  materia prima buscada o {@code null} para no filtrar
     * @param soloVencidos    {@code true} para traer solo los vencidos; cualquier otro valor no
     *                        filtra
     * @param proximosAVencer días de la ventana de vencimiento próximo, o {@code null}
     * @param page            índice de página, desde cero
     * @param size            tamaño de página, entre 1 y 100
     * @param sort            orden solicitado; por defecto {@code fechaVencimiento,asc}
     * @return página de lotes que cumplen los filtros
     * @throws ParametroInvalidoException si {@code proximosAVencer} no es mayor que cero
     */
    @Transactional(readOnly = true)
    public PageResponse<LoteMateriaPrimaResponse> listar(Integer idMateriaPrima,
                                                         Boolean soloVencidos,
                                                         Integer proximosAVencer,
                                                         int page, int size, String sort) {
        if (proximosAVencer != null && proximosAVencer < 1) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO",
                    "El parámetro 'proximosAVencer' debe ser mayor que cero.");
        }
        var orden = PageRequest.of(page, size, sort, ORDEN, "fechaVencimiento,asc");
        var pagina = loteRepository.listarConDetalle(
                idMateriaPrima,
                Boolean.TRUE.equals(soloVencidos) ? Boolean.TRUE : null,
                proximosAVencer,
                orden.toPageable());
        return PageResponse.of(
                pagina.getContent().stream().map(fila -> aResponse(fila, null)).toList(),
                orden.page(), orden.size(), pagina.getTotalElements());
    }

    /**
     * Devuelve un lote de materia prima con los datos de su materia prima.
     *
     * @param id identificador del lote
     * @return el lote, con {@code inventarioActualizado} vacío porque una lectura no mueve stock
     * @throws RecursoNoEncontradoException si el lote no existe o es de producto terminado
     */
    @Transactional(readOnly = true)
    public LoteMateriaPrimaResponse obtener(Integer id) {
        var fila = loteRepository.buscarDetallePorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el lote", id));
        return aResponse(fila, null);
    }

    /**
     * Calcula los campos derivados de la fecha y compone la respuesta.
     *
     * <p>{@code diasParaVencer} puede ser negativo en un lote vencido y {@code vencido} lo marca
     * visualmente (R-05) sin que el registro se haya rechazado. Los saldos solo llegan informados
     * en el alta manual; en el listado y en la obtención son {@code null}.
     */
    private static LoteMateriaPrimaResponse aResponse(LoteConDetalle fila,
                                                      SaldosInventarioResponse saldos) {
        var hoy = LocalDate.now();
        return new LoteMateriaPrimaResponse(
                fila.getIdLote(),
                fila.getCodigoLote(),
                new MateriaPrimaRefResponse(
                        fila.getIdMateriaPrima(), fila.getMateriaPrimaNombre(),
                        fila.getUnidadAbreviatura()),
                fila.getFechaProduccion(),
                fila.getFechaVencimiento(),
                fila.getCantidad(),
                ChronoUnit.DAYS.between(hoy, fila.getFechaVencimiento()),
                fila.getFechaVencimiento().isBefore(hoy),
                saldos);
    }
}
