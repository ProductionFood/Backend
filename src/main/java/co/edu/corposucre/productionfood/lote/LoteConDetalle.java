package co.edu.corposucre.productionfood.lote;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Proyección de la consulta que devuelve un lote de materia prima con el nombre y la unidad de su
 * materia prima (HU-09).
 *
 * <p>Es una interfaz y no un record porque Spring Data la rellena a partir de los alias de las
 * columnas de la consulta nativa; cada alias debe coincidir exactamente con el nombre de la
 * propiedad (sin el prefijo {@code get}): {@code idLote}, {@code codigoLote},
 * {@code idMateriaPrima}, {@code materiaPrimaNombre}, {@code idUnidad}, {@code unidadNombre},
 * {@code unidadAbreviatura}, {@code fechaProduccion}, {@code fechaVencimiento} y {@code cantidad}.
 *
 * <p>No se expone {@code id_producto} (siempre {@code null} en esta historia, R-03): no forma
 * parte del contrato del lote de materia prima. {@code diasParaVencer} y {@code vencido} tampoco
 * salen de la base, porque dependen de la fecha de hoy y los calcula el servicio.
 */
public interface LoteConDetalle {

    Integer getIdLote();

    String getCodigoLote();

    Integer getIdMateriaPrima();

    String getMateriaPrimaNombre();

    Integer getIdUnidad();

    String getUnidadNombre();

    String getUnidadAbreviatura();

    LocalDate getFechaProduccion();

    LocalDate getFechaVencimiento();

    BigDecimal getCantidad();
}
