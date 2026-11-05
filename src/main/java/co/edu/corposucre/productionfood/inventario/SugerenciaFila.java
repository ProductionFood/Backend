package co.edu.corposucre.productionfood.inventario;

/**
 * Materia prima en alerta junto con el proveedor de su última compra {@code RECIBIDA}, tal como
 * sale de la consulta. {@code idProveedor} y {@code nombreProveedor} son nulos si la materia
 * prima nunca se ha recibido en una compra.
 */
record SugerenciaFila(AlertaStockFila alerta, Integer idProveedor, String nombreProveedor) {
}
