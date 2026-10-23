package co.edu.corposucre.productionfood.lote.dto;

import java.math.BigDecimal;

/**
 * Saldo de una materia prima antes y después de registrar un lote (HU-09).
 *
 * <p>Se informa solo en la respuesta del {@code POST} para que el efecto sobre el inventario sea
 * visible en el momento, sin obligar a consultar otra pantalla. Ambos valores ya vienen con escala
 * 2, la de la columna {@code decimal(10,2)}.
 *
 * @param anterior saldo antes de la entrada
 * @param posterior saldo después de la entrada
 */
public record SaldosInventarioResponse(BigDecimal anterior, BigDecimal posterior) {
}
