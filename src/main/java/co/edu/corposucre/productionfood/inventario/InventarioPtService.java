package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;

/**
 * Único componente autorizado a modificar el inventario de producto terminado.
 *
 * <p><strong>Regla de oro:</strong> todo {@code UPDATE inventario_producto_terminado} pasa por
 * aquí. Las historias que mueven stock por lote —ventas, devoluciones, ajustes y bajas— deben
 * llamar a {@link #registrarSalidaFefo} o a {@link #registrarEntrada}; un {@code UPDATE} directo
 * en otra clase rompe la conciliación entre el saldo y el kardex.
 *
 * <p>Cada movimiento sigue el mismo orden: se bloquea la fila con {@code SELECT ... FOR UPDATE},
 * se calculan los saldos, se actualiza el stock y se inserta el movimiento de kardex. El bloqueo
 * y las dos escrituras deben ocurrir en la <em>misma</em> transacción; de lo contrario, dos
 * operaciones concurrentes pueden leer el mismo saldo anterior y perder una suma, o el saldo
 * puede quedar actualizado sin su movimiento si el kardex falla.
 *
 * <p>Por eso ninguno de estos métodos lleva {@code @Transactional}: la transacción la abre el
 * servicio llamador, que es quien conoce las demás escrituras de su operación (por ejemplo, el
 * {@code INSERT} de la línea de pedido). Así el {@code FOR UPDATE} y el kardex comparten el
 * contexto transaccional y un fallo en cualquiera de los pasos revierte todos. Llamar a este
 * servicio fuera de una transacción deja el bloqueo sin efecto.
 */
@Service
public class InventarioPtService {

    private final InventarioPtRepository inventarioPtRepository;

    public InventarioPtService(InventarioPtRepository inventarioPtRepository) {
        this.inventarioPtRepository = inventarioPtRepository;
    }

