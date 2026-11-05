package co.edu.corposucre.productionfood.reporte.dto;

/**
 * Producto de los planes del período sin receta activa (HU-26, R-07): no hay consumo teórico
 * contra el cual comparar, así que queda fuera del análisis y se lista para que el hueco sea
 * visible. {@code planes} cuenta los planes distintos en que aparece.
 */
public record SinRecetaResponse(String producto, long planes, String nota) {
}
