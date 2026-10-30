package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import co.edu.corposucre.productionfood.compra.dto.ActualizarCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CompraResponse;
import co.edu.corposucre.productionfood.compra.dto.CrearCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraResponse;
import co.edu.corposucre.productionfood.compra.dto.GastoProveedorResponse;
import co.edu.corposucre.productionfood.compra.dto.MovimientoGeneradoResponse;
import co.edu.corposucre.productionfood.compra.dto.ProveedorRefResponse;
import co.edu.corposucre.productionfood.compra.dto.RecepcionResponse;
import co.edu.corposucre.productionfood.compra.dto.ResumenComprasResponse;
import co.edu.corposucre.productionfood.compra.dto.ResumenComprasResponse.Periodo;
import co.edu.corposucre.productionfood.compra.dto.ResumenComprasResponse.TotalesEstado;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.inventario.TipoMovimiento;
import co.edu.corposucre.productionfood.proveedor.ProveedorRepository;
import co.edu.corposucre.productionfood.receta.dto.MateriaPrimaRefResponse;

/**
 * Ciclo de vida de una compra (HU-13): alta, edición de cabecera, consulta, recepción y
 * cancelación.
 *
 * <p>Este componente cubre la cabecera y sus transiciones de estado. La recepción
 * ({@link #recibir(Integer)}) es el punto principal de entrada de stock del sistema (R-01): suma
 * inventario línea a línea a través de {@link InventarioService}, nunca con un {@code UPDATE}
 * directo. Las líneas de detalle se leen aquí ({@link #recibir(Integer)} las consume), pero su
 * escritura es de HU-14.
 *
 * <p>Los códigos de error son los de la especificación §5 y no el genérico
 * {@code VALIDACION_FALLIDA}: una regla de negocio violada responde 409
 * ({@link ConflictoNegocioException}) y un dato de entrada fuera de rango, 400
 * ({@link ParametroInvalidoException}).
 */
@Service
public class CompraService {

    /**
     * Lista blanca de ordenamiento del listado: traduce el campo que pide el cliente al prefijo
     * de columna SQL de {@code compras} (alias {@code c}). {@code PageRequest.of} rechaza
     * cualquier campo que no esté aquí, y la columna resuelta es la única que llega al
     * repositorio, de modo que el orden nunca es SQL arbitrario.
     *
     * <p>{@code total} es el alias {@code total_compra} del {@code SELECT} base, no una columna
     * de la tabla: el total se calcula con una subconsulta correlada sobre
     * {@code detalle_compra} y MySQL admite ordenar por el alias del select.
     */
    private static final Map<String, String> ORDEN = Map.of(
            "fecha", "c.fecha", "estado", "c.estado", "total", "total_compra",
            "id", "c.id_compra");

    private static final String ORDEN_POR_DEFECTO = "fecha,desc";

    /**
     * Tope de página cuando el listado se pide con detalle (HU-15 CP-12): el {@code JOIN} con
     * {@code detalle_compra} multiplica las filas por línea, así que con detalle el tamaño se
     * recorta a este máximo en vez de responder un error, y la respuesta refleja el tamaño
     * recortado. Sin detalle la cota sigue siendo la de {@link PageRequest} (100).
     */
    private static final int TAMANO_MAXIMO_CON_DETALLE = 20;

    private final CompraRepository compraRepository;

    private final ProveedorRepository proveedorRepository;

    private final InventarioService inventarioService;

    public CompraService(CompraRepository compraRepository,
                         ProveedorRepository proveedorRepository,
                         InventarioService inventarioService) {
        this.compraRepository = compraRepository;
        this.proveedorRepository = proveedorRepository;
        this.inventarioService = inventarioService;
    }

