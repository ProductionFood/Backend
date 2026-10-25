package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.common.pagination.PageRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.inventario.dto.InventarioResponse;

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
 * <p>Por eso los métodos de escritura <strong>no llevan {@code @Transactional}</strong>: la
 * transacción la abre el servicio llamador, que es quien conoce las demás escrituras de su
 * operación (por ejemplo, el {@code INSERT} en {@code lotes}). Así el {@code FOR UPDATE} y el
 * kardex comparten el contexto transaccional y un fallo en cualquiera de los pasos revierte
 * todos. Llamar a estos métodos fuera de una transacción deja el bloqueo sin efecto.
 *
 * <p>Las consultas de HU-10, en cambio, sí llevan {@code @Transactional(readOnly = true)}: leen
 * la vista {@code v_inventario_materia_prima} y no participan del bloqueo de escritura.
 */
@Service
public class InventarioService {

    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /**
     * Lista blanca de ordenamiento de la consulta: traduce el campo que pide el cliente al nombre
     * de columna SQL. {@code PageRequest.of} rechaza cualquier campo que no esté aquí, y la
     * columna resuelta es la única que llega al repositorio, de modo que el orden nunca es SQL
     * arbitrario.
     */
    private static final Map<String, String> ORDEN = Map.of(
            "nombre", "nombre",
            "cantidadDisponible", "cantidad_disponible",
            "faltante", "faltante",
            "fechaActualizacion", "fecha_actualizacion");

    private static final String ORDEN_POR_DEFECTO = "nombre,asc";

    private final InventarioRepository inventarioRepository;
    private final MovimientoInventarioRepository movimientoRepository;
    private final InventarioConsultaRepository inventarioConsultaRepository;

    public InventarioService(InventarioRepository inventarioRepository,
                             MovimientoInventarioRepository movimientoRepository,
                             InventarioConsultaRepository inventarioConsultaRepository) {
        this.inventarioRepository = inventarioRepository;
        this.movimientoRepository = movimientoRepository;
        this.inventarioConsultaRepository = inventarioConsultaRepository;
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

    /**
     * Lista de forma paginada el inventario de materia prima (HU-10).
     *
     * <p>Es solo lectura (R-01) y consulta la vista {@code v_inventario_materia_prima} (R-02),
     * que ya resuelve el {@code COALESCE} con inventario, el {@code estado_stock} y el
     * {@code faltante}; el servicio no reconstruye esos cálculos.
     *
     * <p>Las materias primas inactivas se incluyen por defecto (R-04): desactivarlas no retira su
     * stock de la bodega, y ocultarlas haría desaparecer existencias reales del sistema. El
     * cliente puede excluirlas con {@code incluirInactivas=false}.
     *
     * <p>{@code estadoStock} de cada fila puede ser {@code AGOTADO}, {@code BAJO} u {@code OK}
     * según R-05; con {@code soloStockBajo=true} solo se devuelven las dos primeras.
     *
     * @param idMateriaPrima   filtra por una materia prima, o {@code null} para no restringir
     * @param soloStockBajo    true para limitar a {@code BAJO} y {@code AGOTADO}; nulo equivale a
     *                         false
     * @param incluirInactivas true para incluir las inactivas; nulo equivale a true (R-04)
     * @param page             página solicitada, desde 0
     * @param size             tamaño de página, entre 1 y 100
     * @param sort             orden "campo,direccion"; por defecto "nombre,asc"
     * @throws ParametroInvalidoException si la paginación o el campo de orden son inválidos
     */
    @Transactional(readOnly = true)
    public PageResponse<InventarioResponse> listar(Integer idMateriaPrima, Boolean soloStockBajo,
                                                   Boolean incluirInactivas, int page, int size,
                                                   String sort) {
        var orden = PageRequest.of(page, size, sort, ORDEN, ORDEN_POR_DEFECTO);
        boolean soloBajo = Boolean.TRUE.equals(soloStockBajo);
        // R-04: solo se excluyen las inactivas cuando el cliente envía explícitamente false; un
        // parámetro ausente o nulo mantiene el comportamiento por defecto de mostrarlas.
        boolean incluirInactivasNormalizado = !Boolean.FALSE.equals(incluirInactivas);
        // El offset se calcula en long: page no tiene cota superior, así que page * size
        // desbordaría int a un valor negativo y el OFFSET del SQL fallaría.
        long offset = (long) orden.page() * orden.size();

        var filas = inventarioConsultaRepository.buscar(idMateriaPrima, soloBajo,
                incluirInactivasNormalizado, orden.propiedad(), orden.ascendente(),
                orden.size(), offset);
        var total = inventarioConsultaRepository.contar(idMateriaPrima, soloBajo,
                incluirInactivasNormalizado);

        return PageResponse.of(filas, orden.page(), orden.size(), total);
    }

    /**
     * Devuelve el detalle de inventario de una materia prima (HU-10).
     *
     * <p>Es solo lectura (R-01) y se resuelve contra la vista {@code v_inventario_materia_prima}
     * (R-02), de modo que el detalle y el listado muestran exactamente los mismos valores
     * calculados.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @return la fila de inventario de la materia prima
     * @throws RecursoNoEncontradoException si la materia prima no existe
     */
    @Transactional(readOnly = true)
    public InventarioResponse obtener(Integer idMateriaPrima) {
        return inventarioConsultaRepository.buscarPorMateriaPrima(idMateriaPrima)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "la materia prima en inventario", idMateriaPrima));
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
