package co.edu.corposucre.productionfood.materiaprima;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de materias primas (HU-08).
 */
public interface MateriaPrimaRepository extends JpaRepository<MateriaPrima, Integer> {

    /**
     * Búsqueda paginada de materias primas con filtro opcional por nombre y por estado.
     *
     * <p>El {@code countQuery} repite exactamente la cláusula {@code where} del {@code value}: si
     * divergieran, {@code totalElements} contaría una población distinta de la que se pagina y el
     * total mostrado mentiría sobre cuántas materias primas cumplen el filtro.
     *
     * @param nombre  texto buscado por prefijo (con comodín) o {@code null} para no filtrar
     * @param activo  estado buscado o {@code null} para no filtrar
     * @param pageable paginación y orden ya validados por el llamador contra su lista blanca
     * @return página de materias primas que cumplen los filtros
     */
    @Query(value = """
        select m from MateriaPrima m
        where (:nombre is null or m.nombre like :nombre escape '\\')
          and (:activo is null or m.estado = :activo)
        """,
        countQuery = """
        select count(m) from MateriaPrima m
        where (:nombre is null or m.nombre like :nombre escape '\\')
          and (:activo is null or m.estado = :activo)
        """)
    Page<MateriaPrima> buscar(@Param("nombre") String nombre,
                              @Param("activo") Boolean activo,
                              Pageable pageable);

    /**
     * Indica si existe una materia prima activa con el identificador dado.
     *
     * <p>Lo usarán HU-12, HU-14 y HU-22 para validar que la materia prima referenciada por una
     * compra, un detalle de receta o un movimiento de inventario esté activa; el estado no debe
     * consultarse por otra vía para no duplicar la regla.
     *
     * @param id identificador de la materia prima
     * @return {@code true} si la materia prima existe y está activa
     */
    @Query("select count(m) > 0 from MateriaPrima m where m.idMateriaPrima = :id and m.estado = true")
    boolean existeActiva(@Param("id") Integer id);

    /**
     * Cuenta en cuántas recetas activas aparece la materia prima en {@code detalle_receta}.
     *
     * <p>Es una consulta nativa porque {@code detalle_receta} y {@code recetas} todavía no tienen
     * entidad JPA. Cuando exista la entidad de recetas esta cuenta debe migrarse al repositorio
     * de recetas: mientras tanto, el SQL queda atado al esquema y no al modelo, igual que
     * {@code ClienteRepository.contarPedidosEnCurso}.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @return número de recetas activas que usan la materia prima
     */
    @Query(value = """
        select count(*) from detalle_receta dr
        join recetas r on r.id_receta = dr.id_receta
        where dr.id_materia_prima = :id and r.estado = 1
        """, nativeQuery = true)
    long contarRecetasActivas(@Param("id") Integer idMateriaPrima);

    /**
     * Búsqueda paginada de materias primas con su unidad de medida y su cantidad disponible.
     *
     * <p>La unidad se trae con un {@code join} a {@code unidades_medida} (nombre y abreviatura)
     * y el stock con un {@code left join} a {@code inventario} envuelto en {@code COALESCE}: una
     * materia prima sin fila de inventario (transitoria) se reporta con stock 0 en lugar de
     * desaparecer del listado. Es una consulta nativa por el mismo motivo que
     * {@link #contarRecetasActivas(Integer)} y sus alias alimentan la proyección
     * {@link MateriaPrimaConDetalle}.
     *
     * <p>El {@code countQuery} repite exactamente los {@code join} y la cláusula {@code where}
     * del {@code value} por la misma razón que en {@link #buscar(String, Boolean, Pageable)}.
     *
     * @param nombre  texto buscado por prefijo (con comodín) o {@code null} para no filtrar
     * @param activo  estado buscado o {@code null} para no filtrar
     * @param pageable paginación y orden ya validados por el llamador contra su lista blanca
     * @return página de materias primas con su unidad y su stock
     *
     * <p>El {@code escape} lleva cuatro barras en Java (dos en la SQL final): siendo una consulta
     * NATIVA, el literal va crudo al driver y con {@code escape '\'} el conector MySQL emulado
     * interpreta {@code \'} como comilla escapada, deja el string abierto, cuenta solo 2 de los 5
     * marcadores {@code ?} y responde «Parameter index out of range (3 > 2)». En las JPQL esto no
     * ocurre porque Hibernate vuelve a emitir el literal al generar el SQL.
     */
    @Query(value = """
        select mp.id_materia_prima as idMateriaPrima,
               mp.nombre as nombre,
               mp.id_unidad as idUnidad,
               u.nombre as unidadNombre,
               u.abreviatura as unidadAbreviatura,
               mp.stock_minimo as stockMinimo,
               mp.costo_unitario as costoUnitario,
               mp.estado as estado,
               coalesce(i.cantidad_disponible, 0) as cantidadDisponible
        from materias_primas mp
        join unidades_medida u on u.id_unidad = mp.id_unidad
        left join inventario i on i.id_materia_prima = mp.id_materia_prima
        where (:nombre is null or mp.nombre like :nombre escape '\\\\')
          and (:activo is null or mp.estado = :activo)
        """,
        countQuery = """
        select count(*)
        from materias_primas mp
        join unidades_medida u on u.id_unidad = mp.id_unidad
        left join inventario i on i.id_materia_prima = mp.id_materia_prima
        where (:nombre is null or mp.nombre like :nombre escape '\\\\')
          and (:activo is null or mp.estado = :activo)
        """, nativeQuery = true)
    Page<MateriaPrimaConDetalle> listarConDetalle(@Param("nombre") String nombre,
                                                  @Param("activo") Boolean activo,
                                                  Pageable pageable);

    /**
     * Devuelve una materia prima con su unidad de medida y su cantidad disponible, por id.
     *
     * <p>Repite los mismos {@code join}, {@code COALESCE} y alias que
     * {@link #listarConDetalle(String, Boolean, Pageable)} para que el detalle de una sola
     * materia prima coincida fila a fila con lo que muestra el listado. Al no paginarse no
     * necesita {@code countQuery}: si el id no existe, el {@code join} a
     * {@code unidades_medida} no produce ninguna fila y el {@code Optional} llega vacío.
     *
     * @param id identificador de la materia prima
     * @return la materia prima con su unidad y su stock, o vacío si no existe
     */
    @Query(value = """
        select mp.id_materia_prima as idMateriaPrima,
               mp.nombre as nombre,
               mp.id_unidad as idUnidad,
               u.nombre as unidadNombre,
               u.abreviatura as unidadAbreviatura,
               mp.stock_minimo as stockMinimo,
               mp.costo_unitario as costoUnitario,
               mp.estado as estado,
               coalesce(i.cantidad_disponible, 0) as cantidadDisponible
        from materias_primas mp
        join unidades_medida u on u.id_unidad = mp.id_unidad
        left join inventario i on i.id_materia_prima = mp.id_materia_prima
        where mp.id_materia_prima = :id
        """, nativeQuery = true)
    Optional<MateriaPrimaConDetalle> buscarConDetalle(@Param("id") Integer id);
}