    /**
     * Registra una compra nueva, siempre en estado {@code PENDIENTE} (HU-13 §5).
     *
     * <p>Las validaciones siguen el orden de la especificación —proveedor, fecha y estado— para
     * que un error de referencia no se enmascare detrás de uno de formato:
     *
     * <ol>
     *   <li><strong>Proveedor</strong>: debe existir ({@code PROVEEDOR_INEXISTENTE}, 409, CP-02) y
     *       estar activo ({@code PROVEEDOR_INACTIVO}, 409, CP-03/R-06). La comprobación de
     *       actividad es exclusiva del alta: una compra ya registrada sigue su curso aunque su
     *       proveedor se desactive después, por eso R-06 no se repite en la edición.</li>
     *   <li><strong>Fecha</strong>: si se omite se registra la de hoy (CP-04); si es posterior a
     *       hoy se rechaza ({@code FECHA_FUTURA}, 400, R-07). Las fechas pasadas siempre se
     *       admiten, para poder registrar compras atrasadas (CP-06).</li>
     *   <li><strong>Estado</strong>: si se omite nace {@code PENDIENTE}; si se envía y no lo es se
     *       rechaza ({@code VALIDACION_FALLIDA}, 400). Una compra nace {@code PENDIENTE} y
     *       {@code RECIBIDA}/{@code CANCELADA} solo se alcanzan por transición de estado (diseño
     *       §4 «Al crear, el estado es siempre PENDIENTE»): dejar que alguien cree una
     *       {@code RECIBIDA} directamente saltaría la recepción y esa compra no sumaría stock a
     *       existencias (R-01).</li>
     * </ol>
     *
     * <p>La compra se inserta sin líneas de detalle: el total y la cantidad de ítems responden
     * {@code 0.00} y {@code 0} hasta que HU-14 agrega el detalle, que es el estado válido de una
     * compra recién registrada.
     *
     * @param req proveedor obligatorio, fecha y estado opcionales de la compra
     * @return la compra insertada, con total {@code 0.00}, cero ítems y {@code editable} a
     *         {@code true}
     * @throws ConflictoNegocioException si el proveedor no existe o está inactivo
     * @throws ParametroInvalidoException si la fecha es futura o el estado enviado no es
     *                                   {@code PENDIENTE}
     */
    @Transactional
    // En las expresiones de detalle los accesores de record se leen como propiedades
    // (#resultado.idCompra) y no como llamadas: el aspecto no resuelve llamadas de record.
    @Auditable(accion = "CREAR", tabla = "compras",
            detalle = "'Compra creada: ' + #resultado.idCompra"
                    + " + ' del proveedor ' + #req.idProveedor")
    public CompraResponse crear(CrearCompraRequest req) {
        var proveedor = proveedorRepository.findById(req.idProveedor())
                .orElseThrow(() -> new ConflictoNegocioException("PROVEEDOR_INEXISTENTE",
                        String.format("El proveedor con id %s no existe.", req.idProveedor())));
        if (Boolean.FALSE.equals(proveedor.getEstado())) {
            throw new ConflictoNegocioException("PROVEEDOR_INACTIVO",
                    String.format("El proveedor con id %s está inactivo; no puede recibir "
                            + "compras nuevas.", req.idProveedor()));
        }

        var hoy = LocalDate.now();
        var fecha = req.fecha() == null ? hoy : req.fecha();
        if (fecha.isAfter(hoy)) {
            throw new ParametroInvalidoException("FECHA_FUTURA",
                    "La fecha de la compra no puede ser posterior a hoy.");
        }

        var estado = req.estado() == null ? EstadoCompra.PENDIENTE : req.estado();
        if (estado != EstadoCompra.PENDIENTE) {
            throw new ParametroInvalidoException("VALIDACION_FALLIDA",
                    "Una compra solo puede registrarse en estado PENDIENTE; RECIBIDA y CANCELADA "
                            + "se alcanzan por transición de estado.");
        }

        var compra = new Compra();
        compra.setIdProveedor(proveedor.getIdProveedor());
        compra.setFecha(fecha);
        compra.setEstado(estado);
        var idCompra = compraRepository.insertar(compra);

        return new CompraResponse(idCompra,
                new ProveedorRefResponse(proveedor.getIdProveedor(), proveedor.getNombre()),
                fecha, estado.name(), new BigDecimal("0.00"), 0, true, null);
    }

