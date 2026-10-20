package co.edu.corposucre.productionfood.unidadmedida;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repositorio del catálogo de unidades de medida (HU-07).
 */
public interface UnidadMedidaRepository extends JpaRepository<UnidadMedida, Integer> {

    /**
     * Lista el catálogo completo de unidades de medida con los conteos de uso.
     *
     * <p>Es una consulta nativa porque {@code materias_primas}, {@code productos} y {@code recetas}
     * todavía no tienen entidad JPA. Los tres conteos se resuelven con subconsultas escalares
     * dentro de la misma sentencia para no multiplicar las viagens a la base de datos.
     *
     * @return unidades de medida ordenadas por nombre, con sus conteos de uso
     */
    @Query(value = """
        select u.id_unidad as idUnidad,
               u.nombre as nombre,
               u.abreviatura as abreviatura,
               (select count(*) from materias_primas mp
                 where mp.id_unidad = u.id_unidad) as enMateriasPrimas,
               (select count(*) from productos p
                 where p.id_unidad = u.id_unidad) as enProductos,
               (select count(*) from recetas r
                 where r.id_unidad = u.id_unidad) as enRecetas
        from unidades_medida u
        order by u.nombre
        """, nativeQuery = true)
    List<UnidadMedidaConUsos> listarConUsos();

    /**
     * Devuelve una unidad de medida con los mismos conteos de uso que {@link #listarConUsos()}.
     *
     * @param idUnidad identificador de la unidad de medida
     * @return la unidad con sus conteos de uso, o vacío si no existe
     */
    @Query(value = """
        select u.id_unidad as idUnidad,
               u.nombre as nombre,
               u.abreviatura as abreviatura,
               (select count(*) from materias_primas mp
                 where mp.id_unidad = u.id_unidad) as enMateriasPrimas,
               (select count(*) from productos p
                 where p.id_unidad = u.id_unidad) as enProductos,
               (select count(*) from recetas r
                 where r.id_unidad = u.id_unidad) as enRecetas
        from unidades_medida u
        where u.id_unidad = :idUnidad
        """, nativeQuery = true)
    Optional<UnidadMedidaConUsos> buscarConUsos(@Param("idUnidad") Integer idUnidad);

    /**
     * Indica si ya existe una unidad de medida con la abreviatura dada.
     *
     * <p>La comparación hereda la collation {@code utf8mb4_0900_ai_ci} de la columna, así que
     * {@code kg} encuentra {@code KG}: es la regla R-02 de la HU-07, que el duplicado se detecte
     * sin distinguir mayúsculas de minúsculas.
     *
     * @param abreviatura abreviatura buscada
     * @return {@code true} si la abreviatura ya está en el catálogo
     */
    boolean existsByAbreviatura(String abreviatura);

    /**
     * Igual que {@link #existsByAbreviatura(String)} pero excluyendo la propia unidad del
     * catálogo.
     *
     * <p>Sirve para validar una edición: si la abreviatura no cambia, la unidad no debe
     * reportarse como duplicada contra sí misma.
     *
     * @param abreviatura abreviatura buscada
     * @param idUnidad    identificador de la unidad que se está editando
     * @return {@code true} si existe otra unidad con esa abreviatura
     */
    boolean existsByAbreviaturaAndIdUnidadNot(String abreviatura, Integer idUnidad);
}
