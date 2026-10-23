package co.edu.corposucre.productionfood.producto;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de productos terminados (HU-11).
 */
public interface ProductoRepository extends JpaRepository<Producto, Integer> {

    /**
     * Indica si existe un producto activo con el identificador dado.
     *
     * <p>Lo usarán HU-12, HU-17, HU-19 y HU-21 para validar que el producto referenciado por una
     * receta, una línea de pedido, un lote o un plan de producción esté activo (R-03, HU-17 CA-02);
     * el estado no debe consultarse por otra vía para no duplicar la regla.
     *
     * @param id identificador del producto
     * @return {@code true} si el producto existe y está activo
     */
    @Query("select count(p) > 0 from Producto p where p.idProducto = :id and p.estado = true")
    boolean existeActivo(@Param("id") Integer id);

    /**
     * Búsqueda paginada de productos con su unidad de venta, si tienen receta y su stock agregado.
     *
     * <p>La unidad se trae con un {@code join} a {@code unidades_medida} (nombre y abreviatura).
     * {@code idReceta} sale de un {@code left join} a {@code recetas}: es un {@code left} para
     * que los productos sin receta no desaparezcan del catálogo, y no multiplica filas porque
     * {@code recetas.id_producto} es UNIQUE (una receta por producto). El stock sale de un
     * {@code left join} a la vista {@code v_stock_producto_terminado}, envuelto en {@code COALESCE}
     * para que un producto sin lotes se reporte con stock 0 en lugar de {@code null}.
     *
     * <p>Es una consulta nativa porque {@code recetas} y la vista no tienen entidad JPA; sus alias
     * alimentan la proyección {@link ProductoConDetalle}. El {@code countQuery} repite exactamente
     * los {@code join} y la cláusula {@code where} del {@code value}: si divergieran,
     * {@code totalElements} contaría una población distinta de la que se pagina.
     *
     * <p>El {@code escape} lleva cuatro barras en Java (dos en la SQL final): siendo una consulta
     * NATIVA el literal va crudo al driver y con {@code escape '\'} el conector MySQL emulado
     * interpreta {@code \'} como comilla escapada, deja el string abierto, cuenta solo parte de los
     * marcadores {@code ?} y responde «Parameter index out of range». En las JPQL esto no ocurre
     * porque Hibernate vuelve a emitir el literal al generar el SQL.
     *
     * @param nombre   texto buscado por prefijo (con comodín) o {@code null} para no filtrar
     * @param activo   estado buscado o {@code null} para no filtrar
     * @param pageable paginación y orden ya validados por el llamador contra su lista blanca
     * @return página de productos con su unidad, su receta y su stock
     */
    @Query(value = """
        select p.id_producto as idProducto,
               p.nombre as nombre,
               p.descripcion as descripcion,
               p.precio as precio,
               p.id_unidad as idUnidad,
               u.nombre as unidadNombre,
               u.abreviatura as unidadAbreviatura,
               p.estado as estado,
               r.id_receta as idReceta,
               coalesce(v.cantidad_disponible, 0) as stockDisponible
        from productos p
        join unidades_medida u on u.id_unidad = p.id_unidad
        left join recetas r on r.id_producto = p.id_producto
        left join v_stock_producto_terminado v on v.id_producto = p.id_producto
        where (:nombre is null or p.nombre like :nombre escape '\\\\')
          and (:activo is null or p.estado = :activo)
        """,
        countQuery = """
        select count(*)
        from productos p
        join unidades_medida u on u.id_unidad = p.id_unidad
        left join recetas r on r.id_producto = p.id_producto
        left join v_stock_producto_terminado v on v.id_producto = p.id_producto
        where (:nombre is null or p.nombre like :nombre escape '\\\\')
          and (:activo is null or p.estado = :activo)
        """, nativeQuery = true)
    Page<ProductoConDetalle> listarConDetalle(@Param("nombre") String nombre,
                                              @Param("activo") Boolean activo,
                                              Pageable pageable);

    /**
     * Devuelve un producto con su unidad de venta, si tiene receta y su stock, por id.
     *
     * <p>Repite los mismos {@code join}, {@code COALESCE} y alias que
     * {@link #listarConDetalle(String, Boolean, Pageable)} para que el detalle de un solo producto
     * coincida fila a fila con lo que muestra el listado. Al no paginarse no necesita
     * {@code countQuery}: si el id no existe, el {@code join} a {@code unidades_medida} no produce
     * ninguna fila y el {@code Optional} llega vacío.
     *
     * @param id identificador del producto
     * @return el producto con su unidad, su receta y su stock, o vacío si no existe
     */
    @Query(value = """
        select p.id_producto as idProducto,
               p.nombre as nombre,
               p.descripcion as descripcion,
               p.precio as precio,
               p.id_unidad as idUnidad,
               u.nombre as unidadNombre,
               u.abreviatura as unidadAbreviatura,
               p.estado as estado,
               r.id_receta as idReceta,
               coalesce(v.cantidad_disponible, 0) as stockDisponible
        from productos p
        join unidades_medida u on u.id_unidad = p.id_unidad
        left join recetas r on r.id_producto = p.id_producto
        left join v_stock_producto_terminado v on v.id_producto = p.id_producto
        where p.id_producto = :id
        """, nativeQuery = true)
    Optional<ProductoConDetalle> buscarConDetalle(@Param("id") Integer id);
}
