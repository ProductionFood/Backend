package co.edu.corposucre.productionfood.inventario;

/**
 * Tipo de movimiento del kardex de materias primas.
 *
 * <p>Los valores coinciden exactamente con el {@code CHECK ck_mov_mp_tipo} que V2 (§C-04)
 * añadió a {@code movimientos_inventario_mp}. Añadir un valor aquí sin actualizar la
 * migración hace que el {@code INSERT} falle en la base, no en la compilación.
 */
public enum TipoMovimiento {
    ENTRADA_COMPRA,
    ENTRADA_LOTE,
    SALIDA_PRODUCCION,
    AJUSTE_POSITIVO,
    AJUSTE_NEGATIVO,
    BAJA_VENCIMIENTO
}
