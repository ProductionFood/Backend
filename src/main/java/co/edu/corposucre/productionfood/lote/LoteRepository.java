package co.edu.corposucre.productionfood.lote;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio de la tabla {@code lotes}, compartida por los lotes de materia prima (HU-09) y los de
 * producto terminado (HU-19).
 *
 * <p>Cada historia consulta solo su mitad: las consultas de materia prima exigen
 * {@code l.id_materia_prima is not null} y las de producto, {@code l.id_producto is not null}
 * (R-06). Ninguna mezcla ambas poblaciones.
 */
public interface LoteRepository extends JpaRepository<Lote, Integer> {

    /**
     * Inserta un lote y fuerza el {@code flush} para que la base asigne el identificador.
     *
     * <p>Con {@link jakarta.persistence.GenerationType#IDENTITY} el valor de {@code id_lote} solo
     * existe después del {@code INSERT}, y el servicio lo necesita para registrar el movimiento de
     * kardex con {@code tabla_origen = 'lotes'} e {@code id_origen} (R-01). Un {@code save} sin
     * {@code flush} dejaría el id en {@code null} hasta el cierre de la transacción.
     *
     * @param lote lote a persistir
     * @return el mismo lote con su identificador asignado
     */
    default Lote insertar(Lote lote) {
        return saveAndFlush(lote);
    }

    /**
     * Indica si ya existe un lote con el código dado.
     *
     * <p>Lo usa el servicio para responder {@code CODIGO_LOTE_DUPLICADO} antes de intentar el
     * {@code INSERT}. El código debe llegar ya normalizado: la collation de la columna es
     * <em>case-insensitive</em>, así que comparar en mayúsculas evita que dos variantes de
     * capitalización burlen la comprobación (R-06).
     *
     * @param codigoLote código del lote ya normalizado
     * @return {@code true} si el código está en uso
     */
    boolean existsByCodigoLote(String codigoLote);

    /**
     * Devuelve un lote de materia prima con el nombre y la unidad de su materia prima, por id.
     *
     * <p>Repite el mismo {@code select} y los mismos {@code join} que
     * {@link #listarConDetalle(Integer, Boolean, Integer, Pageable)} para que el detalle de un solo
     * lote coincida fila a fila con lo que muestra el listado. Al no paginarse no necesita
     * {@code countQuery}. El filtro {@code l.id_materia_prima is not null} deja fuera los lotes de
     * producto terminado (HU-19), que no pertenecen a este endpoint; si el id no existe o es de un
     * lote de producto, el {@code Optional} llega vacío.
     *
     * @param id identificador del lote
     * @return el lote con los datos de su materia prima, o vacío si no existe
     */
    @Query(value = """
        select l.id_lote as idLote,
               l.codigo_lote as codigoLote,
               l.id_materia_prima as idMateriaPrima,
               mp.nombre as materiaPrimaNombre,
               mp.id_unidad as idUnidad,
               u.nombre as unidadNombre,
               u.abreviatura as unidadAbreviatura,
               l.fecha_produccion as fechaProduccion,
               l.fecha_vencimiento as fechaVencimiento,
               l.cantidad as cantidad
        from lotes l
        join materias_primas mp on mp.id_materia_prima = l.id_materia_prima
        join unidades_medida u on u.id_unidad = mp.id_unidad
        where l.id_lote = :id
          and l.id_materia_prima is not null
        """, nativeQuery = true)
    Optional<LoteConDetalle> buscarDetallePorId(@Param("id") Integer id);

