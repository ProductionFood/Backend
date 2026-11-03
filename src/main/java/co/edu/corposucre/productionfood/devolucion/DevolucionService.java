package co.edu.corposucre.productionfood.devolucion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.error.ReglaDeNegocioException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.devolucion.dto.AprobacionResponse;
import co.edu.corposucre.productionfood.devolucion.dto.CambiarEstadoDevolucionRequest;
import co.edu.corposucre.productionfood.devolucion.dto.CrearDevolucionRequest;
import co.edu.corposucre.productionfood.devolucion.dto.DevolucionResponse;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.inventario.LoteConsumido;
import co.edu.corposucre.productionfood.inventario.TipoMovimientoPt;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.pedido.DetallePedidoRepository;
import co.edu.corposucre.productionfood.pedido.EstadoPedido;
import co.edu.corposucre.productionfood.pedido.PedidoRepository;

/**
 * Registro, consulta y cambio de estado de devoluciones de un pedido (HU-18).
 *
 * <p>Registrar una devolución solo <em>reporta</em> la mercancía: la fila nace
 * {@code PENDIENTE} y no se mueve inventario (R-01), porque producto en mal estado no debe
 * volver a estar disponible hasta que alguien lo revise. Que la aprobación repone el stock es
 * R-01 y vive en {@link #cambiarEstado}, no en el alta.
 *
 * <p>El control de cantidad es contra el acumulado de la línea (R-03), no contra cada
 * devolución por separado: las {@code APROBADA} y {@code PENDIENTE} ocupan cantidad pedida y
 * las {@code RECHAZADA} la liberan. Quien excluye las rechazadas es
 * {@link DevolucionRepository#sumarDevueltoPorDetalle(Integer)}, que suma en la base; el
 * servicio solo compara ese acumulado contra {@code detalle_pedido.cantidad}.
 *
 * <p>Los códigos de error son los de la especificación §5: un dato de entrada fuera de rango
 * responde 400 ({@link ParametroInvalidoException}), un conflicto con algo que ya existe —la
 * línea o el estado del pedido— 409 ({@link ConflictoNegocioException}) y una regla de negocio
 * que los datos no satisfacen, 422 ({@link ReglaDeNegocioException}).
 */
@Service
public class DevolucionService {

    private final DevolucionRepository devolucionRepository;

    private final DetallePedidoRepository detallePedidoRepository;

    private final PedidoRepository pedidoRepository;

    private final InventarioPtRepository inventarioPtRepository;

    private final InventarioPtService inventarioPtService;

    public DevolucionService(DevolucionRepository devolucionRepository,
                             DetallePedidoRepository detallePedidoRepository,
                             PedidoRepository pedidoRepository,
                             InventarioPtRepository inventarioPtRepository,
                             InventarioPtService inventarioPtService) {
        this.devolucionRepository = devolucionRepository;
        this.detallePedidoRepository = detallePedidoRepository;
        this.pedidoRepository = pedidoRepository;
        this.inventarioPtRepository = inventarioPtRepository;
        this.inventarioPtService = inventarioPtService;
    }