    /**
     * Edita el proveedor y la fecha de una compra mientras siga {@code PENDIENTE} (HU-13 R-04).
     *
     * <p>El estado no se toca por aquí: tiene su endpoint propio, con la máquina de transiciones
     * de {@link EstadoCompra}. Las validaciones, en el orden de la especificación:
     *
     * <ol>
     *   <li>La compra debe existir; si no, 404 (patrón del resto del proyecto).</li>
     *   <li>Debe estar {@code PENDIENTE} ({@code COMPRA_NO_EDITABLE}, 409, R-04): una
     *       {@code RECIBIDA} ya asentó su recepción y su stock, y una {@code CANCELADA} cerró el
     *       ciclo; ninguna de las dos admite reescribir su cabecera.</li>
     *   <li>El proveedor debe existir ({@code PROVEEDOR_INEXISTENTE}, 409, CP-02), pero aquí
     *       <strong>no</strong> se exige que esté activo: R-06 reserva esa comprobación solo para
     *       el alta, de modo que reasignar una compra ya registrada no depende del estado actual
     *       del proveedor.</li>
     *   <li>La fecha es obligatoria —la revalida el servicio aunque el {@code @NotNull} del DTO ya
     *       la rechaza en la capa HTTP— y no puede ser posterior a hoy ({@code FECHA_FUTURA}, 400,
     *       R-07).</li>
     * </ol>
     *
     * <p>La respuesta se relee de la base tras la escritura para que {@code totalCompra} y
     * {@code cantidadItems}, derivados de {@code detalle_compra}, sean los de la fila y no una
     * copia en memoria.
     *
     * @param id   identificador de la compra a editar
     * @param req  proveedor y fecha nuevos; ninguno puede ser {@code null}
     * @return la compra releída de la base, con sus totales derivados
     * @throws RecursoNoEncontradoException si la compra no existe (también si desaparece entre la
     *                                      escritura y la relectura)
     * @throws ConflictoNegocioException si la compra no está {@code PENDIENTE} o el proveedor no
     *                                   existe
     * @throws ParametroInvalidoException si la fecha es futura
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "compras",
            detalle = "'Compra editada: ' + #resultado.idCompra")
    public CompraResponse actualizar(Integer id, ActualizarCompraRequest req) {
        var compra = compraRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));

        if (compra.getEstado() != EstadoCompra.PENDIENTE) {
            throw new ConflictoNegocioException("COMPRA_NO_EDITABLE",
                    String.format("La compra con id %s no es editable: solo las compras PENDIENTE "
                            + "admiten cambios en su cabecera.", id));
        }

        var proveedor = proveedorRepository.findById(req.idProveedor())
                .orElseThrow(() -> new ConflictoNegocioException("PROVEEDOR_INEXISTENTE",
                        String.format("El proveedor con id %s no existe.", req.idProveedor())));
        // R-06 deliberadamente no comprueba aquí que el proveedor esté activo: esa regla es solo
        // del alta (CP-03), y una compra PENDIENTE ya registrada no se bloquea por desactivarla.

        if (req.fecha() == null) {
            // Defensivo: el @NotNull del DTO ya devuelve 400 VALIDACION_FALLIDA en la capa HTTP.
            throw new ParametroInvalidoException("VALIDACION_FALLIDA",
                    "La fecha de la compra es obligatoria.");
        }
        if (req.fecha().isAfter(LocalDate.now())) {
            throw new ParametroInvalidoException("FECHA_FUTURA",
                    "La fecha de la compra no puede ser posterior a hoy.");
        }

        compraRepository.actualizar(id, proveedor.getIdProveedor(), req.fecha());

        return compraRepository.buscarPorId(id)
                .map(CompraService::aResponse)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));
    }

    /**
     * Lista compras de forma paginada y filtrada, con su proveedor y sus totales derivados.
     *
     * <p>El rango de fechas se valida antes de tocar la base (contrato §4): un rango invertido no
     * tiene resultados posibles y responder 400 explica el error mejor que una página vacía.
     * Con el mismo criterio, un filtro {@code idProveedor} que apunte a un proveedor inexistente
     * responde 404 ({@code PROVEEDOR_INEXISTENTE}, HU-15 §5) en lugar de una página vacía que
     * parecería «ese proveedor no tiene compras». Solo se comprueba <strong>existencia</strong>,
     * no actividad: el historial de una compra ya registrada no se bloquea por desactivar a su
     * proveedor, igual que el {@code PUT} de HU-13; el alta y la edición siguen respondiendo
     * 409 con el mismo código.
     *
     * <p>Los cuatro filtros son opcionales y viajan tal cual al repositorio; un valor nulo no
     * restringe. El total sale de {@link CompraRepository#contar} y no del tamaño de la página:
     * el listado puede traer una página y haber miles.
     *
     * <p>Con {@code incluirDetalle=true} (HU-15 R-06) cada compra de la página trae además sus
     * líneas en la forma de HU-14, resueltas con <strong>una sola consulta</strong>
     * ({@link CompraRepository#buscarConDetalle}, con {@code LEFT JOIN} a {@code detalle_compra})
     * y agrupadas en memoria por {@code id_compra} preservando el orden de la primera aparición
     * de cada compra: nunca hay una consulta por compra (N+1). Una compra sin líneas responde
     * {@code detalle: []}. En este modo el tamaño de página se recorta a 20
     * ({@code TAMANO_MAXIMO_CON_DETALLE}) si lo excede (CP-12, sin error) y la respuesta refleja
     * el tamaño recortado; sin detalle nada cambia y la cota sigue en 100.
     *
     * <p><strong>Condición de carrera (HU-15, deliberado):</strong> la página de compras, el
     * detalle y el conteo del total se leen dentro de esta misma
     * {@code @Transactional(readOnly = true)}. En REPEATABLE READ las tres consultas ven el
     * mismo snapshot de la base, de modo que una compra insertada mientras se construye la
     * página no puede hacer que {@code totalElements} mienta sobre el contenido de la página que
     * se devuelve: o la ve el snapshot entero o no lo ve ninguno.
     *
     * @param idProveedor    proveedor a filtrar, o {@code null} sin filtrar
     * @param estado         estado a filtrar, o {@code null} sin filtrar
     * @param fechaInicio    primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin       último día incluido del rango, o {@code null} sin límite superior
     * @param page           página solicitada, desde 0
     * @param size           tamaño de página, entre 1 y 100; con {@code incluirDetalle} se
     *                       recorta a 20 si lo excede
     * @param sort           orden "campo,direccion", con campos de la lista blanca
     *                       {@code fecha}, {@code estado}, {@code total} e {@code id};
     *                       por defecto "fecha,desc"
     * @param incluirDetalle {@code true} para añadir a cada compra su lista de líneas de detalle
     * @return página de compras con su proveedor y sus totales, y con {@code detalle} relleno
     *         solo cuando se pidió incluirlo
     * @throws ParametroInvalidoException si el rango de fechas está invertido, o la paginación o
     *                                    el campo de orden son inválidos
     * @throws ProveedorInexistenteFiltroException si {@code idProveedor} no existe
     */
    @Transactional(readOnly = true)
    public PageResponse<CompraResponse> listar(Integer idProveedor, EstadoCompra estado,
                                               LocalDate fechaInicio, LocalDate fechaFin,
                                               int page, int size, String sort,
                                               boolean incluirDetalle) {
        if (fechaInicio != null && fechaFin != null && fechaInicio.isAfter(fechaFin)) {
            // Código del contrato transversal (04-CONTRATO-API.md §4), no el genérico
            // PARAMETRO_INVALIDO: el frontend distingue el rango invertido de un query mal formado.
            throw new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                    "La fecha de inicio no puede ser posterior a la fecha de fin.");
        }

