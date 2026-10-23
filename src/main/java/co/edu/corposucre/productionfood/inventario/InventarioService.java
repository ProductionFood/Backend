package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Service;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;

/**
 * Único componente del sistema autorizado a modificar {@code inventario}.
 *
 * <p><strong>Regla de oro:</strong> todo {@code UPDATE inventario} pasa por aquí. Las historias
 * que mueven stock (recepción de compras, lotes, ajustes, producción y bajas) deben llamar a
 * {@link #registrarEntrada} o {@link #registrarSalida}; si aparece un {@code UPDATE inventario}
 * en otra clase, es un defecto que rompe la conciliación entre el saldo y el kardex.
 *
 * <p>Cada movimiento sigue el mismo orden: se bloquea la fila con {@code SELECT ... FOR UPDATE},
 * se calculan los saldos, se actualiza el stock y se inserta el movimiento de kardex. El
 * bloqueo y las dos escrituras deben ocurrir en la <em>misma</em> transacción; de lo contrario,
 * dos operaciones concurrentes pueden leer el mismo saldo anterior y perder una suma, o el
 * saldo puede quedar actualizado sin su movimiento si el kardex falla.
 *
 * <p>Por eso esta clase <strong>no lleva {@code @Transactional}</strong>: la transacción la abre
 * el servicio llamador, que es quien conoce las demás escrituras de su operación (por ejemplo,
 * el {@code INSERT} en {@code lotes}). Así el {@code FOR UPDATE} y el kardex comparten el
 * contexto transaccional y un fallo en cualquiera de los pasos revierte todos. Llamar a estos
 * métodos fuera de una transacción deja el bloqueo sin efecto.
 */
@Service
public class InventarioService {

    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final InventarioRepository inventarioRepository;
    private final MovimientoInventarioRepository movimientoRepository;

    public InventarioService(InventarioRepository inventarioRepository,
                             MovimientoInventarioRepository movimientoRepository) {
        this.inventarioRepository = inventarioRepository;
        this.movimientoRepository = movimientoRepository;
    }

    /**
     * Suma cantidad al stock de una materia prima y registra el movimiento de kardex.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @param cantidad       cantidad a sumar; debe ser mayor que cero
     * @param tipo           tipo de movimiento, según el catálogo de V2
     * @param tablaOrigen    tabla que originó el movimiento, o {@code null}
     * @param idOrigen       identificador de la fila origen, o {@code null}
     * @param observacion    texto libre para el kardex, o {@code null}
     * @return saldos anterior y posterior al movimiento
     * @throws ParametroInvalidoException si la cantidad es nula, cero o negativa
     * @throws RecursoNoEncontradoException si la materia prima no tiene fila de inventario
     */
    public SaldosInventario registrarEntrada(Integer idMateriaPrima, BigDecimal cantidad,
                                             TipoMovimiento tipo, String tablaOrigen,
                                             Integer idOrigen, String observacion) {
        return registrar(idMateriaPrima, cantidad, tipo, tablaOrigen, idOrigen, observacion,
                true);
    }

    /**
     * Resta cantidad del stock de una materia prima y registra el movimiento de kardex.
     *
     * <p>Si el saldo resultante fuera negativo no se actualiza nada ni se inserta el kardex:
     * la operación completa se rechaza con {@code STOCK_INSUFICIENTE}.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @param cantidad       cantidad a restar; debe ser mayor que cero
     * @param tipo           tipo de movimiento, según el catálogo de V2
     * @param tablaOrigen    tabla que originó el movimiento, o {@code null}
     * @param idOrigen       identificador de la fila origen, o {@code null}
     * @param observacion    texto libre para el kardex, o {@code null}
     * @return saldos anterior y posterior al movimiento
     * @throws ParametroInvalidoException si la cantidad es nula, cero o negativa
     * @throws RecursoNoEncontradoException si la materia prima no tiene fila de inventario
     * @throws ConflictoNegocioException si el saldo disponible no alcanza
     */
    public SaldosInventario registrarSalida(Integer idMateriaPrima, BigDecimal cantidad,
                                            TipoMovimiento tipo, String tablaOrigen,
                                            Integer idOrigen, String observacion) {
        return registrar(idMateriaPrima, cantidad, tipo, tablaOrigen, idOrigen, observacion,
                false);
    }

    private SaldosInventario registrar(Integer idMateriaPrima, BigDecimal cantidad,
                                       TipoMovimiento tipo, String tablaOrigen, Integer idOrigen,
                                       String observacion, boolean entrada) {
        validarCantidad(cantidad);

        var inventario = inventarioRepository.bloquearYObtener(idMateriaPrima)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "inventario de la materia prima", idMateriaPrima));

        var anterior = inventario.getCantidadDisponible().setScale(2, RoundingMode.HALF_UP);
        var posterior = (entrada ? anterior.add(cantidad) : anterior.subtract(cantidad))
                .setScale(2, RoundingMode.HALF_UP);

        // La comparación es compareTo y no equals: BigDecimal.equals distingue escalas, de
        // modo que 0.00 y 0 no serían iguales aunque representen el mismo saldo.
        if (!entrada && posterior.compareTo(CERO) < 0) {
            throw new ConflictoNegocioException("STOCK_INSUFICIENTE",
                    "El stock disponible de la materia prima " + idMateriaPrima + " es "
                            + anterior + " y no alcanza para retirar " + cantidad + ".");
        }

        inventarioRepository.actualizarSaldo(idMateriaPrima, posterior);
        // El kardex va después del UPDATE y dentro de la misma transacción del llamador: si
        // este insert falla, la excepción se propaga y la transacción revierte también el
        // saldo, de modo que inventario y kardex no pueden divergir.
        movimientoRepository.insertar(idMateriaPrima, tipo.name(), cantidad, anterior, posterior,
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