    /**
     * Suma el saldo vendible de todos los lotes vigentes de un producto.
     *
     * <p>No es un {@code SELECT SUM} aparte: reutiliza la consulta FEFO, que ya excluye lotes
     * vencidos y saldos en cero. Así el número que se muestra al vendedor y el que se usa para
     * descontar salen de la misma fuente y no pueden divergir (R-03 de HU-17).
     *
     * @param idProducto producto terminado consultado
     * @return unidades disponibles, a escala 2; {@code 0.00} si no hay ningún lote vendible
     */
    public BigDecimal stockDisponible(Integer idProducto) {
        return inventarioPtRepository.lotesDisponiblesFefo(idProducto).stream()
                .map(LoteDisponible::cantidadDisponible)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Descuenta una cantidad del stock de un producto consumiendo lotes en orden FEFO.
     *
     * <p>Primero lee los lotes vendibles ordenados por vencimiento, pero ese primer cálculo solo
     * sirve para saber si hay algo y qué lotes bloquear: la cantidad válida se recalcula sobre
     * los saldos ya bloqueados con {@code FOR UPDATE}. Esa relectura es imprescindible porque
     * entre la primera consulta y el bloqueo otra venta pudo consumir unidades; confiar en la
     * lectura previa permitiría vender el mismo último pan dos veces (R-05).
     *
     * <p>Cada lote tocado recibe su propio {@code SALIDA_VENTA}, con su saldo anterior y
     * posterior: la línea de pedido puede haber consumido varios lotes y el kardex es la única
     * trazabilidad de ese reparto (R-10). Un lote que aparecía en la vista pero ya no existe al
     * bloquear —lo borró otra transacción— se salta; los saldos bloqueados son la autoridad.
     *
     * <p>No abre transacción: el llamador debe cubrir la validación, los descuentos y el
     * {@code INSERT} de la línea con la misma transacción.
     *
     * @param idProducto  producto terminado que se vende
     * @param cantidad    unidades a descontar; deben ser mayores que cero
     * @param tablaOrigen tabla que originó el movimiento, o {@code null}
     * @param idOrigen    fila de esa tabla, o {@code null}
     * @return lotes realmente consumidos, en el mismo orden FEFO en que se tomaron
     * @throws ParametroInvalidoException si la cantidad es nula, cero o negativa
     * @throws ConflictoNegocioException  si el stock vendible no alcanza
     */
    public List<LoteConsumido> registrarSalidaFefo(Integer idProducto, BigDecimal cantidad,
                                                   String tablaOrigen, Integer idOrigen) {
        validarCantidad(cantidad);
        var solicitado = cantidad.setScale(2, RoundingMode.HALF_UP);

        var lotes = inventarioPtRepository.lotesDisponiblesFefo(idProducto);
        if (lotes.isEmpty()) {
            throw stockInsuficiente(BigDecimal.ZERO, solicitado, idProducto);
        }

        var idsLote = lotes.stream().map(LoteDisponible::idLote).toList();
        var saldosBloqueados = inventarioPtRepository.bloquearSaldos(idsLote).stream()
                .collect(Collectors.toMap(SaldoLote::idLote,
                        saldo -> saldo.cantidadDisponible().setScale(2, RoundingMode.HALF_UP)));
        var disponible = saldosBloqueados.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (disponible.compareTo(solicitado) < 0) {
            throw stockInsuficiente(disponible, solicitado, idProducto);
        }

        var observacion = "Salida FEFO desde " + descripcionOrigen(tablaOrigen, idOrigen);
        var restante = solicitado;
        var consumidos = new ArrayList<LoteConsumido>();
        for (var lote : lotes) {
            if (restante.signum() == 0) {
                break;
            }
            var saldo = saldosBloqueados.get(lote.idLote());
            if (saldo == null) {
                continue;
            }
            var tomado = saldo.min(restante);
            if (tomado.signum() == 0) {
                continue;
            }
            var posterior = saldo.subtract(tomado).setScale(2, RoundingMode.HALF_UP);
            inventarioPtRepository.actualizarSaldo(lote.idLote(), posterior);
            inventarioPtRepository.registrarMovimiento(lote.idLote(),
                    TipoMovimientoPt.SALIDA_VENTA, tomado, saldo, posterior, tablaOrigen, idOrigen,
                    observacion);
            consumidos.add(new LoteConsumido(lote.idLote(), lote.codigoLote(),
                    lote.fechaVencimiento(), tomado));
            restante = restante.subtract(tomado);
        }
        return consumidos;
    }

    /**
     * Suma cantidad al saldo de un lote de producto terminado y registra su movimiento de kardex.
     *
     * <p>No abre transacción propia: la abre el llamador y debe cubrir el bloqueo, el
     * {@code UPDATE} del saldo y el {@code INSERT} del kardex. El kardex va después del
     * {@code UPDATE} y dentro de esa misma transacción, de modo que si el {@code INSERT} falla la
     * excepción se propaga y la transacción revierte también el saldo.
     *
     * <p>La salida FEFO al vender es {@link #registrarSalidaFefo}; este método es la vía de
     * entrada, que usan las devoluciones a través de {@link #registrarDevolucion}.
     *
     * @param idLote      lote cuyo saldo cambia
     * @param cantidad    cantidad a sumar; debe ser mayor que cero
     * @param tipo        tipo de movimiento, según el catálogo de V2
     * @param tablaOrigen tabla que originó el movimiento, o {@code null}
     * @param idOrigen    identificador de la fila origen, o {@code null}
     * @param observacion texto libre para el kardex, o {@code null}
     * @return saldos anterior y posterior al movimiento
     * @throws ParametroInvalidoException si la cantidad es nula, cero o negativa
     * @throws RecursoNoEncontradoException si el lote no tiene fila de inventario
     */
    public SaldosInventario registrarEntrada(Integer idLote, BigDecimal cantidad,
                                             TipoMovimientoPt tipo, String tablaOrigen,
                                             Integer idOrigen, String observacion) {
        validarCantidad(cantidad);

        var saldo = inventarioPtRepository.bloquearYObtener(idLote)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "el inventario del lote", idLote));

        var anterior = saldo.cantidadDisponible().setScale(2, RoundingMode.HALF_UP);
        var posterior = anterior.add(cantidad).setScale(2, RoundingMode.HALF_UP);

        inventarioPtRepository.actualizarSaldo(idLote, posterior);
        // El kardex va después del UPDATE y dentro de la misma transacción del llamador: si
        // este insert falla, la excepción se propaga y la transacción revierte también el
        // saldo, de modo que inventario y kardex no pueden divergir.
        inventarioPtRepository.registrarMovimiento(idLote, tipo, cantidad, anterior, posterior,
                tablaOrigen, idOrigen, observacion);

