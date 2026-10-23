package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Escritura del kardex de materias primas ({@code movimientos_inventario_mp}, V2 §C-04).
 *
 * <p>La tabla no tiene entidad JPA porque la aplicación solo inserta en ella: el listado del
 * kardex se resuelve con JDBC. {@link Inventario} se usa como tipo de dominio únicamente para
 * que Spring Data pueda construir el repositorio; el {@code insert} es nativo y no toca esa
 * entidad.
 *
 * <p>El {@code tipoMovimiento} viaja como {@code String} (el nombre del enum) y no como
 * {@link TipoMovimiento}: en una {@code nativeQuery} el binding de un enum depende del mapeo
 * que resuelva Hibernate y podría acabar como ordinal, que el {@code CHECK} de la base
 * rechazaría.
 */
public interface MovimientoInventarioRepository extends Repository<Inventario, Integer> {

    /**
     * Inserta un movimiento de kardex.
     *
     * <p>{@code id_usuario} y {@code fecha} se omiten a propósito: la fecha la pone
     * {@code DEFAULT CURRENT_TIMESTAMP} y el usuario se añadirá cuando el llamador pueda
     * resolverlo; ambos admiten {@code NULL}. No lleva {@code @Transactional}: la transacción
     * es la misma que abrió el llamador para el {@code UPDATE inventario}.
     *
     * @param idMateriaPrima  materia prima cuyo saldo cambia
     * @param tipoMovimiento  nombre del valor de {@link TipoMovimiento}
     * @param cantidad        cantidad movida, con la escala original del llamador
     * @param saldoAnterior   saldo antes del movimiento
     * @param saldoPosterior  saldo después del movimiento
     * @param tablaOrigen     tabla que originó el movimiento, o {@code null}
     * @param idOrigen        identificador de la fila origen, o {@code null}
     * @param observacion     texto libre para el kardex, o {@code null}
     */
    @Modifying
    @Query(value = """
        insert into movimientos_inventario_mp
            (id_materia_prima, tipo_movimiento, cantidad, saldo_anterior, saldo_posterior,
             tabla_origen, id_origen, observacion)
        values (:idMateriaPrima, :tipoMovimiento, :cantidad, :saldoAnterior, :saldoPosterior,
                :tablaOrigen, :idOrigen, :observacion)
        """, nativeQuery = true)
    void insertar(@Param("idMateriaPrima") Integer idMateriaPrima,
                  @Param("tipoMovimiento") String tipoMovimiento,
                  @Param("cantidad") BigDecimal cantidad,
                  @Param("saldoAnterior") BigDecimal saldoAnterior,
                  @Param("saldoPosterior") BigDecimal saldoPosterior,
                  @Param("tablaOrigen") String tablaOrigen,
                  @Param("idOrigen") Integer idOrigen,
                  @Param("observacion") String observacion);
}
