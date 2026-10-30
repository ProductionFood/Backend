package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;

/**
 * Único componente autorizado a modificar el inventario de producto terminado.
 *
 * <p><strong>Regla de oro:</strong> todo {@code UPDATE inventario_producto_terminado} pasa por
 * aquí. Las historias que mueven stock por lote —ventas, devoluciones, ajustes y bajas— deben
 * llamar a {@link #registrarEntrada}; un {@code UPDATE} directo en otra clase rompe la
 * conciliación entre el saldo y el kardex.
 *
 * <p>Cada movimiento sigue el mismo orden: se bloquea la fila con {@code SELECT ... FOR UPDATE},
 * se calculan los saldos, se actualiza el stock y se inserta el movimiento de kardex. El bloqueo
 * y las dos escrituras deben ocurrir en la <em>misma</em> transacción; de lo contrario, dos
 * operaciones concurrentes pueden leer el mismo saldo anterior y perder una suma, o el saldo
 * puede quedar actualizado sin su movimiento si el kardex falla.
 *
 * <p>Por eso {@link #registrarEntrada} <strong>no lleva {@code @Transactional}</strong>: la
 * transacción la abre el servicio llamador, que es quien conoce las demás escrituras de su
 * operación (por ejemplo, el cambio de estado de un pedido). Así el {@code FOR UPDATE} y el
 * kardex comparten el contexto transaccional y un fallo en cualquiera de los pasos revierte
 * todos. Llamar a este método fuera de una transacción deja el bloqueo sin efecto.
 */
@Service
public class InventarioPtService {

    private final InventarioPtRepository inventarioPtRepository;

    public InventarioPtService(InventarioPtRepository inventarioPtRepository) {
        this.inventarioPtRepository = inventarioPtRepository;
    }

    /**
     * Suma cantidad al saldo de un lote de producto terminado y registra su movimiento de kardex.
     *
     * <p>No abre transacción propia: la abre el llamador y debe cubrir el bloqueo, el
     * {@code UPDATE} del saldo y el {@code INSERT} del kardex. El kardex va después del
     * {@code UPDATE} y dentro de esa misma transacción, de modo que si el {@code INSERT} falla la
     * excepción se propaga y la transacción revierte también el saldo.
     *
     * <p>La salida FEFO al vender es de HU-17 y no se implementa aquí: este servicio solo conoce
     * la entrada de stock y la devolución.
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

    private static void validarCantidad(BigDecimal cantidad) {
        if (cantidad == null || cantidad.signum() <= 0) {
            throw new ParametroInvalidoException("CANTIDAD_INVALIDA",
                    "La cantidad debe ser un número mayor que cero.");
        }
    }
}