    /**
     * Registra una devolución sobre una línea de un pedido devolible, siempre
     * {@code PENDIENTE} (CA-01..CA-04, R-01, R-03, R-05).
     *
     * <p>Orden de las validaciones, de la especificación §5:
     *
     * <ol>
     *   <li>La cantidad, redondeada a escala 2, es mayor que cero (400
     *       {@code CANTIDAD_INVALIDA}). La normalización es la segunda barrera del
     *       {@code @NotNull} del DTO: un valor como {@code 0.001} pasa Bean Validation pero,
     *       a la escala de la columna, sería cero y violaría el {@code CHECK (cantidad > 0)}
     *       como error de integridad en lugar de un 400 con mensaje.</li>
     *   <li>El detalle de pedido existe (409 {@code DETALLE_PEDIDO_INEXISTENTE}, CA-01).</li>
     *   <li>El pedido está {@code ENTREGADO} o {@code EN_PREPARACION} (409
     *       {@code PEDIDO_NO_DEVOLVIBLE}, R-05): la mercancía de un pedido pendiente nunca
     *       salió y la de uno cancelada ya volvió entera (HU-16 R-04).</li>
     *   <li>El acumulado de la línea más esta devolución no supera la cantidad pedida (422
     *       {@code CANTIDAD_EXCEDE_PEDIDO}, R-03).</li>
     * </ol>
     *
     * <p>La línea se carga con {@code SELECT ... FOR UPDATE}
     * ({@link DetallePedidoRepository#obtenerPorIdParaActualizar}): el bloqueo se sostiene hasta
     * el commit y serializa dos altas simultáneas sobre la misma línea, de modo que la segunda
     * espera a releer el acumulado de R-03 en vez de validar contra un dato ya obsoleto y
     * superar —entre ambas— la cantidad pedida.
     *
     * <p>El {@code INSERT} lleva el estado {@code PENDIENTE} que fija el servicio y nunca
     * {@code fecha_devolucion}: la fecha la pone la base con su {@code DEFAULT (curdate())}
     * (R-06). No se toca inventario al registrar (R-01).
     *
     * <p>La respuesta se relee con los {@code JOIN} del repositorio y el acumulado se vuelve a
     * sumar <strong>después</strong> del {@code INSERT}: la devolución nace {@code PENDIENTE}
     * y por eso ya cuenta en R-03, de donde salen {@code devueltoPreviamente} —lo que traía
     * la línea antes de esta— y {@code disponibleParaDevolver}.
     *
     * @param req línea, motivo, descripción y cantidad positiva de la devolución
     * @return la devolución releída de la base, con su pedido, producto y acumulado de la línea
     * @throws ParametroInvalidoException si la cantidad no es mayor que cero
     * @throws ConflictoNegocioException si el detalle no existe o el pedido no es devolible
     * @throws ReglaDeNegocioException si la devolución excede lo pedido contando las previas
     * @throws RecursoNoEncontradoException si la fila desaparece entre el alta y la relectura
     */
    @Transactional
    @Auditable(accion = "REGISTRAR_DEVOLUCION", tabla = "devoluciones",
            detalle = "'Devolución registrada: ' + #resultado.idDevolucion"
                    + " + ' en el detalle ' + #req.idDetallePedido")
    public DevolucionResponse crear(CrearDevolucionRequest req) {
        var cantidad = validarCantidad(req.cantidadDevuelta());
        var linea = detallePedidoRepository.obtenerPorIdParaActualizar(req.idDetallePedido())
                .orElseThrow(() -> new ConflictoNegocioException("DETALLE_PEDIDO_INEXISTENTE",
                        String.format("El detalle de pedido con id %s no existe.",
                                req.idDetallePedido())));
        exigirPedidoDevolvible(linea.idPedido());

        var previas = devolucionRepository.sumarDevueltoPorDetalle(linea.idDetallePedido());
        if (previas.add(cantidad).compareTo(linea.cantidad()) > 0) {
            throw excedePedido(previas, linea.cantidad());
        }

        var id = devolucionRepository.insertar(new Devolucion(linea.idDetallePedido(),
                req.motivo(), req.descripcion(), cantidad, EstadoDevolucion.PENDIENTE));

        var guardada = devolucionRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la devolución", id));
        // El acumulado releído ya incluye esta devolución, que cuenta por ser PENDIENTE.
        return aResponse(guardada,
                devolucionRepository.sumarDevueltoPorDetalle(guardada.idDetallePedido()));
    }

