package co.edu.corposucre.productionfood.pedido;

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

import co.edu.corposucre.productionfood.cliente.ClienteRepository;
import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.inventario.TipoMovimientoPt;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CambiarEstadoPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.CrearPedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.PedidoClienteRefResponse;
import co.edu.corposucre.productionfood.pedido.dto.PedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.ProductoVendidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.ResumenPedidosResponse;
import co.edu.corposucre.productionfood.pedido.dto.VentasClienteResponse;

/**
 * Ciclo de vida de la cabecera de un pedido (HU-16): alta, edición, consulta, paginación y
 * cambio de estado.
 *
 * <p>Este componente cubre la cabecera y sus transiciones de estado. El detalle del pedido es de
 * HU-17; aquí solo se lee, a través del kardex, al cancelar para devolver el stock (R-04).
 *
 * <p>Los códigos de error son los de la especificación §5 y no el genérico
 * {@code VALIDACION_FALLIDA}: una regla de negocio violada responde 409
 * ({@link ConflictoNegocioException}) y un dato de entrada fuera de rango, 400
 * ({@link ParametroInvalidoException}).
 *
 * <p>La fecha del pedido nunca se escribe desde Java (R-01): el {@code INSERT} la omite y la
 * columna {@code NOT NULL DEFAULT (curdate())} la pone el servidor de base de datos.
 */
@Service
public class PedidoService {

    /**
     * Lista blanca de ordenamiento del listado: traduce el campo que pide el cliente al prefijo
     * de columna SQL de {@code pedidos} (alias {@code p}) o, para {@code total}, al alias calculado
     * {@code total_pedido}. {@code PageRequest.of} rechaza
     * cualquier campo que no esté aquí, y la columna resuelta es la única que llega al
     * repositorio, de modo que el orden nunca es SQL arbitrario.
     */
    private static final Map<String, String> ORDEN = Map.of(
            "fechaPedido", "p.fecha_pedido",
            "fechaEntrega", "p.fecha_entrega",
            "estado", "p.estado",
            // Columna calculada (HU-25): se ordena por el alias de la subconsulta del SELECT.
            "total", "total_pedido",
            "id", "p.id_pedido");

    private static final BigDecimal CERO = new BigDecimal("0.00");

    private static final String ORDEN_POR_DEFECTO = "fechaPedido,desc";

    /**
     * Tope de pedidos por página cuando el listado incluye el detalle (HU-25 R-06): cargar las
     * líneas de una página grande es costoso, así que {@code size} se recorta a este valor.
     */
    static final int TAMANO_MAXIMO_CON_DETALLE = 20;

    private final PedidoRepository pedidoRepository;

    private final ClienteRepository clienteRepository;

    private final InventarioPtService inventarioPtService;

    public PedidoService(PedidoRepository pedidoRepository, ClienteRepository clienteRepository,
                         InventarioPtService inventarioPtService) {
        this.pedidoRepository = pedidoRepository;
        this.clienteRepository = clienteRepository;
        this.inventarioPtService = inventarioPtService;
    }