        // HU-15 §5: validación barata (un EXISTS por id) antes de cualquier consulta del
        // historial; solo en listar, que es el único endpoint con este filtro. Aquí no se
        // comprueba actividad por la misma razón que el PUT de HU-13.
        if (idProveedor != null && !proveedorRepository.existsById(idProveedor)) {
            throw new ProveedorInexistenteFiltroException(idProveedor);
        }

        // CP-12: el recorte va antes de PageRequest.of para que un size sobre la cota de detalle
        // se recorte y no responda 400; un size igual a 0 o negativo sigue rechazándose allí.
        var sizeEfectivo = incluirDetalle ? Math.min(size, TAMANO_MAXIMO_CON_DETALLE) : size;
        var orden = PageRequest.of(page, sizeEfectivo, sort, ORDEN, ORDEN_POR_DEFECTO);
        // El offset se calcula en long: page no tiene cota superior, así que page * size
        // desbordaría int a un valor negativo y el OFFSET del SQL fallaría.
        long offset = (long) orden.page() * orden.size();

        List<CompraResponse> contenido;
        if (incluirDetalle) {
            var filas = compraRepository.buscarConDetalle(idProveedor, estado, fechaInicio,
                    fechaFin, orden.propiedad(), orden.ascendente(), orden.size(), offset);
            contenido = agruparConDetalle(filas);
        } else {
            var filas = compraRepository.buscar(idProveedor, estado, fechaInicio, fechaFin,
                    orden.propiedad(), orden.ascendente(), orden.size(), offset);
            contenido = filas.stream().map(CompraService::aResponse).toList();
        }
        var total = compraRepository.contar(idProveedor, estado, fechaInicio, fechaFin);

