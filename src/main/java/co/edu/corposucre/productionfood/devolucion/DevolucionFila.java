package co.edu.corposucre.productionfood.devolucion;

import java.math.BigDecimal;

/**
 * Fila del listado de devoluciones con el compromiso acumulado de su línea (HU-18, R-03).
 *
 * <p>{@code comprometidaLinea} es la suma de {@code cantidad_devuelta} de las devoluciones
 * {@code APROBADA} y {@code PENDIENTE} de la misma línea de pedido —incluida la propia fila—
 * calculada con una subconsulta correlacionada en el mismo {@code SELECT}, de modo que el
 * listado no hace una consulta por fila. Las {@code RECHAZADA} no cuentan: liberan la cantidad
 * para una devolución posterior (R-03).
 *
 * <p>Vive fuera de {@link Devolucion} porque es un derivado de la consulta, no una columna de
 * la tabla: guardarla en el registro ensuciaría un POJO que también se usa para escribir.
 *
 * @param devolucion         devolución de la fila, con sus {@code JOIN}
 * @param comprometidaLinea  unidades ya devueltas o en trámite en esa línea
 */
public record DevolucionFila(Devolucion devolucion, BigDecimal comprometidaLinea) {
}
