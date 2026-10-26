package co.edu.corposucre.productionfood.lote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.TipoMovimientoPt;
import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;
import co.edu.corposucre.productionfood.lote.dto.CrearLoteProductoRequest;
import co.edu.corposucre.productionfood.lote.dto.LoteProductoResponse;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.producto.ProductoRepository;

/**
 * Reglas de negocio de los lotes de producto terminado (HU-19).
 *
 * <p>El alta es el <strong>único</strong> punto en el que nace stock vendible: sin una fila en
 * {@code inventario_producto_terminado}, HU-17 no tiene nada que despachar. Por eso
 * {@link #crearLoteProducto} escribe tres filas dentro de la misma transacción (R-02): el lote en
 * {@code lotes}, su saldo en {@code inventario_producto_terminado} y el movimiento
 * {@code ENTRADA_PRODUCCION} en {@code movimientos_inventario_pt}. Las tres o ninguna; un lote sin
 * inventario no aparece en el stock, no se puede vender y no produce ningún error, así que el fallo
 * solo se descubre al ir al estante.
 *
 * <p>La distinción de R-03 recorre toda la clase: {@code lotes.cantidad} es la cantidad con la que
 * el lote <strong>ingresó</strong> y nunca se modifica, mientras que
 * {@code inventario_producto_terminado.cantidad_disponible} es el saldo que baja con las ventas y
 * sube con las devoluciones. Al crear el lote ambos coinciden, pero la respuesta los devuelve por
 * separado para que el consumidor no los confunda.
 *
 * <p>El código de lote se normaliza a mayúsculas (R-08) porque la collation del {@code UNIQUE} es
 * <em>case-insensitive</em>: {@code pf-2026-0918} y {@code PF-2026-0918} colisionan de todos modos.
 * A diferencia de los lotes de materia prima (HU-09), aquí sí se rechaza un vencimiento pasado
 * (R-05): un lote de producto nacería sin poder venderse.
 */
@Service
public class LoteProductoService {

    /** Escala de las columnas {@code decimal(10,2)} que reciben la cantidad. */
    private static final int ESCALA_CANTIDAD = 2;

    /**
     * Lista blanca de ordenamiento del listado: traduce el campo que pide el cliente al alias de la
     * proyección. {@link PageRequest#of} rechaza cualquier campo que no esté aquí, de modo que el
     * orden nunca es SQL arbitrario. {@code id} se publica como {@code idLote} porque así se llama
     * el alias en {@link LoteProductoConDetalle}.
     */
    private static final Map<String, String> ORDEN = Map.of(
            "codigoLote", "codigoLote",
            "fechaProduccion", "fechaProduccion",
            "fechaVencimiento", "fechaVencimiento",
            "id", "idLote");

    private static final String ORDEN_POR_DEFECTO = "fechaVencimiento,asc";

    /**
     * Lista blanca del orden del kardex: traduce el campo que pide el cliente a la columna SQL con
     * su alias de tabla. {@link PageRequest#of} rechaza cualquier campo que no esté aquí, así que
     * la columna que llega a {@link InventarioPtRepository#buscarMovimientos} nunca es SQL
     * arbitrario.
     */
    private static final Map<String, String> ORDEN_MOVIMIENTOS = Map.of(
            "fecha", "m.fecha",
            "tipo", "m.tipo_movimiento",
            "cantidad", "m.cantidad",
            "saldoAnterior", "m.saldo_anterior",
            "saldoPosterior", "m.saldo_posterior",
            "id", "m.id_movimiento");

    private static final String ORDEN_MOVIMIENTOS_POR_DEFECTO = "id,desc";

    private final LoteRepository loteRepository;
    private final ProductoRepository productoRepository;
    private final InventarioPtRepository inventarioPtRepository;

    public LoteProductoService(LoteRepository loteRepository,
                               ProductoRepository productoRepository,
                               InventarioPtRepository inventarioPtRepository) {
        this.loteRepository = loteRepository;
        this.productoRepository = productoRepository;
        this.inventarioPtRepository = inventarioPtRepository;
    }

