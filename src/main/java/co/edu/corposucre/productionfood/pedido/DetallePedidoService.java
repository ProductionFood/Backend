package co.edu.corposucre.productionfood.pedido;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.InventarioPtService;
import co.edu.corposucre.productionfood.inventario.LoteDisponible;
import co.edu.corposucre.productionfood.lote.dto.ProductoRefResponse;
import co.edu.corposucre.productionfood.pedido.dto.ActualizarCantidadRequest;
import co.edu.corposucre.productionfood.pedido.dto.AgregarDetallePedidoRequest;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoListaResponse;
import co.edu.corposucre.productionfood.pedido.dto.DetallePedidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.LoteConsumidoResponse;
import co.edu.corposucre.productionfood.pedido.dto.LoteDisponibleResponse;
import co.edu.corposucre.productionfood.pedido.dto.StockProductoResponse;
import co.edu.corposucre.productionfood.producto.ProductoConDetalle;
import co.edu.corposucre.productionfood.producto.ProductoRepository;

/**
 * Líneas de detalle de un pedido (HU-17): consulta y alta del subrecurso.
 *
 * <p>Este servicio es el único punto que combina la escritura de {@code detalle_pedido} con el
 * descuento de producto terminado. Por eso abre la transacción: el {@code INSERT} de la línea y
 * la salida FEFO deben confirmarse o revertirse juntos, de lo contrario una línea podría existir
 * sin su movimiento de inventario —o al revés— y el pedido dejaría de corresponder con la
 * realidad del estante.
 *
 * <p>Reglas de negocio que este servicio carga y el DTO no puede expresar:
 *
 * <ul>
 *   <li><strong>R-02</strong>: el stock se descuenta en orden FEFO, primero del lote que vence
 *       antes. Una línea puede consumir varios lotes y cada uno genera su propio
 *       {@code SALIDA_VENTA} en el kardex (R-10).</li>
 *   <li><strong>R-04</strong>: el precio se <em>copia</em> de {@code productos.precio} al agregar
 *       la línea y no se vuelve a consultar. Subir el catálogo después no cambia lo ya vendido.</li>
 *   <li><strong>R-06</strong>: un producto no puede repetirse en el mismo pedido; el
 *       {@code UNIQUE uk_detalle_pedido} es la última defensa ante dos peticiones simultáneas y
 *       su {@link DuplicateKeyException} se traduce al mismo 409 legible.</li>
 *   <li><strong>R-09</strong>: el detalle solo se modifica si el pedido está {@code PENDIENTE} o
 *       {@code EN_PREPARACION}. Es una regla más amplia que la de la cabecera de HU-16, que solo
 *       admite {@code PENDIENTE} para editar cliente y fecha.</li>
 * </ul>
 *
 * <p>Los códigos de error son los de la especificación HU-17 §5 y no el genérico
 * {@code VALIDACION_FALLIDA}: una regla de negocio violada responde 409
 * ({@link ConflictoNegocioException}) y un dato de entrada fuera de rango, 400
 * ({@link ParametroInvalidoException}).
 */
@Service
public class DetallePedidoService {

    /** Tabla origen con la que el kardex liga cada salida a su línea de pedido (R-10). */
    private static final String TABLA_ORIGEN = "detalle_pedido";

    private final PedidoRepository pedidoRepository;

    private final DetallePedidoRepository detallePedidoRepository;

    private final ProductoRepository productoRepository;

    private final InventarioPtService inventarioPtService;

    private final InventarioPtRepository inventarioPtRepository;

    public DetallePedidoService(PedidoRepository pedidoRepository,
                                DetallePedidoRepository detallePedidoRepository,
                                ProductoRepository productoRepository,
                                InventarioPtService inventarioPtService,
                                InventarioPtRepository inventarioPtRepository) {
        this.pedidoRepository = pedidoRepository;
        this.detallePedidoRepository = detallePedidoRepository;
        this.productoRepository = productoRepository;
        this.inventarioPtService = inventarioPtService;
        this.inventarioPtRepository = inventarioPtRepository;
    }