    /**
     * Búsqueda paginada de lotes de materia prima con los datos de su materia prima.
     *
     * <p>El {@code where} siempre exige {@code l.id_materia_prima is not null}: este listado es de
     * lotes de materia prima y no debe mezclar los de producto terminado de HU-19. Los demás
     * filtros son opcionales y se omiten con {@code null}.
     *
     * <p>{@code soloVencidos} viaja como {@code null} cuando no se filtra y como {@code TRUE}
     * cuando sí: el servicio normaliza el parámetro y nunca pasa {@code FALSE}, porque
     * {@code FALSE} no significa «no filtrar» sino «exigir no vencido» y excluiría los lotes
     * vigentes junto con los vencidos. Con {@code TRUE} la comparación contra {@code curdate()}
     * marca los lotes ya vencidos, que se permiten registrar (R-05).
     *
     * <p>{@code proximosAVencer} son los días de la ventana: se traen los lotes cuya fecha de
     * vencimiento cae entre hoy y hoy más esos días, ambos extremos incluidos.
     *
     * <p>Es una consulta nativa porque sus alias alimentan la proyección {@link LoteConDetalle} y
     * usa funciones de fecha de MySQL ({@code curdate}, {@code date_add}). El {@code countQuery}
     * repite exactamente los {@code join} y la cláusula {@code where}, sin {@code order by}: si
     * divergieran, {@code totalElements} contaría una población distinta de la que se pagina.
     *
     * @param idMateriaPrima   materia prima buscada o {@code null} para no filtrar
     * @param soloVencidos     {@code TRUE} para traer solo vencidos, {@code null} para no filtrar
     * @param proximosAVencer  días de la ventana de vencimiento próximo, o {@code null} para no
     *                         filtrar
     * @param pageable         paginación y orden ya validados por el llamador contra su lista
     *                         blanca
     * @return página de lotes que cumplen los filtros
     */
    @Query(value = """
        select l.id_lote as idLote,
               l.codigo_lote as codigoLote,
               l.id_materia_prima as idMateriaPrima,
               mp.nombre as materiaPrimaNombre,
               mp.id_unidad as idUnidad,
               u.nombre as unidadNombre,
               u.abreviatura as unidadAbreviatura,
               l.fecha_produccion as fechaProduccion,
               l.fecha_vencimiento as fechaVencimiento,
               l.cantidad as cantidad
        from lotes l
        join materias_primas mp on mp.id_materia_prima = l.id_materia_prima
        join unidades_medida u on u.id_unidad = mp.id_unidad
        where l.id_materia_prima is not null
          and (:idMateriaPrima is null or l.id_materia_prima = :idMateriaPrima)
          and (:soloVencidos is null or l.fecha_vencimiento < curdate())
          and (:proximosAVencer is null
               or (l.fecha_vencimiento >= curdate()
                   and l.fecha_vencimiento <= date_add(curdate(), interval :proximosAVencer day)))
        """,
        countQuery = """
        select count(*)
        from lotes l
        join materias_primas mp on mp.id_materia_prima = l.id_materia_prima
        join unidades_medida u on u.id_unidad = mp.id_unidad
        where l.id_materia_prima is not null
          and (:idMateriaPrima is null or l.id_materia_prima = :idMateriaPrima)
          and (:soloVencidos is null or l.fecha_vencimiento < curdate())
          and (:proximosAVencer is null
               or (l.fecha_vencimiento >= curdate()
                   and l.fecha_vencimiento <= date_add(curdate(), interval :proximosAVencer day)))
        """, nativeQuery = true)
    Page<LoteConDetalle> listarConDetalle(@Param("idMateriaPrima") Integer idMateriaPrima,
                                          @Param("soloVencidos") Boolean soloVencidos,
                                          @Param("proximosAVencer") Integer proximosAVencer,
                                          Pageable pageable);

    /**
     * Devuelve un lote de producto terminado con el nombre y la unidad de su producto, por id.
     *
     * <p>Repite el mismo {@code select} y los mismos {@code join} que
     * {@link #listarProductos(Integer, Boolean, Integer, Boolean, Pageable)} para que el detalle de
     * un solo lote coincida fila a fila con lo que muestra el listado. Al no paginarse no necesita
     * {@code countQuery}.
     *
     * <p>{@code l.cantidad} se expone como {@code cantidadIngresada} y el saldo del lote como
     * {@code cantidadDisponible}: son datos distintos y confundirlos devuelve siempre la cantidad
     * original, como si no se hubiera vendido nada (R-03). Los {@code LEFT JOIN} con
     * {@code COALESCE 0} cubren el caso de un lote sin fila de inventario, que no debe romper la
     * consulta. {@code stockTotalProducto} es el agregado del producto en
     * {@code v_stock_producto_terminado}, que excluye los lotes vencidos (R-04).
     *
     * <p>El filtro {@code l.id_producto is not null} deja fuera los lotes de materia prima; si el
     * id no existe o es de un lote de materia prima, el {@code Optional} llega vacío.
     *
     * @param id identificador del lote
     * @return el lote con los datos de su producto, o vacío si no existe
     */
    @Query(value = """
        select l.id_lote as idLote,
               l.codigo_lote as codigoLote,
               l.id_producto as idProducto,
               p.nombre as productoNombre,
               p.id_unidad as idUnidad,
               u.nombre as unidadNombre,
               u.abreviatura as unidadAbreviatura,
               l.fecha_produccion as fechaProduccion,
               l.fecha_vencimiento as fechaVencimiento,
               l.cantidad as cantidadIngresada,
               coalesce(ipt.cantidad_disponible, 0) as cantidadDisponible,
               coalesce(v.cantidad_disponible, 0) as stockTotalProducto
        from lotes l
        join productos p on p.id_producto = l.id_producto
        join unidades_medida u on u.id_unidad = p.id_unidad
        left join inventario_producto_terminado ipt on ipt.id_lote = l.id_lote
        left join v_stock_producto_terminado v on v.id_producto = l.id_producto
        where l.id_lote = :id
          and l.id_producto is not null
        """, nativeQuery = true)
    Optional<LoteProductoConDetalle> buscarProductoPorId(@Param("id") Integer id);

