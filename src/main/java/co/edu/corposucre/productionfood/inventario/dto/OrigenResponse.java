package co.edu.corposucre.productionfood.inventario.dto;

/**
 * Documento que originó un movimiento del kardex de materia prima.
 *
 * <p>Se compone de las columnas {@code tabla_origen} e {@code id_origen} de
 * {@code movimientos_inventario_mp}. Ambas admiten {@code NULL}: un ajuste manual puede no tener
 * documento asociado, y en tal caso el movimiento se devuelve con {@code origen} nulo en lugar
 * de un objeto con ambos campos vacíos.
 *
 * @param tabla nombre de la tabla que originó el movimiento, o {@code null}
 * @param id    identificador de la fila en esa tabla, o {@code null}
 */
public record OrigenResponse(String tabla, Integer id) {
}