    /**
     * Registra un pedido nuevo, siempre en estado {@code PENDIENTE} (CA-04).
     *
     * <p>Validaciones, en el orden de la especificación:
     *
     * <ol>
     *   <li><strong>Cliente</strong>: debe existir ({@code CLIENTE_INEXISTENTE}, 409) y estar
     *       activo ({@code CLIENTE_INACTIVO}, 409, CA-01). La actividad solo se exige al crear
     *       (R-07): un pedido ya registrado sigue su curso aunque el cliente se desactive.</li>
     *   <li><strong>Fecha de entrega</strong>: opcional; si se informa no puede ser anterior a
     *       hoy ({@code FECHA_ENTREGA_INVALIDA}, 400). Hoy y cualquier fecha futura se admiten
     *       (R-06).</li>
     * </ol>
     *
     * <p>El pedido nace {@code PENDIENTE} y sin líneas de detalle: {@code totalPedido} responde
     * {@code 0.00} y {@code cantidadItems} {@code 0} hasta que HU-17 agregue el detalle. La
     * fecha del pedido no se envía: la pone la base con su {@code DEFAULT (curdate())} (R-01).
     *
     * <p>El {@code INSERT} de {@link PedidoRepository#insertar(Pedido)} escribe cliente, estado y
     * fecha de entrega en una sola sentencia; {@code fecha_pedido} la aporta la base (R-01).
     *
     * @param req cliente obligatorio y fecha de entrega opcional del pedido
     * @return el pedido releído de la base, con total {@code 0.00}, cero ítems y
     *         {@code editable} a {@code true}
     * @throws ConflictoNegocioException si el cliente no existe o está inactivo
     * @throws ParametroInvalidoException si la fecha de entrega es anterior a hoy
     */
    @Transactional
    // En la expresión de detalle los accesores de record se leen como propiedades
    // (#resultado.idPedido) y no como llamadas: el aspecto no resuelve llamadas de record.
    @Auditable(accion = "CREAR", tabla = "pedidos",
            detalle = "'Pedido creado: ' + #resultado.idPedido")
    public PedidoResponse crear(CrearPedidoRequest req) {
        validarCliente(req.idCliente());
        validarFechaEntrega(req.fechaEntrega());

        var pedido = new Pedido();
        pedido.setIdCliente(req.idCliente());
        pedido.setFechaEntrega(req.fechaEntrega());
        pedido.setEstado(EstadoPedido.PENDIENTE);
        var idPedido = pedidoRepository.insertar(pedido);

        return pedidoRepository.buscarPorId(idPedido)
                .map(p -> aResponse(p, null))
                .orElseThrow(() -> new RecursoNoEncontradoException("el pedido", idPedido));
    }