    /**
     * Registra un lote de producto terminado y crea su saldo inicial de inventario.
     *
     * <p>El orden de las comprobaciones va del error más barato y probable al más caro: código
     * duplicado, producto inexistente, coherencia de fechas, vencimiento y cantidad. El código se
     * normaliza antes de consultarlo (R-08). Las fechas iguales son válidas y un lote que vence hoy
     * todavía se puede registrar: solo se bloquea el vencimiento anterior a hoy (R-05).
     *
     * <p>{@code idProducto} va informado e {@code idMateriaPrima} e {@code idDetalleCompra} quedan
     * {@code null}, como exige {@code ck_lotes_exclusividad} para los lotes de producto (R-06). El
     * {@code INSERT} usa {@link LoteRepository#insertar} y no un {@code save} diferido porque el
     * inventario necesita ya el id que asigna {@code IDENTITY}. El {@code try}/{@code catch} cubre
     * solo ese {@code INSERT}: si otro alta gana la carrera entre la comprobación y la escritura,
     * el {@code UNIQUE} de {@code codigo_lote} dispara y se traduce al mismo 409 que la validación
     * previa, en lugar de dejar salir el conflicto de integridad genérico.
     *
     * <p>La cantidad se redondea a dos decimales HALF_UP solo para el inventario y el kardex, que
     * son columnas {@code decimal(10,2)}; el lote conserva el valor recibido.
     *
     * @param req datos del lote a crear
     * @return el lote releído de la base con su saldo y el stock agregado del producto
     * @throws ConflictoNegocioException  si el código ya existe o el producto no existe
     * @throws ParametroInvalidoException si el vencimiento precede a la producción, el lote ya
     *                                    venció o la cantidad no es mayor que cero
     */
    @Transactional
    // En las expresiones de detalle los accesores de record se leen como propiedades
    // (#resultado.codigoLote) y no como llamadas: el contexto SpEL de solo lectura del aspecto
    // no resuelve la llamada y el detalle se perdería en silencio.
    @Auditable(accion = "CREAR", tabla = "lotes",
            detalle = "'Lote de producto registrado: ' + #resultado.codigoLote")
    public LoteProductoResponse crearLoteProducto(CrearLoteProductoRequest req) {
        var codigo = req.codigoLote().trim().toUpperCase(Locale.ROOT);

        if (loteRepository.existsByCodigoLote(codigo)) {
            throw codigoDuplicado(codigo);
        }
        // CA-02 solo exige que el producto exista: no se mira el estado, porque un lote de un
        // producto desactivado sigue siendo una operación legítima.
        if (!productoRepository.existsById(req.idProducto())) {
            throw new ConflictoNegocioException("PRODUCTO_INEXISTENTE",
                    "El producto indicado no existe.");
        }
        if (req.fechaVencimiento().isBefore(req.fechaProduccion())) {
            throw new ParametroInvalidoException("FECHAS_INVALIDAS",
                    "La fecha de vencimiento no puede ser anterior a la fecha de producción.");
        }
        // R-05: el vencimiento de hoy es válido; solo se bloquea el que ya pasó.
        if (req.fechaVencimiento().isBefore(LocalDate.now())) {
            throw new ParametroInvalidoException("LOTE_YA_VENCIDO",
                    "No se pueden registrar lotes de producto ya vencidos.");
        }
        // El DTO solo valida @NotNull y @Digits a propósito: el cero y el negativo deben
        // responder CANTIDAD_INVALIDA y no el genérico VALIDACION_FALLIDA.
        if (req.cantidad() == null || req.cantidad().signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad debe ser un número mayor que cero.");
        }

        var lote = new Lote();
        lote.setCodigoLote(codigo);
        lote.setIdProducto(req.idProducto());
        // R-06: un lote es de producto o de materia prima, nunca de ambos.
        lote.setIdMateriaPrima(null);
        lote.setIdDetalleCompra(null);
        lote.setFechaProduccion(req.fechaProduccion());
        lote.setFechaVencimiento(req.fechaVencimiento());
        lote.setCantidad(req.cantidad());

        Lote creado;
        try {
            creado = loteRepository.insertar(lote);
        } catch (DataIntegrityViolationException e) {
            // Carrera entre dos altas con el mismo código: el UNIQUE gana y debe responder el
            // mismo 409 que la comprobación previa, no un conflicto de integridad genérico.
            if (mencionaCodigoLote(e)) {
                throw codigoDuplicado(codigo);
            }
            throw e;
        }

        var cantidadInventario = req.cantidad().setScale(ESCALA_CANTIDAD, RoundingMode.HALF_UP);
        inventarioPtRepository.crearParaLote(creado.getIdLote(), cantidadInventario);
        inventarioPtRepository.registrarMovimiento(
                creado.getIdLote(), TipoMovimientoPt.ENTRADA_PRODUCCION, cantidadInventario,
                new BigDecimal("0.00"), cantidadInventario,
                "lotes", creado.getIdLote(), "Lote " + codigo);

        var fila = loteRepository.buscarProductoPorId(creado.getIdLote())
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "el lote de producto", creado.getIdLote()));
        return aResponse(fila);
    }

    /**
     * Lista lotes de producto terminado con sus filtros opcionales y paginación (CA-06).
     *
     * <p>El {@code where} del repositorio exige {@code l.id_producto is not null}, así que este
     * listado no mezcla los lotes de materia prima de HU-09 aunque compartan la tabla
     * {@code lotes} (R-06): cada historia consulta solo su mitad.
     *
     * <p>{@code soloVencidos} y {@code conStock} se normalizan a {@code TRUE} o {@code null}, nunca
     * a {@code FALSE}: el repositorio solo filtra cuando recibe {@code TRUE}, y un {@code FALSE}
     * significaría «exigir no vencido» o «exigir sin stock», excluyendo justo las filas que el
     * cliente quiere ver. {@code proximosAVencer} son los días de la ventana de vencimiento
     * próximo, contados desde hoy con ambos extremos incluidos, y debe ser mayor que cero.
     *
     * <p>El orden se resuelve contra {@link #ORDEN} y por defecto es {@code fechaVencimiento,asc},
     * el que muestra primero lo que antes caduca. Cada fila conserva la separación de R-03 entre
     * {@code cantidadIngresada} y {@code cantidadDisponible}.
     *
     * @param idProducto      producto buscado o {@code null} para no filtrar
     * @param soloVencidos    {@code true} para traer solo los vencidos; cualquier otro valor no
     *                        filtra
     * @param proximosAVencer días de la ventana de vencimiento próximo, o {@code null}
     * @param conStock        {@code true} para traer solo lotes con saldo; cualquier otro valor no
     *                        filtra
     * @param page            índice de página, desde cero
     * @param size            tamaño de página, entre 1 y 100
     * @param sort            orden solicitado; por defecto {@code fechaVencimiento,asc}
     * @return página de lotes que cumplen los filtros
     * @throws ParametroInvalidoException si {@code proximosAVencer} no es mayor que cero, la
     *                                    paginación es inválida o el campo de orden no está en la
     *                                    lista blanca
     */
    @Transactional(readOnly = true)
    public PageResponse<LoteProductoResponse> listar(Integer idProducto, Boolean soloVencidos,
                                                     Integer proximosAVencer, Boolean conStock,
                                                     int page, int size, String sort) {
        if (proximosAVencer != null && proximosAVencer < 1) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO",
                    "El parámetro 'proximosAVencer' debe ser mayor que cero.");
        }
        var orden = PageRequest.of(page, size, sort, ORDEN, ORDEN_POR_DEFECTO);
        var pagina = loteRepository.listarProductos(
                idProducto,
                Boolean.TRUE.equals(soloVencidos) ? Boolean.TRUE : null,
                proximosAVencer,
                Boolean.TRUE.equals(conStock) ? Boolean.TRUE : null,
                orden.toPageable());
        return PageResponse.of(
                pagina.getContent().stream().map(LoteProductoService::aResponse).toList(),
                orden.page(), orden.size(), pagina.getTotalElements());
    }

    /**
     * Devuelve un lote de producto terminado con su producto y sus saldos (R-03).
     *
     * <p>La consulta exige {@code l.id_producto is not null}: un id inexistente y uno de un lote de
     * materia prima responden el mismo 404, porque desde este endpoint un lote de materia prima no
     * es un recurso válido.
     *
     * @param id identificador del lote
     * @return el lote con {@code cantidadIngresada} y {@code cantidadDisponible} por separado
     * @throws RecursoNoEncontradoException si el lote no existe o es de materia prima
     */
    @Transactional(readOnly = true)
    public LoteProductoResponse obtener(Integer id) {
        var fila = loteRepository.buscarProductoPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "el lote de producto", id));
        return aResponse(fila);
    }

    /**
     * Devuelve el kardex paginado de un lote de producto terminado.
     *
     * <p>El orden por defecto es {@code id,desc}, del movimiento más reciente al más antiguo. El
     * cliente puede pedir otro criterio de la lista blanca ({@code fecha}, {@code tipo},
     * {@code cantidad}, {@code saldoAnterior}, {@code saldoPosterior} o {@code id}); el repositorio
     * añade siempre {@code id_movimiento DESC} como desempate para que la trazabilidad sea estable
     * entre páginas aunque varias filas compartan fecha, tipo o cantidad. Sin este libro no habría
     * forma de reconstruir cómo se llegó al saldo actual del lote.
     *
     * <p>Un lote que no existe o que es de materia prima responde {@code RECURSO_NO_ENCONTRADO}: el
     * kardex a consultar es el de {@code movimientos_inventario_pt}, que solo tiene filas de lotes
     * de producto. En cambio, un lote de producto existente que todavía no tiene movimientos
     * devuelve una página vacía, no un 404.
     *
     * @param id   identificador del lote
     * @param page página solicitada, desde 0
     * @param size tamaño de página, entre 1 y 100
     * @param sort orden "campo,direccion"; por defecto {@code id,desc}
     * @return la página de movimientos y su total
     * @throws ParametroInvalidoException   si la paginación es inválida o el campo de orden no
     *                                      está en la lista blanca
     * @throws RecursoNoEncontradoException si el lote no existe o es de materia prima
     */
    @Transactional(readOnly = true)
    public PageResponse<MovimientoResponse> movimientos(Integer id, int page, int size,
                                                        String sort) {
        if (page < 0) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO",
                    "El parámetro 'page' no puede ser negativo.");
        }
        if (size < 1 || size > 100) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO",
                    "El parámetro 'size' debe estar entre 1 y 100.");
        }
        var orden = PageRequest.of(page, size, sort, ORDEN_MOVIMIENTOS,
                ORDEN_MOVIMIENTOS_POR_DEFECTO);
        // La existencia se comprueba contra la tabla de lotes y no contra el kardex: un lote
        // recién registrado existe aunque todavía no tenga movimientos.
        if (!loteRepository.esLoteProducto(id)) {
            throw new RecursoNoEncontradoException("el lote de producto", id);
        }

        // El offset se calcula en long: page no tiene cota superior, así que page * size
        // desbordaría int a un valor negativo y el OFFSET del SQL fallaría.
        long offset = (long) page * size;

        var filas = inventarioPtRepository.buscarMovimientos(id, orden.propiedad(),
                orden.ascendente(), size, offset);
        var total = inventarioPtRepository.contarMovimientos(id);

        return PageResponse.of(filas, page, size, total);
    }

    private static ConflictoNegocioException codigoDuplicado(String codigo) {
        return new ConflictoNegocioException("CODIGO_LOTE_DUPLICADO",
                "Ya existe un lote con el código " + codigo + ".");
    }

    /**
     * Indica si la violación de integridad viene del {@code UNIQUE} de {@code codigo_lote}.
     *
     * <p>Se mira la causa más específica y no el mensaje de la excepción de Spring, que solo dice
     * «could not execute statement»: el nombre de la restricción únicamente aparece en el error del
     * driver.
     */
    private static boolean mencionaCodigoLote(DataIntegrityViolationException e) {
        var mensaje = e.getMostSpecificCause().getMessage();
        return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains("codigo_lote");
    }

    /**
     * Calcula los campos derivados de la fecha y compone la respuesta.
     *
     * <p>{@code diasParaVencer} puede ser negativo si el lote vence después de registrarse y
     * {@code vencido} lo marca visualmente; aquí no se rechaza porque el alta solo bloquea el
     * vencimiento pasado (R-05). Los saldos vienen de la relectura: {@code cantidadIngresada} de
     * {@code lotes.cantidad} y {@code cantidadDisponible} de {@code inventario_producto_terminado}
     * (R-03).
     */
    private static LoteProductoResponse aResponse(LoteProductoConDetalle fila) {
        var hoy = LocalDate.now();
        return new LoteProductoResponse(
                fila.getIdLote(),
                fila.getCodigoLote(),
                new ProductoRefResponse(
                        fila.getIdProducto(), fila.getProductoNombre(),
                        fila.getUnidadAbreviatura()),
                fila.getFechaProduccion(),
                fila.getFechaVencimiento(),
                fila.getCantidadIngresada(),
                fila.getCantidadDisponible(),
                ChronoUnit.DAYS.between(hoy, fila.getFechaVencimiento()),
                fila.getFechaVencimiento().isBefore(hoy),
                fila.getStockTotalProducto());
    }
}
