package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Línea de detalle de una compra, leída de la tabla {@code detalle_compra} (HU-13).
 *
 * <p>Es un registro inmutable de solo lectura: en HU-13 la compra se consulta y se cuenta, y las
 * escrituras del detalle (HU-14) ocurren en su propio flujo. El importe de la línea no se
 * persiste: se calcula como {@code cantidad * precioUnitario} cuando la recibe la compra.
 *
 * <p>La cantidad y el precio se fijan a escala 2 en el constructor compacto, la de las columnas
 * {@code decimal(10,2)} de {@code detalle_compra}, así toda construcción del registro cumple el
 * dominio de la base.
 *
 * @param idDetalle      identificador de la línea
 * @param idMateriaPrima materia prima comprada
 * @param cantidad       cantidad comprada
 * @param precioUnitario precio de una unidad de esa materia prima en esta compra
 */
public record LineaDetalleCompra(
        Integer idDetalle,
        Integer idMateriaPrima,
        BigDecimal cantidad,
        BigDecimal precioUnitario) {

    public LineaDetalleCompra {
        cantidad = cantidad == null ? null : cantidad.setScale(2, RoundingMode.HALF_UP);
        precioUnitario = precioUnitario == null
                ? null : precioUnitario.setScale(2, RoundingMode.HALF_UP);
    }
}
