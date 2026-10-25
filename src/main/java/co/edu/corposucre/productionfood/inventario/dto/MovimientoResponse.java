package co.edu.corposucre.productionfood.inventario.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Fila del kardex de materia prima tal como la ve el cliente (HU-10, R-03).
 *
 * <p>El kardex es el libro de movimientos que explica el saldo: cada fila dice qué entró o salió,
 * cuándo, quién y por qué documento. Sin él no habría forma de reconstruir cómo se llegó a la
 * {@code cantidad_disponible} actual.
 *
 * <p>{@code cantidad} conserva la escala de la columna {@code decimal(10,3)}: el kardex registra
 * la cantidad movida con precisión de milésimas, aunque los saldos se guarden con dos decimales.
 *
 * <p>{@code usuario} es {@code "Sistema"} cuando {@code id_usuario} es {@code NULL} (CP-16): hay
 * movimientos que no nacen de una acción humana y no deben mostrarse como un usuario inexistente
 * ni como una cadena vacía.
 *
 * <p>{@code origen} puede ser {@code null} si el movimiento no tiene tabla ni identificador de
 * origen; cuando existe alguno de los dos se devuelve un {@link OrigenResponse} con los valores
 * disponibles.
 *
 * @param fecha          momento en que se registró el movimiento
 * @param tipo           tipo de movimiento del catálogo de V2 (por ejemplo {@code ENTRADA_LOTE})
 * @param cantidad       cantidad movida, con la escala de {@code decimal(10,3)}
 * @param saldoAnterior  saldo antes del movimiento
 * @param saldoPosterior saldo después del movimiento
 * @param origen         documento que originó el movimiento, o {@code null}
 * @param usuario        nombre del usuario que lo registró, o {@code "Sistema"} si no hubo
 */
public record MovimientoResponse(
        LocalDateTime fecha,
        String tipo,
        BigDecimal cantidad,
        BigDecimal saldoAnterior,
        BigDecimal saldoPosterior,
        OrigenResponse origen,
        String usuario
) {
}
