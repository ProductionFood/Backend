package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
import co.edu.corposucre.productionfood.compra.dto.MovimientoGeneradoResponse;
import co.edu.corposucre.productionfood.compra.dto.ProveedorRefResponse;
import co.edu.corposucre.productionfood.compra.dto.RecepcionResponse;
import co.edu.corposucre.productionfood.compra.dto.ResumenComprasResponse;
import co.edu.corposucre.productionfood.compra.dto.ResumenComprasResponse.Periodo;
import co.edu.corposucre.productionfood.compra.dto.ResumenComprasResponse.TotalesEstado;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.inventario.TipoMovimiento;
import co.edu.corposucre.productionfood.proveedor.ProveedorRepository;

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
     */
    private static final Map<String, String> ORDEN = Map.of(
            "fecha", "c.fecha", "estado", "c.estado", "id", "c.id_compra");

    private static final String ORDEN_POR_DEFECTO = "fecha,desc";

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
                fecha, estado.name(), new BigDecimal("0.00"), 0, true);
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
     *
     * <p>Los cuatro filtros son opcionales y viajan tal cual al repositorio; un valor nulo no
     * restringe. El total sale de {@link CompraRepository#contar} y no del tamaño de la página:
     * el listado puede traer una página y haber miles.
     *
     * @param idProveedor proveedor a filtrar, o {@code null} sin filtrar
     * @param estado      estado a filtrar, o {@code null} sin filtrar
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @param page        página solicitada, desde 0
     * @param size        tamaño de página, entre 1 y 100
     * @param sort        orden "campo,direccion"; por defecto "fecha,desc"
     * @return página de compras con su proveedor y sus totales
     * @throws ParametroInvalidoException si el rango de fechas está invertido, o la paginación o
     *                                    el campo de orden son inválidos
     */
    @Transactional(readOnly = true)
    public PageResponse<CompraResponse> listar(Integer idProveedor, EstadoCompra estado,
                                               LocalDate fechaInicio, LocalDate fechaFin,
                                               int page, int size, String sort) {
        if (fechaInicio != null && fechaFin != null && fechaInicio.isAfter(fechaFin)) {
            // Código del contrato transversal (04-CONTRATO-API.md §4), no el genérico
            // PARAMETRO_INVALIDO: el frontend distingue el rango invertido de un query mal formado.
            throw new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                    "La fecha de inicio no puede ser posterior a la fecha de fin.");
        }

        var orden = PageRequest.of(page, size, sort, ORDEN, ORDEN_POR_DEFECTO);
        // El offset se calcula en long: page no tiene cota superior, así que page * size
        // desbordaría int a un valor negativo y el OFFSET del SQL fallaría.
        long offset = (long) orden.page() * orden.size();
        var filas = compraRepository.buscar(idProveedor, estado, fechaInicio, fechaFin,
                orden.propiedad(), orden.ascendente(), orden.size(), offset);
        var total = compraRepository.contar(idProveedor, estado, fechaInicio, fechaFin);

        return PageResponse.of(
                filas.stream().map(CompraService::aResponse).toList(),
                orden.page(), orden.size(), total);
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
        return new CompraResponse(
                compra.getIdCompra(),
                new ProveedorRefResponse(compra.getIdProveedor(), compra.getNombreProveedor()),
                compra.getFecha(),
                compra.getEstado().name(),
                compra.getTotalCompra(),
                compra.getCantidadItems(),
                compra.getEstado() == EstadoCompra.PENDIENTE);
    }
}