    /**
     * Edita el cliente y la fecha de entrega de un pedido mientras siga {@code PENDIENTE}
     * (R-05).
     *
     * <p>El estado no se toca por aquí: tiene su endpoint propio con la máquina de transiciones
     * de {@link EstadoPedido}. Las validaciones, en orden:
     *
     * <ol>
     *   <li>El pedido debe existir; si no, 404.</li>
     *   <li>Debe estar {@code PENDIENTE} ({@code PEDIDO_NO_EDITABLE}, 409): una vez en
     *       preparación, cambiar el cliente o la entrega no tiene sentido operativo.</li>
     *   <li>El cliente debe existir ({@code CLIENTE_INEXISTENTE}, 409) y estar activo
     *       ({@code CLIENTE_INACTIVO}, 409). La edición sigue el criterio de compras: el DTO
     *       trae {@code idCliente} obligatorio y R-07 solo exime la comprobación de actividad en
     *       pedidos ya existentes, no la de existencia.</li>
     *   <li>La fecha de entrega, si se informa, no puede ser anterior a hoy
     *       ({@code FECHA_ENTREGA_INVALIDA}, 400); {@code null} la deja vacía.</li>
     * </ol>
     *
     * <p>La respuesta se relee de la base tras la escritura para que {@code totalPedido} y
     * {@code cantidadItems}, derivados de {@code detalle_pedido}, sean los de la fila.
     *
     * @param id  identificador del pedido a editar
     * @param req cliente y fecha de entrega nuevos
     * @return el pedido releído de la base, con sus totales derivados
     * @throws RecursoNoEncontradoException si el pedido no existe (también si desaparece entre la
     *                                      escritura y la relectura)
     * @throws ConflictoNegocioException si el pedido no está {@code PENDIENTE} o el cliente no
     *                                   existe o está inactivo
     * @throws ParametroInvalidoException si la fecha de entrega es anterior a hoy
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "pedidos",
            detalle = "'Pedido editado: ' + #resultado.idPedido")
    public PedidoResponse actualizar(Integer id, ActualizarPedidoRequest req) {
        var pedido = pedidoRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el pedido", id));

        if (pedido.getEstado() != EstadoPedido.PENDIENTE) {
            throw new ConflictoNegocioException("PEDIDO_NO_EDITABLE",
                    String.format("El pedido con id %s no es editable: solo los pedidos PENDIENTE "
                            + "admiten cambios en su cabecera.", id));
        }

        validarCliente(req.idCliente());
        validarFechaEntrega(req.fechaEntrega());

        var cambios = new Pedido();
        cambios.setIdPedido(id);
        cambios.setIdCliente(req.idCliente());
        cambios.setFechaEntrega(req.fechaEntrega());
        pedidoRepository.actualizar(cambios);

        return pedidoRepository.buscarPorId(id)
                .map(p -> aResponse(p, null))
                .orElseThrow(() -> new RecursoNoEncontradoException("el pedido", id));
    }

    /**
     * Lista pedidos de forma paginada y filtrada, con su cliente y sus totales derivados.
     *
     * <p>El rango de fechas se valida antes de tocar la base: un rango invertido no tiene
     * resultados posibles y responder 400 explica el error mejor que una página vacía.
     *
     * <p>Los cuatro filtros son opcionales y viajan tal cual al repositorio; un valor nulo no
     * restringe. El total sale de {@link PedidoRepository#contar} y no del tamaño de la página:
     * el listado puede traer una página y haber miles.
     *
     * @param idCliente  cliente a filtrar, o {@code null} sin filtrar
     * @param estado     estado a filtrar, o {@code null} sin filtrar
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin   último día incluido del rango, o {@code null} sin límite superior
     * @param page       página solicitada, desde 0
     * @param size       tamaño de página, entre 1 y 100
     * @param sort       orden "campo,direccion", con campos de la lista blanca
     *                   {@code fechaPedido}, {@code fechaEntrega}, {@code estado}, {@code total}
     *                   e {@code id};
     *                   por defecto "fechaPedido,desc"
     * @return página de pedidos con su cliente y sus totales
     * @throws ParametroInvalidoException si el rango de fechas está invertido, o la paginación o
     *                                    el campo de orden son inválidos
     * @throws ClienteInexistenteFiltroException si {@code idCliente} no existe (404)
     */
    @Transactional(readOnly = true)
    public PageResponse<PedidoResponse> listar(Integer idCliente, EstadoPedido estado,
                                               LocalDate fechaInicio, LocalDate fechaFin,
                                               int page, int size, String sort,
                                               boolean incluirDetalle) {
        validarRango(fechaInicio, fechaFin);

        // HU-25: solo existencia, no actividad; un cliente desactivado conserva su historial.
        // Los caminos de crear y actualizar siguen respondiendo 409 y no pasan por aquí.
        if (idCliente != null && !clienteRepository.existsById(idCliente)) {
            throw new ClienteInexistenteFiltroException(idCliente);
        }

        // CP-10: el recorte va antes de PageRequest.of para que un size sobre la cota de detalle
        // se recorte y no responda 400; un size igual a 0 o negativo sigue rechazándose allí.
        var sizeEfectivo = incluirDetalle ? Math.min(size, TAMANO_MAXIMO_CON_DETALLE) : size;
        var orden = PageRequest.of(page, sizeEfectivo, sort, ORDEN, ORDEN_POR_DEFECTO);
        // El offset se calcula en long: page no tiene cota superior, así que page * size
        // desbordaría int a un valor negativo y el OFFSET del SQL fallaría.
        long offset = (long) orden.page() * orden.size();

        List<PedidoResponse> contenido;
        if (incluirDetalle) {
            var filas = pedidoRepository.buscarConDetalle(idCliente, estado, fechaInicio,
                    fechaFin, orden.propiedad(), orden.ascendente(), orden.size(), offset);
            contenido = agruparConDetalle(filas);
        } else {
            var filas = pedidoRepository.buscar(idCliente, estado, fechaInicio, fechaFin,
                    orden.propiedad(), orden.ascendente(), orden.size(), offset);
            contenido = filas.stream().map(f -> aResponse(f, null)).toList();
        }
        var total = pedidoRepository.contar(idCliente, estado, fechaInicio, fechaFin);

        return PageResponse.of(contenido, orden.page(), orden.size(), total);
    }

    /**
     * Devuelve un pedido por su identificador, con su cliente y sus totales derivados.
     *
     * @param id identificador del pedido
     * @return el pedido en la forma del contrato
     * @throws RecursoNoEncontradoException si el pedido no existe
     */
    @Transactional(readOnly = true)
    public PedidoResponse obtener(Integer id) {
        return pedidoRepository.buscarPorId(id)
                .map(p -> aResponse(p, null))
                .orElseThrow(() -> new RecursoNoEncontradoException("el pedido", id));
    }