    /**
     * Lista devoluciones de forma paginada y filtrada, con el compromiso de cada línea.
     *
     * <p>Los cuatro filtros son opcionales y viajan tal cual al repositorio; un valor nulo no
     * restringe. El total sale de {@link DevolucionRepository#contar} y no del tamaño de la
     * página: el listado puede traer una página y haber miles.
     *
     * <p>El orden se arma con {@link DevolucionRepository#ORDEN}, la lista blanca que traduce
     * cada campo del contrato a su columna; un campo fuera de ella responde 400
     * {@code CAMPO_ORDEN_INVALIDO} antes de tocar la base, y un {@code sort} nulo o vacío cae
     * en {@link DevolucionRepository#ORDEN_POR_DEFECTO}.
     *
     * @param estado     estado a filtrar, o {@code null} sin filtrar
     * @param fechaInicio primer día incluido del rango de {@code fecha_devolucion}, o
     *                    {@code null} sin límite inferior
     * @param fechaFin   último día incluido del rango, o {@code null} sin límite superior
     * @param idPedido   pedido cuyas devoluciones se listan, o {@code null} sin filtrar
     * @param page       página solicitada, desde 0
     * @param size       tamaño de página, entre 1 y 100
     * @param sort       orden "campo,direccion", con campos de la lista blanca
     *                   {@code fechaDevolucion}, {@code estado} e {@code id}; por defecto
     *                   "fechaDevolucion,desc"
     * @return página de devoluciones con su pedido, producto y acumulado de la línea
     * @throws ParametroInvalidoException si el rango de fechas está invertido, o si la
     *                                    paginación o el campo de orden son inválidos
     */
    @Transactional(readOnly = true)
    public PageResponse<DevolucionResponse> listar(EstadoDevolucion estado, LocalDate fechaInicio,
                                                   LocalDate fechaFin, Integer idPedido,
                                                   int page, int size, String sort) {
        if (fechaInicio != null && fechaFin != null && fechaInicio.isAfter(fechaFin)) {
            // Código del contrato transversal (04-CONTRATO-API.md §4), no el genérico
            // PARAMETRO_INVALIDO: el frontend distingue el rango invertido de un query mal formado.
            throw new ParametroInvalidoException("RANGO_FECHAS_INVALIDO",
                    "La fecha de inicio no puede ser posterior a la fecha de fin.");
        }

        var orden = PageRequest.of(page, size, sort, DevolucionRepository.ORDEN,
                DevolucionRepository.ORDEN_POR_DEFECTO);
        var filas = devolucionRepository.buscar(estado, fechaInicio, fechaFin, idPedido, orden);
        var contenido = filas.stream()
                .map(fila -> aResponse(fila.devolucion(), fila.comprometidaLinea()))
                .toList();
        var total = devolucionRepository.contar(estado, fechaInicio, fechaFin, idPedido);

        return PageResponse.of(contenido, orden.page(), orden.size(), total);
    }

    /**
     * Lista las devoluciones de un pedido, para GET {@code /pedidos/{id}/devoluciones}.
     *
     * <p>El listado es el contrato: un pedido sin devoluciones —o inexistente— responde una
     * página vacía con total {@code 0}, no un 404. Comprobar aquí la existencia del pedido no
     * añadiría información al consumidor y sí un caso de error que el endpoint no define.
     *
     * @param idPedido pedido cuyas devoluciones se listan
     * @param page     página solicitada, desde 0
     * @param size     tamaño de página, entre 1 y 100
     * @param sort     orden "campo,direccion", con campos de la lista blanca; por defecto
     *                 "fechaDevolucion,desc"
     * @return página de devoluciones del pedido, vacía si no tiene ninguna
     * @throws ParametroInvalidoException si la paginación o el campo de orden son inválidos
     */
    @Transactional(readOnly = true)
    public PageResponse<DevolucionResponse> listarPorPedido(Integer idPedido, int page, int size,
                                                            String sort) {
        return listar(null, null, null, idPedido, page, size, sort);
    }

    /**
     * Devuelve una devolución por su identificador, con su pedido, producto y acumulado.
     *
     * @param id identificador de la devolución
     * @return la devolución en la forma del contrato
     * @throws RecursoNoEncontradoException si la devolución no existe
     */
    @Transactional(readOnly = true)
    public DevolucionResponse obtener(Integer id) {
        var devolucion = devolucionRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la devolución", id));
        return aResponse(devolucion,
                devolucionRepository.sumarDevueltoPorDetalle(devolucion.idDetallePedido()));
    }

