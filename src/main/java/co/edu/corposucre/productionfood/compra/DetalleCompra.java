package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Línea de detalle de una compra, mapeada sobre la tabla {@code detalle_compra} (HU-14).
 *
 * <p>Es un registro inmutable, igual que {@link LineaDetalleCompra}, porque el módulo usa JDBC
 * explícito. A diferencia del registro de HU-13, este lleva {@code idCompra}, necesario para
 * persistir la línea, y las proyecciones de nombre y unidad que aporta el {@code JOIN} de
 * {@link DetalleCompraRepository}.
 *
 * <p>La cantidad y el precio se fijan a escala 2 en el constructor compacto, la de las columnas
 * {@code decimal(10,2)} de {@code detalle_compra} (R-07). El importe de la línea no se persiste:
 * se calcula con {@link #subtotal()} cuando la necesita el consumidor.
 *
 * <p>{@code nombreMateriaPrima} y {@code abreviaturaUnidad} son proyecciones de solo lectura que
 * llegan del {@code JOIN} con {@code materias_primas} y {@code unidades_medida}; no existen como
 * columnas en {@code detalle_compra} y quedan en {@code null} cuando la fila se lee sin el
 * {@code JOIN}.
 *
 * @param idDetalle          identificador de la línea
 * @param idCompra           compra a la que pertenece la línea
 * @param idMateriaPrima     materia prima comprada
 * @param cantidad           cantidad comprada; positiva por regla de negocio (R-05)
 * @param precioUnitario     precio pagado por unidad en esta compra; puede ser cero (R-05)
 * @param nombreMateriaPrima nombre de la materia prima, traído del {@code JOIN}
 * @param abreviaturaUnidad  abreviatura de la unidad de la materia prima, del {@code JOIN}
 */
public record DetalleCompra(
        Integer idDetalle,
        Integer idCompra,
        Integer idMateriaPrima,
        BigDecimal cantidad,
        BigDecimal precioUnitario,
        String nombreMateriaPrima,
        String abreviaturaUnidad) {

    public DetalleCompra {
        cantidad = cantidad == null ? null : cantidad.setScale(2, RoundingMode.HALF_UP);
        precioUnitario = precioUnitario == null
                ? null : precioUnitario.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Construye una línea sin las proyecciones del {@code JOIN}, para las operaciones de
     * escritura, que solo necesitan las columnas de la tabla.
     *
     * @param idDetalle      identificador de la línea, o {@code null} si aún no se ha insertado
     * @param idCompra       compra a la que pertenece la línea
     * @param idMateriaPrima materia prima comprada
     * @param cantidad       cantidad comprada
     * @param precioUnitario precio pagado por unidad en esta compra
     */
    public DetalleCompra(Integer idDetalle, Integer idCompra, Integer idMateriaPrima,
                         BigDecimal cantidad, BigDecimal precioUnitario) {
        this(idDetalle, idCompra, idMateriaPrima, cantidad, precioUnitario, null, null);
    }

    /**
     * Importe de la línea: {@code cantidad * precioUnitario}, redondeado a escala 2 con
     * {@code HALF_UP} (R-03).
     *
     * <p>El producto de dos {@code decimal(10,2)} puede traer más decimales de los que caben en
     * la vista —por ejemplo 33.33 × 3333.33 da 111099.8889—; el redondeo a dos decimales es el
     * mismo que aplica el backend al informar cualquier importe. Nunca lo calcula el cliente.
     *
     * @return el subtotal de la línea a escala 2
     */
    public BigDecimal subtotal() {
        return cantidad.multiply(precioUnitario).setScale(2, RoundingMode.HALF_UP);
    }
}