    /**
     * Cambia el estado de un pedido validando la máquina de transiciones (R-02).
     *
     * <p>Orden exacto de la operación, dentro de una única transacción:
     *
     * <ol>
     *   <li>El pedido debe existir; si no, 404.</li>
     *   <li>La transición se valida con la máquina de {@link EstadoPedido} <strong>antes</strong>
     *       de tocar el inventario ({@code TRANSICION_INVALIDA}, 409): un salto prohibido —por
     *       ejemplo {@code ENTREGADO} a {@code CANCELADO}— no debe devolver stock.</li>
     *   <li>Si el destino es {@code CANCELADO}, por cada línea devuelta por
     *       {@link PedidoRepository#lineasParaDevolucion} se repone su cantidad al lote con
     *       {@link InventarioPtService#registrarEntrada}, tipo {@code ENTRADA_DEVOLUCION} y
     *       origen {@code pedidos}/{@code idPedido} (R-04, R-10 de HU-17). Un pedido sin líneas
     *       vendidas no genera ningún movimiento (CP-18).</li>
     *   <li>Se cambia el estado y se relee el pedido para montar la respuesta.</li>
     * </ol>
     *
     * <p>Si una devolución falla, la excepción se propaga sin atraparla: la transacción revierte
     * las entradas anteriores y el estado. Una cancelación a medias dejaría un kardex que nadie
     * puede explicar.
     *
     * @param id  identificador del pedido
     * @param req estado destino
     * @return el pedido releído de la base con su estado nuevo
     * @throws RecursoNoEncontradoException si el pedido no existe (también si desaparece entre la
     *                                      escritura y la relectura)
     * @throws ConflictoNegocioException si la transición no está permitida
     */
    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO", tabla = "pedidos",
            detalle = "'Pedido ' + #id + ' a ' + #req.estado")
    public PedidoResponse cambiarEstado(Integer id, CambiarEstadoPedidoRequest req) {
        var pedido = pedidoRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el pedido", id));

        // Antes del inventario: un pedido terminal no puede volver a mover stock.
        if (!pedido.getEstado().puedeTransicionarA(req.estado())) {
            throw transicionInvalida(id, req.estado());
        }

        // El UPDATE va antes de devolver stock y condicionado al estado leído: si otra
        // transacción cambió el pedido entre la lectura y la escritura, afecta cero filas y se
        // aborta sin tocar inventario. Así una doble cancelación concurrente no repone dos veces.
        var afectadas = pedidoRepository.cambiarEstado(id, pedido.getEstado(), req.estado());
        if (afectadas == 0) {
            throw transicionInvalida(id, req.estado());
        }

        if (req.estado() == EstadoPedido.CANCELADO) {
            for (var linea : pedidoRepository.lineasParaDevolucion(id)) {
                inventarioPtService.registrarEntrada(linea.idLote(), linea.cantidad(),
                        TipoMovimientoPt.ENTRADA_DEVOLUCION, "pedidos", id,
                        "Devolucion por cancelacion del pedido " + id);
            }
        }

        return pedidoRepository.buscarPorId(id)
                .map(p -> aResponse(p, null))
                .orElseThrow(() -> new RecursoNoEncontradoException("el pedido", id));
    }

    /**
     * Construye la excepción {@code TRANSICION_INVALIDA} lista para lanzar, sin lanzarla aquí.
     *
     * <p>Sigue el patrón de {@code CompraService.transicionInvalida}: devolver la excepción deja
     * el {@code throw} —y por tanto la decisión de responder 409— en quien conoce todos los
     * destinos, sin duplicar la construcción del mensaje.
     *
     * @param id      identificador del pedido
     * @param destino estado al que se intentó transicionar
     * @return la excepción, ya construida con el mensaje del contrato §7, lista para lanzar
     * @throws RecursoNoEncontradoException si el pedido no existe
     */
    @Transactional(readOnly = true)
    public ConflictoNegocioException transicionInvalida(Integer id, EstadoPedido destino) {
        var pedido = pedidoRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("el pedido", id));
        return new ConflictoNegocioException("TRANSICION_INVALIDA",
                mensajeTransicion(pedido.getEstado(), destino));
    }

    /**
     * Mensaje único de {@code TRANSICION_INVALIDA} (contrato §7), compartido por
     * {@link #cambiarEstado(Integer, CambiarEstadoPedidoRequest)} y
     * {@link #transicionInvalida(Integer, EstadoPedido)} para que la misma regla se lea igual
     * venga del endpoint que venga.
     */
    private static String mensajeTransicion(EstadoPedido actual, EstadoPedido destino) {
        var permitidas = actual.transicionesValidas().stream()
                .map(EstadoPedido::name)
                .sorted()
                .collect(Collectors.joining(", "));
        var disponibles = permitidas.isEmpty()
                ? "no hay transiciones disponibles"
                : "las transiciones disponibles son: " + permitidas;
        return String.format("No se puede pasar de %s a %s. Desde %s %s.",
                actual, destino, actual, disponibles);
    }

    /**
     * Indica si un pedido admite cambios mientras siga {@code PENDIENTE} (R-05).
     *
     * <p>Es la consulta que HU-17 hace antes de tocar el detalle: un pedido en preparación ya
     * comprometió su mercancía y uno terminal cerró su ciclo, así que ninguno admite líneas
     * nuevas.
     *
     * @param id identificador del pedido
     * @return {@code true} solo si el pedido existe y está {@code PENDIENTE}; un pedido
     *         inexistente o en cualquier otro estado devuelve {@code false} sin excepción
     */
    @Transactional(readOnly = true)
    public boolean esEditable(Integer id) {
        return pedidoRepository.buscarPorId(id)
                .map(pedido -> pedido.getEstado() == EstadoPedido.PENDIENTE)
                .orElse(false);
    }

    /**
     * Valida que el cliente exista y esté activo (CA-01), con la doble comprobación de
     * {@code CompraService.crear}: primero existencia ({@code CLIENTE_INEXISTENTE}) y después
     * actividad ({@code CLIENTE_INACTIVO}), ambos 409.
     */
    private void validarCliente(Integer idCliente) {
        if (!clienteRepository.existsById(idCliente)) {
            throw new ConflictoNegocioException("CLIENTE_INEXISTENTE",
                    String.format("El cliente con id %s no existe.", idCliente));
        }
        if (!clienteRepository.existeActivo(idCliente)) {
            throw new ConflictoNegocioException("CLIENTE_INACTIVO",
                    String.format("El cliente con id %s está inactivo; no puede recibir pedidos.",
                            idCliente));
        }
    }

    /**
     * Valida la fecha de entrega (R-06): opcional, pero si viene informada no puede ser anterior
     * a hoy. Hoy y cualquier fecha futura se admiten; {@code null} no restringe.
     */
    private static void validarFechaEntrega(LocalDate fechaEntrega) {
        if (fechaEntrega != null && fechaEntrega.isBefore(LocalDate.now())) {
            throw new ParametroInvalidoException("FECHA_ENTREGA_INVALIDA",
                    "La fecha de entrega no puede ser anterior a la fecha del pedido.");
        }
    }

    /**
     * Convierte una fila de {@link PedidoRepository} en la respuesta del contrato.
     *
     * <p>{@code totalPedido} y {@code cantidadItems} llegan ya calculados de la base y
     * {@code editable} se deriva de la máquina de estados: solo un pedido {@code PENDIENTE}
     * admite cambios.
     */
    private static PedidoResponse aResponse(Pedido pedido, List<DetallePedidoResponse> detalle) {
        return new PedidoResponse(
                pedido.getIdPedido(),
                new PedidoClienteRefResponse(pedido.getIdCliente(), pedido.getNombreCliente()),
                pedido.getFechaPedido(),
                pedido.getFechaEntrega(),
                pedido.getEstado().name(),
                pedido.getTotalPedido(),
                pedido.getTotalBruto(),
                pedido.getDevoluciones(),
                pedido.getTotalNeto(),
                pedido.getCantidadItems(),
                pedido.getEstado() == EstadoPedido.PENDIENTE,
                detalle);
    }

    /**
     * Agrupa las filas planas del listado con detalle por pedido, preservando el orden de la
     * página (HU-25 R-06).
     *
     * <p>El {@code LinkedHashMap} conserva el orden de la primera aparición de cada pedido, que
     * es el de la consulta; las líneas de cada uno quedan en el orden del {@code ORDER BY}. Una
     * fila sin línea crea el pedido con la lista vacía, no la omite.
     */
    private static List<PedidoResponse> agruparConDetalle(List<PedidoDetalleFila> filas) {
        var pedidos = new LinkedHashMap<Integer, Pedido>();
        var detalles = new LinkedHashMap<Integer, List<DetallePedidoResponse>>();
        for (var fila : filas) {
            var id = fila.pedido().getIdPedido();
            pedidos.putIfAbsent(id, fila.pedido());
            var lineas = detalles.computeIfAbsent(id, k -> new ArrayList<>());
            if (fila.conDetalle()) {
                lineas.add(aLinea(fila.linea()));
            }
        }

        var respuestas = new ArrayList<PedidoResponse>(pedidos.size());
        for (var pedido : pedidos.values()) {
            respuestas.add(aResponse(pedido, detalles.get(pedido.getIdPedido())));
        }
        return respuestas;
    }

    /**
     * Convierte una línea del listado en la misma forma que expone
     * {@code GET /pedidos/{id}/detalles}, salvo lotes y total (van en {@code null}).
     *
     * <p>El subtotal es {@code cantidad * precioUnitario} a escala 2 con {@code HALF_UP}, igual
     * que en {@code DetallePedidoService}: la cifra oficial nunca la entrega el cliente.
     */
    private static DetallePedidoResponse aLinea(DetallePedido linea) {
        return new DetallePedidoResponse(
                linea.idDetallePedido(),
                new ProductoRefResponse(linea.idProducto(), linea.nombreProducto(), linea.unidad()),
                linea.cantidad().setScale(2, RoundingMode.HALF_UP),
                linea.precioUnitario().setScale(2, RoundingMode.HALF_UP),
                linea.cantidad().multiply(linea.precioUnitario())
                        .setScale(2, RoundingMode.HALF_UP),
                null,
                null);
    }

    /**
     * Resume los pedidos de un período por situación de venta (HU-25 R-03, R-04).
     *
     * <p>El rango se valida antes de tocar la base. Los estados {@code PENDIENTE} y
     * {@code EN_PREPARACION} se reúnen en {@code enCurso}; un estado sin pedidos responde
     * {@code 0} y {@code 0.00}.
     *
     * <p>Decisión sobre las cifras: la venta bruta es solo el total de los {@code ENTREGADO}
     * (venta consumada), sin sumar lo {@code enCurso} (venta comprometida) —mismo patrón que
     * {@code gastoEjecutado}/{@code gastoComprometido} de HU-15—, porque sumarlos daría una cifra
     * que no corresponde ni a lo facturado ni a lo prometido. Las devoluciones son las
     * {@code APROBADA} de los pedidos entregados, y la venta neta es bruta menos devoluciones, de
     * modo que con el ejemplo de la especificación (1.840.000 entregados, 62.000 devueltos) la
     * neta es 1.778.000. El ticket promedio es la bruta entre los entregados, a escala 2 con
     * HALF_UP, y {@code null} si no hubo entregados.
     *
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return el resumen del período; {@code periodo} es {@code null} si no se envió ninguna fecha
     * @throws ParametroInvalidoException si el rango de fechas está invertido
     */
    @Transactional(readOnly = true)
    public ResumenPedidosResponse resumen(LocalDate fechaInicio, LocalDate fechaFin) {
        validarRango(fechaInicio, fechaFin);

        var porEstado = pedidoRepository.resumenPorPeriodo(fechaInicio, fechaFin).stream()
                .collect(Collectors.toMap(ResumenPedidoEstadoFila::estado, fila -> fila));

        var entregados = totalesDe(porEstado.get(EstadoPedido.ENTREGADO));
        var enCurso = sumar(totalesDe(porEstado.get(EstadoPedido.PENDIENTE)),
                totalesDe(porEstado.get(EstadoPedido.EN_PREPARACION)));
        var cancelados = totalesDe(porEstado.get(EstadoPedido.CANCELADO));

        // R-03: la venta es lo entregado; R-04: solo descuentan las devoluciones de esos pedidos.
        var ventaBruta = entregados.total();
        var entregadosFila = porEstado.get(EstadoPedido.ENTREGADO);
        var devoluciones = entregadosFila == null
                ? CERO : entregadosFila.devoluciones();
        var ventaNeta = ventaBruta.subtract(devoluciones);
        var ticketPromedio = entregados.cantidad() == 0
                ? null
                : ventaBruta.divide(BigDecimal.valueOf(entregados.cantidad()), 2,
                        RoundingMode.HALF_UP);

        var periodo = fechaInicio == null && fechaFin == null
                ? null : new ResumenPedidosResponse.Periodo(fechaInicio, fechaFin);
        return new ResumenPedidosResponse(periodo, entregados, enCurso, cancelados,
                ventaBruta, devoluciones, ventaNeta, ticketPromedio);
    }

    /**
     * Ventas de un período agrupadas por cliente (HU-25 R-03).
     *
     * <p>El rango se valida antes de tocar la base. El repositorio devuelve solo los pedidos no
     * cancelados, ordenados por total descendente, y ese orden se preserva. Cada fila recibe su
     * porcentaje sobre la suma de los totales listados, a escala 2 con redondeo HALF_UP: la suma
     * es 100.00 con el residuo de redondeo permitido, sin ajustar el último. Con suma cero el
     * porcentaje de cada fila es {@code 0.00}. Un rango vacío responde la lista vacía.
     *
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return ventas por cliente, ordenadas por total descendente
     * @throws ParametroInvalidoException si el rango de fechas está invertido
     */
    @Transactional(readOnly = true)
    public List<VentasClienteResponse> ventasPorCliente(LocalDate fechaInicio,
                                                        LocalDate fechaFin) {
        validarRango(fechaInicio, fechaFin);

        var filas = pedidoRepository.ventasPorCliente(fechaInicio, fechaFin);
        var sumaGlobal = filas.stream()
                .map(VentaClienteFila::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return filas.stream()
                .map(fila -> new VentasClienteResponse(
                        new PedidoClienteRefResponse(fila.idCliente(), fila.cliente()),
                        fila.cantidadPedidos(),
                        fila.total(),
                        porcentajeDe(fila.total(), sumaGlobal)))
                .toList();
    }

    /**
     * Ranking de productos más vendidos de un período (HU-25 R-07).
     *
     * <p>El rango se valida antes de tocar la base. El orden es el del repositorio: unidades
     * vendidas descendente, no importe. Solo cuentan los pedidos {@code ENTREGADO}.
     *
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return productos de más a menos unidades vendidas
     * @throws ParametroInvalidoException si el rango de fechas está invertido
     */
    @Transactional(readOnly = true)
    public List<ProductoVendidoResponse> productosMasVendidos(LocalDate fechaInicio,
                                                              LocalDate fechaFin) {
        validarRango(fechaInicio, fechaFin);

        return pedidoRepository.productosMasVendidos(fechaInicio, fechaFin).stream()
                .map(fila -> new ProductoVendidoResponse(fila.producto(),
                        fila.unidadesVendidas(), fila.importe(), fila.pedidos()))
                .toList();
    }

    /**
     * Rechaza un rango de fechas invertido, con el código del contrato transversal
     * (04-CONTRATO-API.md §4) y no el genérico {@code PARAMETRO_INVALIDO}: el frontend distingue
     * el rango invertido de un query mal formado. Un rango de un solo día o con un límite nulo es
     * válido.
     */
    private static void validarRango(LocalDate fechaInicio, LocalDate fechaFin) {
        if (fechaInicio != null && fechaFin != null && fechaInicio.isAfter(fechaFin)) {
            throw new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                    "La fecha de inicio no puede ser posterior a la fecha de fin.");
        }
    }

    /** Peso de un total sobre la suma global, a escala 2 con redondeo HALF_UP. */
    private static BigDecimal porcentajeDe(BigDecimal total, BigDecimal sumaGlobal) {
        if (sumaGlobal.signum() == 0) {
            return CERO;
        }
        return total.multiply(new BigDecimal(100))
                .divide(sumaGlobal, 2, RoundingMode.HALF_UP);
    }

    /** Totales de un estado del resumen; sin pedidos en el rango responde {@code 0}/{@code 0.00}. */
    private static ResumenPedidosResponse.TotalesEstado totalesDe(ResumenPedidoEstadoFila fila) {
        return fila == null
                ? new ResumenPedidosResponse.TotalesEstado(0L, CERO)
                : new ResumenPedidosResponse.TotalesEstado(fila.cantidad(), fila.total());
    }

    private static ResumenPedidosResponse.TotalesEstado sumar(
            ResumenPedidosResponse.TotalesEstado a, ResumenPedidosResponse.TotalesEstado b) {
        return new ResumenPedidosResponse.TotalesEstado(a.cantidad() + b.cantidad(),
                a.total().add(b.total()));
    }
}
