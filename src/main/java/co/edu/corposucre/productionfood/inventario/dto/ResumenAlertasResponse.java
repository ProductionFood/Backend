package co.edu.corposucre.productionfood.inventario.dto;

/**
 * Conteo de materias primas en alerta (HU-27): {@code total = agotadas + bajas}.
 */
public record ResumenAlertasResponse(long total, long agotadas, long bajas) {
}