        return PageResponse.of(contenido, orden.page(), orden.size(), total);
    }

    /**
     * Resume las compras de un período por estado, con sus gastos ejecutado y comprometido
     * (HU-15 §2).
     *
     * <p>El rango se valida igual que en {@link #listar(Integer, EstadoCompra, LocalDate,
     * LocalDate, int, int, String)}: un rango invertido no tiene resultados posibles y responde
     * 400 {@code RANGO_FECHAS_INVALIDO} antes de tocar la base.
     *
     * <p>Los totales llegan del repositorio ya agrupados por estado y siempre recalculados desde
     * {@code detalle_compra} (R-05). Un estado sin compras en el rango —y también un rango vacío
     * (CP-17)— responde cantidad {@code 0} y total {@code 0.00}, no un error. Por R-04 el gasto
     * ejecutado es solo el total de las RECIBIDA y el comprometido solo el de las PENDIENTE: las
     * CANCELADA se contabilizan en su bloque y quedan fuera de ambos.
     *
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return el resumen del período; el periodo de la respuesta recoge los filtros tal cual,
     *         con ambos campos {@code null} cuando no se envió ninguno
     * @throws ParametroInvalidoException si el rango de fechas está invertido
     */
    @Transactional(readOnly = true)
    public ResumenComprasResponse resumen(LocalDate fechaInicio, LocalDate fechaFin) {
        if (fechaInicio != null && fechaFin != null && fechaInicio.isAfter(fechaFin)) {
            // Mismo código del contrato transversal que en listar: el frontend distingue el
            // rango invertido de un query mal formado.
            throw new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                    "La fecha de inicio no puede ser posterior a la fecha de fin.");
        }

        var porEstado = compraRepository.resumenPorPeriodo(fechaInicio, fechaFin).stream()
                .collect(Collectors.toMap(ResumenEstadoFila::estado, fila -> fila));

        var recibidas = totalesDe(porEstado.get(EstadoCompra.RECIBIDA));
        var pendientes = totalesDe(porEstado.get(EstadoCompra.PENDIENTE));
        var canceladas = totalesDe(porEstado.get(EstadoCompra.CANCELADA));

        // R-04: canceladas se contabiliza aparte; ninguno de los dos gastos la financia.
        return new ResumenComprasResponse(new Periodo(fechaInicio, fechaFin),
                recibidas, pendientes, canceladas,
                recibidas.total(), pendientes.total());
    }

    /**
     * Resume las compras recibidas de un período agrupadas por proveedor (HU-15 §2).
     *
     * <p>El rango se valida igual que en {@link #resumen(LocalDate, LocalDate)}: un rango
     * invertido no tiene resultados posibles y responde 400 {@code RANGO_FECHAS_INVALIDO} antes
     * de tocar la base.
     *
     * <p>El repositorio devuelve solo las compras RECIBIDA —el gasto ejecutado—, ordenadas por
     * total descendente (CP-15), y ese orden se preserva en la respuesta. Cada fila recibe su
     * porcentaje sobre la suma global de los totales del período, a escala 2 con redondeo
     * HALF_UP (CP-16): la suma de los porcentajes es 100.00 con el residuo de redondeo
     * permitido, sin ajustar el último. Un proveedor sin recibidas en el rango no aparece; un
     * rango vacío responde la lista vacía, no un error.
     *
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return gasto por proveedor, ordenado por total descendente; vacía si no hay recibidas
     * @throws ParametroInvalidoException si el rango de fechas está invertido
     */
    @Transactional(readOnly = true)
    public List<GastoProveedorResponse> gastoPorProveedor(LocalDate fechaInicio,
                                                           LocalDate fechaFin) {
        if (fechaInicio != null && fechaFin != null && fechaInicio.isAfter(fechaFin)) {
            // Mismo código del contrato transversal que en listar y en resumen.
            throw new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                    "La fecha de inicio no puede ser posterior a la fecha de fin.");
        }

        var filas = compraRepository.gastoPorProveedor(fechaInicio, fechaFin);
        var sumaGlobal = filas.stream()
                .map(GastoProveedorFila::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return filas.stream()
                .map(fila -> new GastoProveedorResponse(
                        fila.proveedor(),
                        fila.cantidadCompras(),
                        fila.total(),
                        porcentajeDe(fila.total(), sumaGlobal)))
                .toList();
    }

    /**
     * Peso de un total sobre la suma global, a escala 2 con redondeo HALF_UP (CP-16).
     *
     * <p>Una suma global en cero solo cabe un período cuyas recibidas suman todos {@code 0.00}
     * —precios o cantidades a cero—: en ese caso el porcentaje de cada fila es {@code 0.00},
     * porque no hay nada sobre lo que calcular una fracción.
     */
    private static BigDecimal porcentajeDe(BigDecimal total, BigDecimal sumaGlobal) {
        if (sumaGlobal.signum() == 0) {
            return new BigDecimal("0.00");
        }
        return total.multiply(new BigDecimal(100))
                .divide(sumaGlobal, 2, RoundingMode.HALF_UP);
    }

    /**
     * Totales de un estado del resumen; un estado sin compras en el rango responde {@code 0} y
     * {@code 0.00} (CP-17), con la escala 2 del contrato monetario.
     */
    private static TotalesEstado totalesDe(ResumenEstadoFila fila) {
        return fila == null
                ? new TotalesEstado(0L, new BigDecimal("0.00"))
                : new TotalesEstado(fila.cantidad(), fila.total());
    }

    /**
     * Devuelve una compra por su identificador, con su proveedor y sus totales derivados.
     *
     * @param id identificador de la compra
     * @return la compra en la forma del contrato
     * @throws RecursoNoEncontradoException si la compra no existe
     */
    @Transactional(readOnly = true)
    public CompraResponse obtener(Integer id) {
        return compraRepository.buscarPorId(id)
                .map(CompraService::aResponse)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));
    }

    /**
     * Indica si una compra admite cambios mientras siga {@code PENDIENTE} (HU-13 R-04).
     *
     * <p>Es la consulta que HU-14 hace antes de tocar el detalle: una compra {@code RECIBIDA} ya
     * asentó su stock y una {@code CANCELADA} cerró su ciclo, y en ninguno de los dos casos se
     * admiten líneas nuevas.
     *
     * @param idCompra identificador de la compra
     * @return {@code true} solo si la compra existe y está {@code PENDIENTE}; una compra
     *         inexistente, {@code RECIBIDA} o {@code CANCELADA} devuelve {@code false} sin
     *         excepción
     */
    @Transactional(readOnly = true)
    public boolean esEditable(Integer idCompra) {
        return compraRepository.buscarPorId(idCompra)
                .map(compra -> compra.getEstado() == EstadoCompra.PENDIENTE)
                .orElse(false);
    }

    /**
     * Recibe una compra pasándola a {@code RECIBIDA} y sumando su mercancía al inventario
     * (HU-13 R-01, punto principal de entrada de stock del sistema).
     *
     * <p>Orden exacto de la operación, dentro de una única transacción:
     *
     * <ol>
     *   <li>La compra debe existir; si no, 404.</li>
     *   <li>La transición se valida con la máquina de {@link EstadoCompra} <strong>antes</strong>
     *       de tocar el inventario ({@code TRANSICION_INVALIDA}, 409, R-02/CP-11/CP-15): es lo
     *       que impide que una doble recepción doble el stock.</li>
     *   <li>Debe tener al menos una línea de detalle ({@code COMPRA_SIN_DETALLE}, 409, R-03,
     *       CP-14): recibir una compra vacía no sumaría nada y dejaría el estado en
     *       {@code RECIBIDA} sin efecto.</li>
     *   <li>Por cada línea se llama a {@link InventarioService#registrarEntrada} con tipo
     *       {@code ENTRADA_COMPRA} y origen {@code detalle_compra}/{@code idDetalle}; nunca hay
     *       un {@code UPDATE inventario} directo (R-01). Cada llamada genera su movimiento de
     *       kardex y sus saldos quedan en la respuesta.</li>
     *   <li>Se cambia el estado a {@code RECIBIDA}.</li>
     *   <li>Se relee la compra para montar el {@code totalCompra} de la respuesta.</li>
     * </ol>
     *
     * <p>Si una línea falla, la excepción se propaga sin atraparla: la transacción revierte las
     * entradas anteriores y el estado (R-01, CP-16). Una recepción parcial dejaría un inventario
     * que nadie puede explicar.
     *
     * @param id identificador de la compra a recibir
     * @return el estado final, el total y los movimientos de inventario generados
     * @throws RecursoNoEncontradoException si la compra no existe
     * @throws ConflictoNegocioException si la transición no está permitida o la compra no tiene
     *                                   líneas de detalle
     */
    @Transactional
    @Auditable(accion = "RECEPCION_COMPRA", tabla = "compras",
            detalle = "'Compra recibida: ' + #id")
    public RecepcionResponse recibir(Integer id) {
        var compra = compraRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));

        // Antes del inventario: una compra ya RECIBIDA no puede volver a sumar (CP-15).
        if (!compra.getEstado().puedeTransicionarA(EstadoCompra.RECIBIDA)) {
            throw new ConflictoNegocioException("TRANSICION_INVALIDA",
                    mensajeTransicion(compra.getEstado(), EstadoCompra.RECIBIDA));
        }

        if (compraRepository.contarDetalles(id) == 0) {
            throw new ConflictoNegocioException("COMPRA_SIN_DETALLE",
                    String.format("La compra con id %s no tiene líneas de detalle; no se puede "
                            + "recibir sin mercancía.", id));
        }

        var movimientos = new ArrayList<MovimientoGeneradoResponse>();
        for (var linea : compraRepository.listarDetalles(id)) {
            var saldos = inventarioService.registrarEntrada(linea.idMateriaPrima(),
                    linea.cantidad(), TipoMovimiento.ENTRADA_COMPRA, "detalle_compra",
                    linea.idDetalle(), "Compra #" + id);
            movimientos.add(new MovimientoGeneradoResponse(linea.nombreMateriaPrima(),
                    linea.cantidad(), saldos.anterior(), saldos.posterior()));
        }

        compraRepository.cambiarEstado(id, EstadoCompra.RECIBIDA);

        var total = compraRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id))
                .getTotalCompra();
        return new RecepcionResponse(id, EstadoCompra.RECIBIDA.name(), total, movimientos);
    }

    /**
     * Cancela una compra pasándola a {@code CANCELADA} desde {@code PENDIENTE} (HU-13 R-05).
     *
     * <p>{@code RECIBIDA} es terminal (R-02): cancelarla no es solo un salto prohibido por la
     * máquina de estados, sino que además debería devolver un stock que quizá ya se consumió
     * (CP-12). Por eso este método <strong>nunca</strong> llama a {@link InventarioService}
     * (CP-13): cancelar una compra pendiente no cambia el inventario porque nunca sumó nada.
     *
     * @param id identificador de la compra a cancelar
     * @return la compra releída de la base, con estado {@code CANCELADA}
     * @throws RecursoNoEncontradoException si la compra no existe (también si desaparece entre
     *                                      la escritura y la relectura)
     * @throws ConflictoNegocioException si la transición no está permitida
     */
    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO", tabla = "compras",
            detalle = "'Compra cancelada: ' + #id")
    public CompraResponse cancelar(Integer id) {
        var compra = compraRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));

        if (!compra.getEstado().puedeTransicionarA(EstadoCompra.CANCELADA)) {
            throw new ConflictoNegocioException("TRANSICION_INVALIDA",
                    mensajeTransicion(compra.getEstado(), EstadoCompra.CANCELADA));
        }

        compraRepository.cambiarEstado(id, EstadoCompra.CANCELADA);

        return compraRepository.buscarPorId(id)
                .map(CompraService::aResponse)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));
    }

    /**
     * Construye la excepción {@code TRANSICION_INVALIDA} lista para lanzar, sin lanzarla aquí.
     *
     * <p>La compra destino {@code PENDIENTE} no tiene método propio en el servicio porque es la
     * única transición que la máquina de {@link EstadoCompra} rechaza sin regla propia que
     * aplicar; el controlador (PR4) la ofrece igualmente en su {@code PATCH /estado} y su
     * {@code switch} decide con este método: si el destino no tiene handler, hace
     * {@code throw servicio.transicionInvalida(id, destino)}. Devolver la excepción en lugar de
     * lanzarla deja ese {@code throw} —y por tanto la decisión de responder 409— en el único
     * punto que conoce todos los destinos, sin duplicar la construcción del mensaje.
     *
     * @param id      identificador de la compra
     * @param destino estado al que se intentó transicionar
     * @return la excepción, ya construida con el mensaje del contrato §7, lista para lanzar
     * @throws RecursoNoEncontradoException si la compra no existe
     */
    @Transactional(readOnly = true)
    public ConflictoNegocioException transicionInvalida(Integer id, EstadoCompra destino) {
        var compra = compraRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la compra", id));
        return new ConflictoNegocioException("TRANSICION_INVALIDA",
                mensajeTransicion(compra.getEstado(), destino));
    }

    /**
     * Mensaje único de {@code TRANSICION_INVALIDA} (contrato §7), compartido por
     * {@link #recibir(Integer)}, {@link #cancelar(Integer)} y
     * {@link #transicionInvalida(Integer, EstadoCompra)} para que la misma regla se lea igual
     * venga del endpoint que venga.
     */
    private static String mensajeTransicion(EstadoCompra actual, EstadoCompra destino) {
        var permitidas = actual.transicionesValidas().stream()
                .map(EstadoCompra::name)
                .sorted()
                .collect(Collectors.joining(", "));
        var disponibles = permitidas.isEmpty()
                ? "no hay transiciones disponibles"
                : "las transiciones disponibles son: " + permitidas;
        return String.format("No se puede pasar de %s a %s. Desde %s %s.",
                actual, destino, actual, disponibles);
    }

    /**
     * Convierte una fila de {@link CompraRepository} en la respuesta del contrato.
     *
     * <p>{@code totalCompra} y {@code cantidadItems} llegan ya calculados de la base y
     * {@code editable} se deriva de la misma máquina de estados que valida la edición: solo una
     * compra {@code PENDIENTE} admite cambios.
     */
    private static CompraResponse aResponse(Compra compra) {
        return aResponse(compra, null);
    }

    /**
     * Versión de {@link #aResponse(Compra)} con la lista de líneas del modo
     * {@code incluirDetalle=true} (HU-15 R-06); en el resto de respuestas el detalle va en
     * {@code null} y el campo ni siquiera se serializa.
     */
    private static CompraResponse aResponse(Compra compra,
                                            List<DetalleCompraResponse> detalle) {
        return new CompraResponse(
                compra.getIdCompra(),
                new ProveedorRefResponse(compra.getIdProveedor(), compra.getNombreProveedor()),
                compra.getFecha(),
                compra.getEstado().name(),
                compra.getTotalCompra(),
                compra.getCantidadItems(),
                compra.getEstado() == EstadoCompra.PENDIENTE,
                detalle);
    }

    /**
     * Agrupa las filas planas de {@link CompraRepository#buscarConDetalle} en una respuesta por
     * compra (HU-15 R-06), preservando el orden de la primera aparición de cada una.
     *
     * <p>La agrupación es en memoria y sobre las filas de una única consulta: nunca se consulta
     * una compra por compra. Cada compra recibe su lista de líneas —la fila sin línea no aporta
     * nada al grupo, que queda como lista vacía— y su cabecera se lee de la primera fila de ese
     * grupo, cuyas columnas de cabecera se repiten idénticas en todas las filas de la compra.
     */
    private static List<CompraResponse> agruparConDetalle(List<CompraDetalleFila> filas) {
        var compras = new LinkedHashMap<Integer, Compra>();
        var detalles = new LinkedHashMap<Integer, List<DetalleCompraResponse>>();
        for (var fila : filas) {
            compras.putIfAbsent(fila.idCompra(), cabeceraDe(fila));
            var lineas = detalles.computeIfAbsent(fila.idCompra(), id -> new ArrayList<>());
            if (fila.conDetalle()) {
                lineas.add(aDetalle(fila));
            }
        }

        var respuestas = new ArrayList<CompraResponse>(compras.size());
        for (var compra : compras.values()) {
            respuestas.add(aResponse(compra, detalles.get(compra.getIdCompra())));
        }
        return respuestas;
    }

    /**
     * Reconstruye la cabecera de una compra a partir de la primera fila que la trajo: las
     * columnas de cabecera son las mismas en todas las filas de su grupo.
     */
    private static Compra cabeceraDe(CompraDetalleFila fila) {
        var compra = new Compra();
        compra.setIdCompra(fila.idCompra());
        compra.setIdProveedor(fila.idProveedor());
        compra.setFecha(fila.fecha());
        compra.setEstado(fila.estado());
        compra.setNombreProveedor(fila.nombreProveedor());
        compra.setTotalCompra(fila.totalCompra());
        compra.setCantidadItems(fila.cantidadItems());
        return compra;
    }

    /**
     * Convierte la parte de detalle de una fila plana en la línea del contrato de HU-14.
     *
     * <p>El subtotal es {@code cantidad * precioUnitario} a escala 2 con {@code HALF_UP}
     * (R-03/R-07), nunca una cifra que entregue el cliente. {@code totalCompra} va en
     * {@code null}: en un listado el total agregado viaja una sola vez en la cabecera de su
     * compra, y un {@code null} ni siquiera se serializa. El comparativo de HU-14 R-04 sale de
     * la misma fila: {@code costoReferencia} es {@code materias_primas.costo_unitario} vigente y
     * {@code diferenciaCosto} es el precio pagado menos ese costo; sin costo, ambos en
     * {@code null}.
     */
    private static DetalleCompraResponse aDetalle(CompraDetalleFila fila) {
        return new DetalleCompraResponse(
                fila.idDetalleCompra(),
                new MateriaPrimaRefResponse(fila.idMateriaPrima(), fila.nombreMateriaPrima(),
                        fila.abreviaturaUnidad()),
                fila.cantidad(),
                fila.precioUnitario(),
                fila.cantidad().multiply(fila.precioUnitario()).setScale(2, RoundingMode.HALF_UP),
                null,
                fila.costoReferencia(),
                diferenciaDeCosto(fila.precioUnitario(), fila.costoReferencia()));
    }

    /**
     * Comparativo de HU-14 R-04: lo pagado menos el costo de referencia, a escala 2 con
     * {@code HALF_UP}. Sin costo de referencia no hay comparativo posible: {@code null} y el
     * campo no se serializa.
     */
    private static BigDecimal diferenciaDeCosto(BigDecimal precioUnitario,
                                                BigDecimal costoReferencia) {
        if (costoReferencia == null) {
            return null;
        }
        return precioUnitario.subtract(costoReferencia).setScale(2, RoundingMode.HALF_UP);
    }
}
