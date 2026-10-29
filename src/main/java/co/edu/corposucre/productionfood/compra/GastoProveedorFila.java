package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fila agregada del gasto de compras por proveedor (HU-15).
 *
 * <p>Es lo que {@link CompraRepository#gastoPorProveedor} devuelve por cada proveedor con compras
 * RECIBIDA en el rango: la cantidad de compras distintas y la suma de
 * {@code cantidad * precio_unitario} de sus líneas de detalle. Un proveedor sin recibidas en el
 * rango no aparece como fila, y el servicio calcula sobre estas filas el porcentaje de cada una
 * contra la suma global (CP-16).
 *
 * <p>El total se fija a escala 2, la del contrato monetario: MySQL devuelve el producto de dos
 * {@code decimal(10,2)} —y por lo tanto su {@code SUM}— con escala 4, y el consumidor del
 * endpoint espera 2.
 *
 * @param proveedor       nombre del proveedor agrupado
 * @param cantidadCompras número de compras distintas RECIBIDA de ese proveedor en el rango
 * @param total           suma de {@code cantidad * precio_unitario} de sus líneas de detalle
 */
public record GastoProveedorFila(String proveedor, long cantidadCompras, BigDecimal total) {

    public GastoProveedorFila {
        total = total == null ? null : total.setScale(2, RoundingMode.HALF_UP);
    }
}