    /**
     * Devuelve las líneas de un pedido con su total, su estado y si todavía es editable.
     *
     * <p>El total y los ítems salen de la base en cada llamada: {@code totalPedido} es el
     * {@code SUM(cantidad * precio_unitario)} redondeado a escala 2 con {@code HALF_UP} y
     * {@code cantidadItems} es el tamaño de la lista devuelta, de modo que el número de ítems y
     * el array nunca pueden discrepar. Un pedido sin líneas responde lista vacía y total
     * {@code 0.00}, que es el estado válido de un pedido recién registrado (HU-16).
     *
     * <p>Cada ítem informa los lotes que consumió leyéndolos del kardex (R-10): la trazabilidad
     * lote ↔ línea vive en {@code movimientos_inventario_pt}, no en {@code detalle_pedido}.
     * {@code totalPedido} va en {@code null} en los ítems porque el total agregado viaja una sola
     * vez en el wrapper.
     *
     * <p>{@code editable} implementa R-09 —no la regla más estricta de la cabecera de HU-16—:
     * {@code PENDIENTE} y {@code EN_PREPARACION} admiten cambios en el detalle; {@code ENTREGADO}
     * y {@code CANCELADO}, no.
     *
     * @param idPedido identificador del pedido
     * @return sus líneas, el total recalculado, el estado y la bandera de edición
     * @throws PedidoInexistenteException si el pedido no existe (404)
     */
    @Transactional(readOnly = true)
    public DetallePedidoListaResponse listar(Integer idPedido) {
        var pedido = buscarPedido(idPedido);

        var lineas = detallePedidoRepository.listarPorPedido(idPedido);
        var detalles = lineas.stream()
                .map(linea -> aResponse(linea, null))
                .toList();

        return new DetallePedidoListaResponse(
                idPedido,
                pedido.getEstado().name(),
                admiteCambios(pedido.getEstado()),
                detalles,
                totalPedido(idPedido),
                detalles.size());
    }

    /**
     * Stock vendible de un producto terminado, agregado por lote y en orden FEFO (HU-17).
     *
     * <p>{@code cantidadDisponible} es la suma de los lotes vendibles que devuelve
     * {@link InventarioPtRepository#lotesDisponiblesFefo(Integer)}, que ya excluye los vencidos y
     * los saldos en cero (R-03): es exactamente lo que se puede vender, no el inventario bruto.
     * {@code proximoVencimiento} es la fecha del primer lote de esa lista, el que FEFO consumiría
     * antes (R-02); la lista no se reordena aquí porque la consulta ya aplica el orden.
     *
     * <p>No exige que el producto esté activo: el stock de un producto desactivado sigue
     * existiendo físicamente y debe poder consultarse aunque ya no pueda agregarse a un pedido.
     *
     * @param idProducto producto terminado consultado
     * @return el stock agregado, el número de lotes activos, el próximo vencimiento y el detalle
     *         FEFO
     * @throws RecursoNoEncontradoException si el producto no existe (404)
     */
    @Transactional(readOnly = true)
    public StockProductoResponse obtenerStock(Integer idProducto) {
        var producto = productoRepository.buscarConDetalle(idProducto)
                .orElseThrow(() -> new RecursoNoEncontradoException("el producto", idProducto));

        var lotes = inventarioPtRepository.lotesDisponiblesFefo(idProducto);
        var cantidadDisponible = lotes.stream()
                .map(LoteDisponible::cantidadDisponible)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        var proximoVencimiento = lotes.isEmpty() ? null : lotes.getFirst().fechaVencimiento();

        return new StockProductoResponse(
                producto.getIdProducto(),
                producto.getNombre(),
                cantidadDisponible,
                lotes.size(),
                proximoVencimiento,
                lotes.stream()
                        .map(lote -> new LoteDisponibleResponse(lote.codigoLote(),
                                lote.fechaVencimiento(),
                                lote.cantidadDisponible().setScale(2, RoundingMode.HALF_UP)))
                        .toList());
    }

