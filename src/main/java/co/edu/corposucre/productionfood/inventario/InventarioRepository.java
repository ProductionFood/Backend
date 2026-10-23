package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio del inventario de materias primas (HU-08).
 */
public interface InventarioRepository extends JpaRepository<Inventario, Integer> {

    /**
     * Crea la fila inicial de inventario de una materia prima recién creada, con stock 0.
     *
     * <p>Es la regla R-01 de HU-08: toda materia prima debe nacer con su fila de inventario en la
     * misma transacción que el alta, porque la columna {@code cantidad_disponible} es
     * {@code NOT NULL} y las vistas de stock ({@code v_inventario_materia_prima}) y los
     * movimientos del kardex esperan una fila por materia prima.
     *
     * <p>Es un {@code insert} nativo y no un {@code save} de JPA por dos motivos: el literal
     * {@code 0} se inserta exactamente como {@code 0.00} en el {@code decimal(10,2)} y la
     * columna {@code fecha_actualizacion} no se envía, de modo que MySQL la rellena con su
     * {@code DEFAULT CURRENT_TIMESTAMP}. No lleva {@code @Transactional}: la transacción la abre
     * el servicio que da de alta la materia prima.
     *
     * @param idMateriaPrima identificador de la materia prima creada
     */
    @Modifying
    @Query(value = """
        insert into inventario (id_materia_prima, cantidad_disponible)
        values (:idMateriaPrima, 0)
        """, nativeQuery = true)
    void crearFilaInicial(@Param("idMateriaPrima") Integer idMateriaPrima);

    /**
     * Indica si la materia prima ya tiene fila de inventario.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @return {@code true} si ya existe la fila de inventario
     */
    boolean existsByIdMateriaPrima(Integer idMateriaPrima);

    /**
     * Lee la fila de inventario bloqueándola hasta el final de la transacción.
     *
     * <p>{@code FOR UPDATE} solo tiene efecto dentro de una transacción abierta por el
     * llamador. Es lo que serializa dos entradas simultáneas sobre la misma materia prima:
     * sin él, ambas leerían el mismo saldo anterior y una de las dos sumas se perdería.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @return la fila bloqueada, o vacío si la materia prima no tiene inventario
     */
    @Query(value = """
        select * from inventario where id_materia_prima = :idMateriaPrima for update
        """, nativeQuery = true)
    Optional<Inventario> bloquearYObtener(@Param("idMateriaPrima") Integer idMateriaPrima);

    /**
     * Reemplaza el saldo disponible de una materia prima.
     *
     * <p>Es el único {@code UPDATE inventario} del sistema. No exige que la fila exista:
     * {@link InventarioService} ya la leyó con bloqueo y, si faltaba, lanzó 404 antes.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @param cantidad       saldo nuevo, con escala 2
     * @return filas afectadas
     */
    @Modifying
    @Query(value = """
        update inventario set cantidad_disponible = :cantidad
        where id_materia_prima = :idMateriaPrima
        """, nativeQuery = true)
    int actualizarSaldo(@Param("idMateriaPrima") Integer idMateriaPrima,
                        @Param("cantidad") BigDecimal cantidad);
}
