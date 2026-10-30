package co.edu.corposucre.productionfood.compra.dto;

import java.math.BigDecimal;

/**
 * Gasto del período contra un proveedor concreto (HU-15 §2, por-proveedor).
 *
 * <p>Agrupa las compras recibidas del rango por proveedor: la cantidad de compras distintas y la
 * suma de {@code cantidad * precio_unitario} de sus líneas de detalle, siempre recalculada desde
 * {@code detalle_compra} (R-05). Solo entran las compras en estado RECIBIDA —el gasto ejecutado—:
 * las PENDIENTE y las CANCELADA no son gasto contra el proveedor y por eso un proveedor cuyas
 * compras del rango no se hayan recibido ni siquiera aparece en la lista.
 *
 * <p>El porcentaje es el peso del total del proveedor sobre la suma de los totales del período,
 * a escala 2 con redondeo HALF_UP (CP-16): la suma de todos los porcentajes de la respuesta es
 * 100.00 con un residuo de redondeo de hasta ± 0.01, sin ajuste forzado del último.
 *
 * @param proveedor       nombre del proveedor agrupado
 * @param cantidadCompras número de compras distintas RECIBIDA de ese proveedor en el período
 * @param total           suma de {@code cantidad * precio_unitario} de sus líneas, a escala 2
 * @param porcentaje      peso del total del proveedor sobre el total del período, a escala 2
 */
public record GastoProveedorResponse(
        String proveedor,
        long cantidadCompras,
        BigDecimal total,
        BigDecimal porcentaje) {
}
