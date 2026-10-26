package co.edu.corposucre.productionfood.inventario;

/**
 * Tipo de movimiento del kardex de producto terminado.
 *
 * <p>Los valores coinciden exactamente con el {@code CHECK ck_mov_pt_tipo} que V2 (§C-04)
 * añadió a {@code movimientos_inventario_pt}. Añadir un valor aquí sin actualizar la
 * migración hace que el {@code INSERT} falle en la base, no en la compilación.
 */
public enum TipoMovimientoPt {
    ENTRADA_PRODUCCION,
    SALIDA_VENTA,
    ENTRADA_DEVOLUCION,
    AJUSTE_POSITIVO,
    AJUSTE_NEGATIVO,
    BAJA_VENCIMIENTO
}
