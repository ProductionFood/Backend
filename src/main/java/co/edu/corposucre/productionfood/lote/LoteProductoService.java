package co.edu.corposucre.productionfood.lote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.audit.Auditable;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.inventario.InventarioPtRepository;
import co.edu.corposucre.productionfood.inventario.TipoMovimientoPt;
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
