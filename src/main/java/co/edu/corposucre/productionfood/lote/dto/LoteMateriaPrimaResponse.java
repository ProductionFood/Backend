package co.edu.corposucre.productionfood.lote.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Respuesta de un lote de materia prima (HU-09).
 *
 * <p>La materia prima se embebe como objeto {@link MateriaPrimaRefResponse} para que el consumidor
 * no necesite una llamada extra para mostrar su nombre y la abreviatura de su unidad.
 *
 * <p>{@code diasParaVencer} y {@code vencido} son calculados y de solo lectura: se derivan de la
 * fecha de vencimiento y de la fecha de hoy, no se persisten. {@code vencido} marca visualmente el
 * lote (R-05) sin impedir su registro.
 *
 * <p>{@code inventarioActualizado} solo se informa en el {@code POST} de creación, con los saldos
 * anterior y posterior. En el listado y en la obtención de un lote queda {@code null}, y la
 * inclusión global {@code non_null} de Jackson lo omite del JSON: la clave no aparece porque esos
 * caminos no modifican el inventario.
 */
public record LoteMateriaPrimaResponse(
    Integer idLote,
    String codigoLote,
    MateriaPrimaRefResponse materiaPrima,
    LocalDate fechaProduccion,
    LocalDate fechaVencimiento,
    BigDecimal cantidad,
    long diasParaVencer,
    boolean vencido,
    SaldosInventarioResponse inventarioActualizado) {}