    /**
     * Cambia el estado de una devolución validando la máquina de transiciones (R-04) y, si el
     * destino es {@code APROBADA}, devuelve la mercancía a los lotes de los que salió (R-01).
     *
     * <p>Orden exacto de la operación, dentro de una única transacción:
     *
     * <ol>
     *   <li>La devolución debe existir; si no, 404.</li>
     *   <li>La transición se valida con la máquina de {@link EstadoDevolucion} <strong>antes</strong>
     *       de tocar el inventario ({@code TRANSICION_INVALIDA}, 409): un destino prohibido
     *       —aprobar una ya aprobada o volver a {@code PENDIENTE} (CP-20, CP-21)— no debe
     *       reponer stock, y un rechazo ni siquiera lo consulta.</li>
     *   <li>El {@code UPDATE} va antes de reponer y condicionado al estado leído: si otra
     *       transacción cambió la devolución entre la lectura y la escritura, afecta cero filas
     *       y se aborta sin tocar inventario. Así una doble aprobación concurrente no repone
     *       dos veces (CP-20).</li>
     *   <li>Solo si el destino es {@code APROBADA} (R-01) se lee el kardex de la línea
     *       —{@link InventarioPtRepository#movimientosDeVentaPorOrigen}, {@code SALIDA_VENTA}
     *       de {@code detalle_pedido} en orden inverso al consumo (R-02)— y se reparte la
     *       cantidad devuelta entre esos renglones, una entrada por lote con
     *       {@link TipoMovimientoPt#ENTRADA_DEVOLUCION} y origen {@code devoluciones}/{@code id}
     *       (CP-18). Un {@code RECHAZADA} no toca inventario en absoluto.</li>
     *   <li>Se relee la devolución para montar la respuesta.</li>
     * </ol>
     *
     * <p>Si el kardex no respalda la cantidad —lista vacía o suma insuficiente— se lanza
     * {@link IllegalStateException} para que la transacción revierta y la devolución <strong>no
     * quede aprobada</strong> con stock sin reponer (CP-24). Si una entrada falla, la
     * excepción se propaga sin atraparla: la transacción revierte las entradas anteriores y el
     * estado, de modo que inventario, kardex y {@code devoluciones} no pueden divergir.
     *
     * <p>La anotación usa la acción genérica {@code CAMBIAR_ESTADO_DEVOLUCION} y no
     * {@code APROBAR_DEVOLUCION}, que pedía el paso 7 de la tarea: un mismo método atiende
     * tanto la aprobación como el rechazo y una anotación no puede variar el literal según el
     * destino. El estado pedido viaja en {@code detalle}, igual que {@code CAMBIAR_ESTADO} de
     * HU-16 en {@code PedidoService.cambiarEstado}.
     *
     * @param id  identificador de la devolución
     * @param req estado destino: {@code APROBADA} repone stock, {@code RECHAZADA} no lo toca
     * @return la devolución releída de la base con su estado nuevo; {@code lotesRepuestos}
     *         informa cada entrada al aprobar y queda vacío al rechazar
     * @throws RecursoNoEncontradoException si la devolución no existe (también si desaparece
     *                                      entre la escritura y la relectura)
     * @throws ConflictoNegocioException si la transición no está permitida o se perdió la carrera
     * @throws IllegalStateException si el kardex no respalda la cantidad a reponer (CP-24)
     */
    @Transactional
    @Auditable(accion = "CAMBIAR_ESTADO_DEVOLUCION", tabla = "devoluciones",
            detalle = "'Devolución ' + #id + ' a ' + #req.estado")
    public AprobacionResponse cambiarEstado(Integer id, CambiarEstadoDevolucionRequest req) {
        var actual = devolucionRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la devolución", id));

        // Antes del inventario: una devolución terminal no puede volver a mover stock.
        if (!actual.estado().puedeTransicionarA(req.estado())) {
            throw transicionInvalida(actual.estado(), req.estado());
        }

        // El UPDATE va antes de reponer y condicionado al estado leído: si otra transacción
        // cambió la devolución entre la lectura y la escritura, afecta cero filas y se aborta
        // sin tocar inventario. Así una doble aprobación concurrente no repone dos veces.
        var afectadas = devolucionRepository.cambiarEstado(id, actual.estado(), req.estado());
        if (afectadas == 0) {
            throw transicionInvalida(actual.estado(), req.estado());
        }

        List<AprobacionResponse.LoteRepuesto> lotesRepuestos = List.of();
        if (req.estado() == EstadoDevolucion.APROBADA) {
            lotesRepuestos = reponerStock(actual);
        }

        var guardada = devolucionRepository.buscarPorId(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("la devolución", id));
        return new AprobacionResponse(id, guardada.estado().name(), lotesRepuestos);
    }