    /**
     * Agrega una línea a un pedido no cerrado (HU-17 CA-01..CA-04, R-09).
     *
     * <p>Orden de las validaciones, de la especificación §5:
     *
     * <ol>
     *   <li>El pedido existe (404 {@code PEDIDO_INEXISTENTE}) y admite cambios (409
     *       {@code PEDIDO_NO_EDITABLE}): {@code PENDIENTE} o {@code EN_PREPARACION}, según R-09.</li>
     *   <li>La cantidad, redondeada a escala 2, es mayor que cero (400
     *       {@code CANTIDAD_INVALIDA}).</li>
     *   <li>El producto existe (409 {@code PRODUCTO_INEXISTENTE}) y está activo (409
     *       {@code PRODUCTO_INACTIVO}).</li>
     *   <li>El producto no es ya un ítem del pedido (409 {@code PRODUCTO_DUPLICADO}, R-06).</li>
     *   <li>Hay stock suficiente (409 {@code STOCK_INSUFICIENTE}, CA-04). Esta comprobación da el
     *       mensaje con el nombre del producto; {@link InventarioPtService#registrarSalidaFefo}
     *       vuelve a validar bajo bloqueo, que es la que manda ante concurrencia (R-05).</li>
     * </ol>
     *
     * <p>El {@code INSERT} va <strong>antes</strong> de la salida FEFO porque el kardex liga cada
     * movimiento a la línea con {@code id_origen = id_detalle_pedido} (R-10); sin ese id no habría
     * trazabilidad lote ↔ pedido. Si la salida FEFO falla, la transacción revierte el
     * {@code INSERT} y la línea no queda creada. El precio se copia de {@code productos.precio}
     * antes de insertar (R-04): no se recalcula nunca.
     *
     * <p>La respuesta se relee de la base tras la escritura para traer nombre y unidad del
     * producto, informa los lotes consumidos leyéndolos del kardex y recalcula el total del
     * pedido con {@link DetallePedidoRepository#calcularTotal(Integer)}.
     *
     * @param idPedido pedido al que se agrega la línea
     * @param req      producto obligatorio y cantidad positiva (R-05)
     * @return la línea insertada, con sus lotes consumidos y el total del pedido recalculado
     * @throws PedidoInexistenteException si el pedido no existe (404)
     * @throws ConflictoNegocioException  si el pedido no admite cambios, el producto no existe o
     *                                    está inactivo, ya está en el pedido o no hay stock
     * @throws ParametroInvalidoException si la cantidad no es mayor que cero
     */
    @Transactional
    @Auditable(accion = "CREAR", tabla = "detalle_pedido",
            detalle = "'Producto ' + #req.idProducto + ' agregado al pedido ' + #idPedido")
    public DetallePedidoResponse agregar(Integer idPedido, AgregarDetallePedidoRequest req) {
        var pedido = buscarPedido(idPedido);
        exigirEditable(pedido);

        var cantidad = validarCantidad(req.cantidad());
        var producto = buscarProductoActivo(req.idProducto());

        if (detallePedidoRepository.existeProducto(idPedido, req.idProducto())) {
            throw productoDuplicado();
        }
        exigirStock(req.idProducto(), producto.getNombre(), cantidad);

        // R-04: se copia el precio vigente; la línea no volverá a consultar el catálogo.
        var precioUnitario = producto.getPrecio().setScale(2, RoundingMode.HALF_UP);
        var linea = new DetallePedido(null, idPedido, req.idProducto(), cantidad, precioUnitario);
        Integer idDetalle;
        try {
            idDetalle = detallePedidoRepository.insertar(linea);
        } catch (DuplicateKeyException ex) {
            // Otra petición insertó la misma pareja (pedido, producto) entre la comprobación y
            // el INSERT: el UNIQUE de R-06 la detuvo y se responde con el mismo 409 legible.
            throw productoDuplicado();
        }

        // El kardex necesita id_origen = id_detalle_pedido (R-10); si la salida FEFO falla, la
        // excepción revierte el INSERT y la transacción entera.
        inventarioPtService.registrarSalidaFefo(req.idProducto(), cantidad, TABLA_ORIGEN,
                idDetalle);

        var guardada = detallePedidoRepository.obtenerPorId(idDetalle)
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle));
        return aResponse(guardada, totalPedido(idPedido));
    }

    /**
     * Ajusta la cantidad de una línea moviendo el stock solo por la diferencia (R-07, R-09).
     *
     * <p>Aumentar de 10 a 15 descuenta 5 más; reducir de 10 a 6 devuelve 4 a los lotes de origen.
     * No se devuelve todo y se vuelve a descontar: eso dejaría en el kardex movimientos que no
     * corresponden a ningún hecho real (R-07).
     *
     * <p>Cada rama mueve stock solo si la diferencia no es cero, de modo que repetir la misma
     * cantidad es idempotente. El aumento se apoya en la validación de
     * {@link InventarioPtService#registrarSalidaFefo}, que bloquea los lotes y recalcula el saldo
     * dentro de la transacción (R-05); el pre-chequeo de {@link #exigirStock} solo aporta el
     * mensaje legible con el nombre del producto.
     *
     * <p>La línea se obtiene con {@code SELECT ... FOR UPDATE}: el bloqueo serializa dos ediciones
     * de la misma línea y evita que ambas calculen la diferencia sobre el mismo saldo.
     *
     * @param idPedido  pedido al que pertenece la línea
     * @param idDetalle línea que se edita
     * @param req       cantidad nueva
     * @return la línea releída con su total y los lotes netos que le quedan en el kardex
     * @throws PedidoInexistenteException  si el pedido no existe (404)
     * @throws RecursoNoEncontradoException si la línea no existe o es de otro pedido (404)
     * @throws ConflictoNegocioException   si el pedido no admite cambios o no hay stock para el
     *                                     aumento
     * @throws ParametroInvalidoException  si la cantidad no es mayor que cero
     */
    @Transactional
    @Auditable(accion = "EDITAR", tabla = "detalle_pedido",
            detalle = "'Detalle ' + #idDetalle + ' del pedido ' + #idPedido + ' actualizado'")
    public DetallePedidoResponse actualizarCantidad(Integer idPedido, Integer idDetalle,
                                                    ActualizarCantidadRequest req) {
        var pedido = buscarPedido(idPedido);
        exigirEditable(pedido);

        var linea = buscarLineaDelPedido(idPedido, idDetalle);
        var nueva = validarCantidad(req.cantidad());
        var actual = linea.cantidad().setScale(2, RoundingMode.HALF_UP);
        var diferencia = nueva.subtract(actual);

        if (diferencia.signum() > 0) {
            exigirStock(linea.idProducto(), linea.nombreProducto(), diferencia);
            inventarioPtService.registrarSalidaFefo(linea.idProducto(), diferencia, TABLA_ORIGEN,
                    idDetalle);
        } else if (diferencia.signum() < 0) {
            // Solo la diferencia vuelve a los lotes de origen; nunca la cantidad completa.
            inventarioPtService.registrarDevolucion(TABLA_ORIGEN, idDetalle, diferencia.abs());
        }

        detallePedidoRepository.actualizarCantidad(idDetalle, nueva);

        var guardada = detallePedidoRepository.obtenerPorId(idDetalle)
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle));
        return aResponse(guardada, totalPedido(idPedido));
    }

    /**
     * Quita una línea del pedido devolviendo toda su cantidad neta a los lotes de origen (R-08).
     *
     * <p>La devolución se apoya en el kardex y en el neto por lote: si la línea se había reducido
     * antes, solo vuelve lo que aún estaba descontado, no la cantidad original. Al eliminar la
     * línea, sus movimientos dejan de unirse a {@code detalle_pedido} y una cancelación posterior
     * del pedido no los devuelve otra vez.
     *
     * @param idPedido  pedido al que pertenece la línea
     * @param idDetalle línea que se elimina
     * @throws PedidoInexistenteException  si el pedido no existe (404)
     * @throws RecursoNoEncontradoException si la línea no existe o es de otro pedido (404)
     * @throws ConflictoNegocioException   si el pedido no admite cambios (R-09)
     */
    @Transactional
    @Auditable(accion = "ELIMINAR", tabla = "detalle_pedido",
            detalle = "'Detalle ' + #idDetalle + ' eliminado del pedido ' + #idPedido")
    public void eliminar(Integer idPedido, Integer idDetalle) {
        var pedido = buscarPedido(idPedido);
        exigirEditable(pedido);

        var linea = buscarLineaDelPedido(idPedido, idDetalle);
        inventarioPtService.registrarDevolucion(TABLA_ORIGEN, idDetalle,
                linea.cantidad().setScale(2, RoundingMode.HALF_UP));
        detallePedidoRepository.eliminar(idDetalle);
    }

    /**
     * Carga y bloquea la línea comprobando que pertenezca al pedido de la ruta.
     *
     * <p>Una línea de otro pedido se trata como inexistente: informar que existe pero no
     * pertenece al pedido filtraría datos de otro documento y daría un remedio equivocado.
     */
    private DetallePedido buscarLineaDelPedido(Integer idPedido, Integer idDetalle) {
        return detallePedidoRepository.obtenerPorIdParaActualizar(idDetalle)
                .filter(linea -> idPedido.equals(linea.idPedido()))
                .orElseThrow(() -> new RecursoNoEncontradoException("la línea de detalle",
                        idDetalle));
    }

    /**
     * Carga el pedido o responde 404 con el código propio de HU-17.
     *
     * <p>No es {@link RecursoNoEncontradoException}: ese responde con el código transversal
     * {@code RECURSO_NO_ENCONTRADO} y aquí el contrato exige {@code PEDIDO_INEXISTENTE} para
     * distinguir que el que falta es el documento padre.
     */
    private Pedido buscarPedido(Integer idPedido) {
        return pedidoRepository.buscarPorId(idPedido)
                .orElseThrow(() -> new PedidoInexistenteException(idPedido));
    }

    /**
     * Exige que el pedido admita cambios en su detalle (R-09).
     *
     * <p>{@code PENDIENTE} y {@code EN_PREPARACION} son los únicos estados editables: en
     * {@code ENTREGADO} la mercancía ya salió y en {@code CANCELADO} ya volvió. Una devolución
     * posterior a la entrega es HU-18, no una edición del pedido.
     */
    private static void exigirEditable(Pedido pedido) {
        if (!admiteCambios(pedido.getEstado())) {
            throw new ConflictoNegocioException("PEDIDO_NO_EDITABLE",
                    String.format("El pedido con id %s no es editable: solo los pedidos PENDIENTE "
                            + "o EN_PREPARACION admiten cambios en su detalle.",
                            pedido.getIdPedido()));
        }
    }

    private static boolean admiteCambios(EstadoPedido estado) {
        return estado == EstadoPedido.PENDIENTE || estado == EstadoPedido.EN_PREPARACION;
    }

    /**
     * Carga el producto y comprueba que exista y esté activo (CA-02).
     *
     * <p>La distinción entre inexistente e inactivo es deliberada: el contrato §5 les da códigos
     * distintos porque el remedio es distinto —crear el producto frente a reactivarlo—.
     */
    private ProductoConDetalle buscarProductoActivo(Integer idProducto) {
        var producto = productoRepository.buscarConDetalle(idProducto)
                .orElseThrow(() -> new ConflictoNegocioException("PRODUCTO_INEXISTENTE",
                        String.format("El producto con id %s no existe.", idProducto)));
        if (!Boolean.TRUE.equals(producto.getEstado())) {
            throw new ConflictoNegocioException("PRODUCTO_INACTIVO",
                    String.format("El producto con id %s está inactivo; no puede agregarse a un "
                            + "pedido.", idProducto));
        }
        return producto;
    }

    /**
     * Comprueba el stock vendible antes de escribir (CA-04).
     *
     * <p>Este pre-chequeo existe para dar un mensaje con el nombre del producto y cifras legibles;
     * la validación que manda es la de {@link InventarioPtService#registrarSalidaFefo}, que
     * bloquea las filas y recalcula el saldo dentro de la misma transacción (R-05). Si entre ambas
     * lecturas otra venta consume el stock, el FEFO responde {@code STOCK_INSUFICIENTE} y la
     * transacción revierte el {@code INSERT} ya hecho.
     */
    private void exigirStock(Integer idProducto, String nombre, BigDecimal cantidad) {
        var disponible = inventarioPtService.stockDisponible(idProducto);
        if (disponible.compareTo(cantidad) < 0) {
            throw new ConflictoNegocioException("STOCK_INSUFICIENTE",
                    "Solo hay " + cifra(disponible) + " unidades disponibles de " + nombre
                            + ". Se solicitaron " + cifra(cantidad) + ".");
        }
    }

    /**
     * Segunda barrera tras el {@code @NotNull} del DTO (§5 {@code CANTIDAD_INVALIDA}).
     *
     * <p>Un valor como {@code 0.001} pasa Bean Validation pero, redondeado a la escala 2 de la
     * columna, queda en cero: una línea de cero unidades no es una línea y sin esta comprobación
     * llegaría al {@code CHECK (cantidad > 0)} como un error de integridad en lugar de un 400 con
     * mensaje.
     *
     * @return la cantidad normalizada a escala 2
     * @throws ParametroInvalidoException si la cantidad es nula o no mayor que cero tras redondear
     */
    private static BigDecimal validarCantidad(BigDecimal cantidad) {
        var normalizada = cantidad == null ? null : cantidad.setScale(2, RoundingMode.HALF_UP);
        if (normalizada == null || normalizada.signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad debe ser un número mayor que cero.");
        }
        return normalizada;
    }

    /**
     * Construye el 409 de R-06 con el mismo mensaje en la comprobación previa y en la carrera que
     * detiene el {@code UNIQUE}, para que el consumidor no distinga dos causas de una misma regla.
     */
    private static ConflictoNegocioException productoDuplicado() {
        return new ConflictoNegocioException("PRODUCTO_DUPLICADO",
                "El producto indicado ya está en el pedido; edite la cantidad de la línea "
                        + "existente.");
    }

    /**
     * Total del pedido recalculado desde la base, a escala 2 con {@code HALF_UP}.
     *
     * <p>El repositorio devuelve el {@code SUM} con la escala que trae MySQL —potencialmente
     * cuatro decimales, porque el producto de dos {@code decimal(10,2)} los tiene—; el redondeo
     * aquí evita que un total llegue como {@code 36000.0000} o con centavos fantasma.
     */
    private BigDecimal totalPedido(Integer idPedido) {
        return detallePedidoRepository.calcularTotal(idPedido).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Convierte una línea del repositorio en la respuesta del contrato.
     *
     * <p>{@code subtotal} lo calcula el backend como {@code cantidad * precioUnitario}: la cifra
     * oficial del importe nunca la entrega el cliente. Los lotes consumidos se leen del kardex
     * con los movimientos netos de la línea, de modo que una devolución posterior se refleja en
     * la consulta sin reconstruir el reparto FEFO en memoria (R-10).
     *
     * @param totalPedido total agregado a informar en el ítem, o {@code null} en el listado
     */
    private DetallePedidoResponse aResponse(DetallePedido linea, BigDecimal totalPedido) {
        return new DetallePedidoResponse(
                linea.idDetallePedido(),
                new ProductoRefResponse(linea.idProducto(), linea.nombreProducto(), linea.unidad()),
                linea.cantidad().setScale(2, RoundingMode.HALF_UP),
                linea.precioUnitario().setScale(2, RoundingMode.HALF_UP),
                linea.cantidad().multiply(linea.precioUnitario())
                        .setScale(2, RoundingMode.HALF_UP),
                inventarioPtRepository.movimientosNetosDeOrigen(TABLA_ORIGEN,
                                linea.idDetallePedido()).stream()
                        .map(lote -> new LoteConsumidoResponse(lote.codigoLote(),
                                lote.fechaVencimiento(),
                                lote.cantidad().setScale(2, RoundingMode.HALF_UP)))
                        .toList(),
                totalPedido);
    }

    /**
     * Formatea una cifra para el mensaje de stock sin ceros decimales sobrantes: {@code 77.00}
     * se lee {@code 77} y {@code 0.00} se lee {@code 0}, como en el ejemplo del contrato.
     */
    private static String cifra(BigDecimal valor) {
        return valor.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