        return new SaldosInventario(anterior, posterior);
    }

    /**
     * Devuelve stock a los lotes de los que salió, repartiendo la cantidad entre ellos.
     *
     * <p>No se devuelve todo ni se vuelve a descontar: se repone exactamente la diferencia
     * pedida, leyendo del kardex cuánto queda neto en cada lote de origen (R-07/R-08 de HU-17).
     * Devolver-y-redescontar generaría movimientos que no corresponden a ningún hecho real.
     *
     * <p>El recorrido va en orden <strong>inverso al FEFO</strong>: los lotes que vencen más
     * tarde se reponen primero, porque son los que conservan más vida útil y conviene tener
     * disponibles antes que los que están por caducar.
     *
     * <p>Si el kardex no respalda la cantidad solicitada es una inconsistencia de datos, no una
     * regla de negocio: por eso lanza {@link IllegalStateException} y aborta la transacción del
     * llamador en vez de devolver de menos en silencio.
     *
     * @param tablaOrigen tabla que originó la salida, por ejemplo {@code detalle_pedido}
     * @param idOrigen    fila de esa tabla
     * @param cantidad    unidades a devolver; deben ser mayores que cero
     * @throws ParametroInvalidoException si la cantidad es nula, cero o negativa
     * @throws IllegalStateException      si el kardex no tiene neto suficiente para reponer
     */
    public void registrarDevolucion(String tablaOrigen, Integer idOrigen, BigDecimal cantidad) {
        validarCantidad(cantidad);
        var solicitado = cantidad.setScale(2, RoundingMode.HALF_UP);

        var netos = new ArrayList<>(
                inventarioPtRepository.movimientosNetosDeOrigen(tablaOrigen, idOrigen));
        var netoTotal = netos.stream().map(LoteConsumido::cantidad)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (netoTotal.compareTo(solicitado) < 0) {
            throw new IllegalStateException("El kardex de " + descripcionOrigen(tablaOrigen,
                    idOrigen) + " solo respalda "
                    + netoTotal.setScale(2, RoundingMode.HALF_UP).toPlainString()
                    + " unidades; se intentaron devolver " + solicitado.toPlainString() + ".");
        }

        Collections.reverse(netos);
        var observacion = "Devolución a " + descripcionOrigen(tablaOrigen, idOrigen);
        var restante = solicitado;
        for (var lote : netos) {
            if (restante.signum() == 0) {
                break;
            }
            var tomado = lote.cantidad().min(restante).setScale(2, RoundingMode.HALF_UP);
            if (tomado.signum() == 0) {
                continue;
            }
            registrarEntrada(lote.idLote(), tomado, TipoMovimientoPt.ENTRADA_DEVOLUCION,
                    tablaOrigen, idOrigen, observacion);
            restante = restante.subtract(tomado);
        }
    }

    private static ConflictoNegocioException stockInsuficiente(BigDecimal disponible,
                                                               BigDecimal solicitado,
                                                               Integer idProducto) {
        return new ConflictoNegocioException("STOCK_INSUFICIENTE",
                "Solo hay " + disponible.setScale(2, RoundingMode.HALF_UP).toPlainString()
                        + " unidades disponibles del producto " + idProducto
                        + ". Se solicitaron " + solicitado.toPlainString() + ".");
    }

    /**
     * Describe el origen de un movimiento para la columna {@code observacion} del kardex.
     *
     * <p>El origen es opcional: la producción y los ajustes del sistema no nacen de una fila de
     * negocio y llegan en {@code null}. En ese caso se escribe un texto fijo en vez de un
     * {@code "null"} en el campo de trazabilidad.
     */
    private static String descripcionOrigen(String tablaOrigen, Integer idOrigen) {
        if (tablaOrigen == null) {
            return "sin origen";
        }
        return idOrigen == null ? tablaOrigen : tablaOrigen + " #" + idOrigen;
    }

    private static void validarCantidad(BigDecimal cantidad) {
        if (cantidad == null || cantidad.signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad debe ser un número mayor que cero.");
        }
    }
}