    /**
     * Devuelve la mercancía de una devolución aprobada a los lotes de los que salió la línea
     * (R-01, R-02, CP-18).
     *
     * <p>El kardex se lee en orden inverso al consumo ({@code id_movimiento DESC}) y ahí se
     * reparte la cantidad devuelta: el último lote tomado es el de vencimiento más lejano y es
     * el primero que debe recibir la mercancía. Varias salidas del mismo lote se agrupan en una
     * sola entrada, manteniendo la posición de su primera aparición, para que el kardex no
     * registre dos entradas donde cabía una.
     *
     * <p>La suma de las salidas debe respaldar lo que se devuelve; si no, es que el kardex de
     * la línea está incompleto y aprobar inventaría stock que nadie vendió, así que se lanza
     * {@link IllegalStateException} para revertir la transacción entera (CP-24).
     *
     * @param devolucion devolución recién aprobada, con su línea y cantidad
     * @return un {@link AprobacionResponse.LoteRepuesto} por lote que recibió mercancía, en el
     *         orden en que se repuso
     * @throws IllegalStateException si el kardex no respalda la cantidad devuelta
     */
    private List<AprobacionResponse.LoteRepuesto> reponerStock(Devolucion devolucion) {
        var movimientos = inventarioPtRepository.movimientosDeVentaPorOrigen(
                "detalle_pedido", devolucion.idDetallePedido());
        var respaldado = movimientos.stream()
                .map(LoteConsumido::cantidad)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (respaldado.compareTo(devolucion.cantidadDevuelta()) < 0) {
            throw new IllegalStateException("El kardex de detalle_pedido #"
                    + devolucion.idDetallePedido() + " solo respalda " + cifra(respaldado)
                    + " unidades; la devolución " + devolucion.idDevolucion()
                    + " intenta reponer " + cifra(devolucion.cantidadDevuelta()) + ".");
        }

        // LinkedHashMap mantiene la posición de la primera aparición de cada lote: agrupar
        // no cambia el orden inverso al consumo en el que se reparte.
        var porLote = new LinkedHashMap<Integer, LoteConsumido>();
        for (var movimiento : movimientos) {
            var previo = porLote.get(movimiento.idLote());
            porLote.put(movimiento.idLote(), previo == null ? movimiento
                    : new LoteConsumido(movimiento.idLote(), movimiento.codigoLote(),
                            movimiento.fechaVencimiento(),
                            previo.cantidad().add(movimiento.cantidad())));
        }

        var restante = devolucion.cantidadDevuelta().setScale(2, RoundingMode.HALF_UP);
        var repuestos = new ArrayList<AprobacionResponse.LoteRepuesto>();
        for (var lote : porLote.values()) {
            if (restante.signum() == 0) {
                break;
            }
            var porcion = lote.cantidad().min(restante);
            if (porcion.signum() == 0) {
                continue;
            }
            var saldos = inventarioPtService.registrarEntrada(lote.idLote(), porcion,
                    TipoMovimientoPt.ENTRADA_DEVOLUCION, "devoluciones",
                    devolucion.idDevolucion(),
                    "Reposicion por devolucion " + devolucion.idDevolucion());
            repuestos.add(new AprobacionResponse.LoteRepuesto(lote.codigoLote(), porcion,
                    saldos.anterior(), saldos.posterior()));
            restante = restante.subtract(porcion);
        }
        return repuestos;
    }

    /**
     * Construye la excepción {@code TRANSICION_INVALIDA} lista para lanzar, sin lanzarla aquí.
     *
     * <p>Mismo patrón que {@code PedidoService.transicionInvalida}: devolver la excepción deja
     * el {@code throw} —y por tanto la decisión de responder 409— en quien decide. El mensaje
     * nombra el estado actual y los destinos permitidos, como en el contrato §7 de HU-16.
     *
     * @param actual  estado en el que está la devolución
     * @param destino estado al que se intentó transicionar
     * @return la excepción, ya construida, lista para lanzar
     */
    private static ConflictoNegocioException transicionInvalida(EstadoDevolucion actual,
                                                                EstadoDevolucion destino) {
        var permitidas = actual.transicionesValidas().stream()
                .map(EstadoDevolucion::name)
                .sorted()
                .collect(Collectors.joining(", "));
        var disponibles = permitidas.isEmpty()
                ? "no hay transiciones disponibles"
                : "las transiciones disponibles son: " + permitidas;
        return new ConflictoNegocioException("TRANSICION_INVALIDA",
                String.format("No se puede pasar de %s a %s. Desde %s %s.",
                        actual, destino, actual, disponibles));
    }

    /**
     * Exige que el pedido de la línea admita devolución (R-05).
     *
     * <p>{@code ENTREGADO} y {@code EN_PREPARACION} son los únicos estados devolibles: un
     * pedido {@code PENDIENTE} nunca sacó la mercancía y uno {@code CANCELADO} ya devolvió su
     * stock entero, al que aprobar una devolución encima duplicaría.
     */
    private void exigirPedidoDevolvible(Integer idPedido) {
        var pedido = pedidoRepository.buscarPorId(idPedido)
                .orElseThrow(() -> new RecursoNoEncontradoException("el pedido", idPedido));
        if (pedido.getEstado() != EstadoPedido.ENTREGADO
                && pedido.getEstado() != EstadoPedido.EN_PREPARACION) {
            throw new ConflictoNegocioException("PEDIDO_NO_DEVOLVIBLE",
                    String.format("El pedido con id %s no admite devoluciones: solo los pedidos "
                            + "ENTREGADO o EN_PREPARACION son devolibles.", idPedido));
        }
    }