    /**
     * Búsqueda paginada de lotes de producto terminado con los datos de su producto y su saldo.
     *
     * <p>El {@code where} siempre exige {@code l.id_producto is not null}: este listado es de lotes
     * de producto terminado y no debe mezclar los de materia prima de HU-09. Los demás filtros son
     * opcionales y se omiten con {@code null}.
     *
     * <p>{@code soloVencidos} viaja como {@code null} cuando no se filtra y como {@code TRUE}
     * cuando sí: {@code FALSE} no significa «no filtrar» sino «exigir vencido», así que el servicio
     * normaliza el parámetro y nunca pasa {@code FALSE}.
     * {@code proximosAVencer} son los días de la ventana: se traen los lotes cuya fecha de
     * vencimiento cae entre hoy y hoy más esos días, ambos extremos incluidos.
     * {@code conStock} exige saldo mayor que cero en el lote ({@code coalesce} cubre la ausencia de
     * fila de inventario); igual que {@code soloVencidos}, el servicio pasa {@code TRUE} o
     * {@code null}.
     *
     * <p>Es una consulta nativa porque sus alias alimentan la proyección
     * {@link LoteProductoConDetalle} y usa funciones de fecha de MySQL ({@code curdate},
     * {@code date_add}). El orden no se escribe aquí: viaja en el {@link Pageable} y el servicio lo
     * resuelve contra su lista blanca, de modo que las propiedades de orden son los alias de la
     * proyección ({@code idLote}, {@code codigoLote}, {@code fechaProduccion},
     * {@code fechaVencimiento}). El {@code countQuery} repite exactamente los {@code join} y la
     * cláusula {@code where}, sin {@code order by}: si divergieran, {@code totalElements} contaría
     * una población distinta de la que se pagina.
     *
     * @param idProducto      producto buscado o {@code null} para no filtrar
     * @param soloVencidos    {@code TRUE} para traer solo vencidos, {@code null} para no filtrar
     * @param proximosAVencer días de la ventana de vencimiento próximo, o {@code null} para no
     *                        filtrar
     * @param conStock        {@code TRUE} para traer solo lotes con saldo, {@code null} para no
     *                        filtrar
     * @param pageable        paginación y orden ya validados por el llamador contra su lista
     *                        blanca
     * @return página de lotes que cumplen los filtros
     */
    @Query(value = """
        select l.id_lote as idLote,
               l.codigo_lote as codigoLote,
               l.id_producto as idProducto,
               p.nombre as productoNombre,
               p.id_unidad as idUnidad,
               u.nombre as unidadNombre,
               u.abreviatura as unidadAbreviatura,
               l.fecha_produccion as fechaProduccion,
               l.fecha_vencimiento as fechaVencimiento,
               l.cantidad as cantidadIngresada,
               coalesce(ipt.cantidad_disponible, 0) as cantidadDisponible,
               coalesce(v.cantidad_disponible, 0) as stockTotalProducto
        from lotes l
        join productos p on p.id_producto = l.id_producto
        join unidades_medida u on u.id_unidad = p.id_unidad
        left join inventario_producto_terminado ipt on ipt.id_lote = l.id_lote
        left join v_stock_producto_terminado v on v.id_producto = l.id_producto
        where l.id_producto is not null
          and (:idProducto is null or l.id_producto = :idProducto)
          and (:soloVencidos is null or l.fecha_vencimiento < curdate())
          and (:proximosAVencer is null
               or (l.fecha_vencimiento >= curdate()
                   and l.fecha_vencimiento <= date_add(curdate(), interval :proximosAVencer day)))
          and (:conStock is null or coalesce(ipt.cantidad_disponible, 0) > 0)
        """,
        countQuery = """
        select count(*)
        from lotes l
        join productos p on p.id_producto = l.id_producto
        join unidades_medida u on u.id_unidad = p.id_unidad
        left join inventario_producto_terminado ipt on ipt.id_lote = l.id_lote
        left join v_stock_producto_terminado v on v.id_producto = l.id_producto
        where l.id_producto is not null
          and (:idProducto is null or l.id_producto = :idProducto)
          and (:soloVencidos is null or l.fecha_vencimiento < curdate())
          and (:proximosAVencer is null
               or (l.fecha_vencimiento >= curdate()
                   and l.fecha_vencimiento <= date_add(curdate(), interval :proximosAVencer day)))
          and (:conStock is null or coalesce(ipt.cantidad_disponible, 0) > 0)
        """, nativeQuery = true)
    Page<LoteProductoConDetalle> listarProductos(@Param("idProducto") Integer idProducto,
                                                 @Param("soloVencidos") Boolean soloVencidos,
                                                 @Param("proximosAVencer") Integer proximosAVencer,
                                                 @Param("conStock") Boolean conStock,
                                                 Pageable pageable);

    /**
     * Indica si el lote existe y es de producto terminado.
     *
     * <p>Lo usa el controlador de producto terminado para distinguir un id inexistente de un lote
     * de materia prima: ambos responden {@code RECURSO_NO_ENCONTRADO}, pero solo el segundo existe
     * en la tabla compartida. Es JPQL y no SQL porque no proyecta columnas: basta con contar sobre
     * la entidad.
     *
     * @param id identificador del lote
     * @return {@code true} si el lote existe y tiene {@code id_producto} informado
     */
    @Query("select count(l) > 0 from Lote l where l.idLote = :id and l.idProducto is not null")
    boolean esLoteProducto(@Param("id") Integer id);
}
