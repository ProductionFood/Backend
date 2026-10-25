package co.edu.corposucre.productionfood.receta;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Acceso al detalle de las recetas por JDBC, sin entidad JPA (HU-12).
 *
 * <p>Es el único punto del proyecto que <strong>elimina</strong> filas: un ingrediente quitado de
 * una receta no tiene valor histórico y {@code detalle_receta} no está referenciada por ninguna
 * otra tabla (R-07).
 */
@Repository
public class DetalleRecetaRepository {

    /**
     * Columnas enumeradas y con alias, compartidas por todas las lecturas. El {@code JOIN} a
     * {@code materias_primas} aporta el nombre y el costo unitario de referencia con el que PR3
     * calcula el costo estimado; el {@code JOIN} a {@code unidades_medida} aporta la abreviatura de
     * la unidad de la materia prima.
     */
    private static final String SELECT = """
            SELECT d.id_detalle_receta, d.id_receta, d.id_materia_prima,
                   mp.nombre AS nombre_materia_prima, u.abreviatura AS abreviatura_unidad,
                   d.cantidad, mp.costo_unitario
            FROM detalle_receta d
            JOIN materias_primas mp ON mp.id_materia_prima = d.id_materia_prima
            JOIN unidades_medida u ON u.id_unidad = mp.id_unidad
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public DetalleRecetaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve el detalle completo de una receta, en el orden en que se agregaron los ingredientes.
     *
     * <p>El {@code ORDER BY id_detalle_receta} hace determinista la respuesta: sin él, la base
     * podría devolver las líneas en cualquier orden y obtener la misma receta dos veces mostraría
     * los ingredientes en distinto orden.
     *
     * @param idReceta identificador de la receta
     * @return líneas de detalle de la receta; vacía si la receta no tiene ingredientes
     */
    public List<DetalleReceta> listarPorReceta(Integer idReceta) {
        var params = new MapSqlParameterSource().addValue("idReceta", idReceta);
        return jdbc.query(SELECT + " WHERE d.id_receta = :idReceta ORDER BY d.id_detalle_receta",
                params, new DetalleRecetaRowMapper());
    }

    /**
     * Devuelve el detalle de varias recetas en una sola consulta.
     *
     * <p>Existe para que el listado paginado no ejecute una consulta por fila: el servicio trae
     * todas las líneas de la página con un solo {@code IN} y las agrupa en memoria por
     * {@code idReceta}.
     *
     * @param idsRecetas identificadores de las recetas de la página
     * @return líneas de detalle de todas esas recetas; vacía si la lista viene vacía
     */
    public List<DetalleReceta> listarPorRecetas(List<Integer> idsRecetas) {
        if (idsRecetas == null || idsRecetas.isEmpty()) {
            return List.of();
        }
        var params = new MapSqlParameterSource().addValue("idsRecetas", idsRecetas);
        return jdbc.query(
                SELECT + " WHERE d.id_receta IN (:idsRecetas) ORDER BY d.id_detalle_receta",
                params, new DetalleRecetaRowMapper());
    }

    /**
     * Devuelve una línea de detalle con los datos de su materia prima, por id.
     *
     * @param idDetalle identificador de la línea de detalle
     * @return la línea, o vacío si no existe
     */
    public Optional<DetalleReceta> buscarPorId(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("idDetalle", idDetalle);
        return jdbc.query(SELECT + " WHERE d.id_detalle_receta = :idDetalle", params,
                new DetalleRecetaRowMapper()).stream().findFirst();
    }

    /**
     * Inserta una línea de detalle y devuelve el identificador que asignó la base.
     *
     * <p>El {@code UNIQUE KEY uk_detalle_receta (id_receta, id_materia_prima)} de V2 es la
     * garantía de CA-05: el servicio comprueba antes para dar un 409 legible, pero dos peticiones
     * simultáneas pueden pasar ambas la comprobación y solo la base las detiene (R-01).
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio, igual que en el
     * alta de la cabecera.
     *
     * @param detalle línea a persistir
     * @return identificador asignado a la línea
     */
    public Integer insertar(DetalleReceta detalle) {
        var params = new MapSqlParameterSource()
                .addValue("idReceta", detalle.getIdReceta())
                .addValue("idMateriaPrima", detalle.getIdMateriaPrima())
                .addValue("cantidad", detalle.getCantidad());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO detalle_receta (id_receta, id_materia_prima, cantidad)
                VALUES (:idReceta, :idMateriaPrima, :cantidad)
                """, params, generadas, new String[] {"id_detalle_receta"});

        Integer id = generadas.getKeyAs(Integer.class);
        if (id == null) {
            throw new IllegalStateException("La base no devolvió el id de la línea insertada.");
        }
        return id;
    }

    /**
     * Reemplaza la cantidad de una línea de detalle.
     *
     * <p>La cantidad debe llegar con escala 3, la de la columna (R-06). No cambia la materia prima
     * de la línea: para eso se elimina y se agrega otra, porque el {@code UNIQUE} de la receta
     * prohíbe repetir un ingrediente (CA-05).
     *
     * @param idDetalle identificador de la línea
     * @param cantidad  cantidad nueva
     */
    public void actualizarCantidad(Integer idDetalle, BigDecimal cantidad) {
        var params = new MapSqlParameterSource()
                .addValue("idDetalle", idDetalle)
                .addValue("cantidad", cantidad);

        jdbc.update("""
                UPDATE detalle_receta
                SET cantidad = :cantidad
                WHERE id_detalle_receta = :idDetalle
                """, params);
    }

    /**
     * Elimina una línea de detalle (R-07).
     *
     * @param idDetalle identificador de la línea
     */
    public void eliminar(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("idDetalle", idDetalle);
        jdbc.update("DELETE FROM detalle_receta WHERE id_detalle_receta = :idDetalle", params);
    }

    /**
     * Indica si la materia prima ya está en la receta (CA-05).
     *
     * <p>Lo usa el servicio para responder {@code MATERIA_PRIMA_DUPLICADA} antes de intentar el
     * {@code INSERT}; el {@code UNIQUE KEY uk_detalle_receta} es la garantía ante dos peticiones
     * simultáneas (R-01).
     *
     * @param idReceta       identificador de la receta
     * @param idMateriaPrima identificador de la materia prima
     * @return {@code true} si la materia prima ya es un ingrediente de la receta
     */
    public boolean existeMateriaPrima(Integer idReceta, Integer idMateriaPrima) {
        var params = new MapSqlParameterSource()
                .addValue("idReceta", idReceta)
                .addValue("idMateriaPrima", idMateriaPrima);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM detalle_receta
                WHERE id_receta = :idReceta AND id_materia_prima = :idMateriaPrima
                """, params, Long.class);
        return total != null && total > 0;
    }
}