    /**
     * Segunda barrera tras el {@code @NotNull} del DTO (§5 {@code CANTIDAD_INVALIDA}).
     *
     * <p>Un valor como {@code 0.001} pasa Bean Validation pero, redondeado a la escala 2 de la
     * columna, queda en cero: una devolución de cero unidades no es una devolución y sin esta
     * comprobación llegaría al {@code CHECK (cantidad_devuelta > 0)} como un error de
     * integridad en lugar de un 400 con mensaje.
     *
     * @return la cantidad normalizada a escala 2
     * @throws ParametroInvalidoException si la cantidad es nula o no mayor que cero tras redondear
     */
    private static BigDecimal validarCantidad(BigDecimal cantidad) {
        var normalizada = cantidad == null ? null : cantidad.setScale(2, RoundingMode.HALF_UP);
        if (normalizada == null || normalizada.signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad devuelta debe ser un número mayor que cero.");
        }
        return normalizada;
    }

    /**
     * Construye el 422 de R-03 con las cifras del contrato §4: lo pedido, lo que ya ocupa la
     * línea y lo que aún queda por devolver.
     *
     * <p>El acumulado se lee <strong>antes</strong> de insertar, así que no incluye esta
     * devolución: «ya hay» y «solo puede devolver» describen la línea tal como está ahora.
     *
     * @param previas unidades ya devueltas o en trámite en la línea
     * @param pedida  cantidad original de la línea
     * @return la excepción, lista para lanzar
     */
    private static ReglaDeNegocioException excedePedido(BigDecimal previas, BigDecimal pedida) {
        return new ReglaDeNegocioException("CANTIDAD_EXCEDE_PEDIDO",
                "Se pidieron " + cifra(pedida) + " unidades y ya hay " + cifra(previas)
                        + " devueltas o en trámite. Solo puede devolver "
                        + cifra(pedida.subtract(previas)) + ".");
    }

    /**
     * Formatea una cifra para los mensajes de negocio sin ceros decimales sobrantes:
     * {@code 30.00} se lee {@code 30} y {@code 2.00} se lee {@code 2}, como en el ejemplo de la
     * especificación.
     */
    private static String cifra(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * Convierte una devolución y el compromiso de su línea en la respuesta del contrato (R-03).
     *
     * <p>{@code devueltoPreviamente} deja fuera la propia devolución si cuenta en el acumulado
     * —está {@code APROBADA} o {@code PENDIENTE}—, porque el compromiso ya la incluye y lo que
     * el consumidor necesita es lo que traía la línea antes de registrarla. Una
     * {@code RECHAZADA} liberó su cantidad, así que no se informa como devuelta.
     * {@code disponibleParaDevolver} se calcula siempre contra el acumulado completo.
     *
     * @param devolucion devolución con sus {@code JOIN}
     * @param comprometida unidades devueltas o en trámite de la línea, incluida la propia si
     *                     cuenta
     */
    private static DevolucionResponse aResponse(Devolucion devolucion, BigDecimal comprometida) {
        var comprometidaLinea = comprometida.setScale(2, RoundingMode.HALF_UP);
        var cuenta = devolucion.estado() == EstadoDevolucion.APROBADA
                || devolucion.estado() == EstadoDevolucion.PENDIENTE;
        var previamente = comprometidaLinea
                .subtract(cuenta ? devolucion.cantidadDevuelta() : BigDecimal.ZERO);
        var disponible = devolucion.cantidadPedida().subtract(comprometidaLinea);

        return new DevolucionResponse(
                devolucion.idDevolucion(),
                new DevolucionResponse.PedidoRef(devolucion.idPedido(),
                        devolucion.nombreCliente()),
                new ProductoRefResponse(devolucion.idProducto(), devolucion.nombreProducto(),
                        null),
                devolucion.cantidadPedida(),
                devolucion.cantidadDevuelta(),
                previamente,
                disponible,
                devolucion.motivo(),
                devolucion.descripcion(),
                devolucion.fechaDevolucion(),
                devolucion.estado().name());
    }
}
