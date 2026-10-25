package co.edu.corposucre.productionfood.receta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Acceso a la cabecera de las recetas por JDBC, sin entidad JPA (HU-12).
 *
 * <p>Se usa JDBC y no JPA porque {@code recetas} ya se consulta de forma nativa desde
 * {@code ProductoRepository} (el catálogo necesita saber si el producto tiene receta) y porque el
 * módulo trabaja con cabecera y detalle en la misma transacción. El mapeo explícito de
 * {@link RecetaRowMapper} deja los alias del {@code JOIN} como única fuente de las proyecciones de
 * lectura.
 */
@Repository
public class RecetaRepository {

    /**
     * Columnas enumeradas y con alias, compartidas por todas las lecturas para que el detalle de
     * una receta coincida fila a fila con lo que muestra el listado. {@code r.estado} viaja como
     * {@code activo} porque es el nombre que usa el contrato de HU-12.
     */
    private static final String SELECT = """
            SELECT r.id_receta, r.id_producto, p.nombre AS nombre_producto, r.nombre,
                   r.cantidad_producir, r.id_unidad, u.nombre AS nombre_unidad,
                   u.abreviatura AS abreviatura_unidad, r.estado AS activo
            FROM recetas r
            JOIN productos p ON p.id_producto = r.id_producto
            JOIN unidades_medida u ON u.id_unidad = r.id_unidad
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public RecetaRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una receta con los datos de su producto y de su unidad de producción, por id.
     *
     * <p>Se resuelve con {@code query(...).stream().findFirst()} en vez de {@code queryForObject}
     * para que la ausencia sea un {@link Optional#empty()} y no una {@code EmptyResultDataAccessException}
     * que el servicio tendría que traducir.
     *
     * @param id identificador de la receta
     * @return la receta con su producto y su unidad, o vacío si no existe
     */
    public Optional<Receta> buscarPorId(Integer id) {
        var params = new MapSqlParameterSource().addValue("id", id);
        return jdbc.query(SELECT + " WHERE r.id_receta = :id", params, new RecetaRowMapper())
                .stream().findFirst();
    }

    /**
     * Devuelve la receta de un producto, si la tiene (CA-01).
     *
     * @param idProducto identificador del producto
     * @return la receta del producto, o vacío si el producto no tiene receta
     */
    public Optional<Receta> buscarPorProducto(Integer idProducto) {
        var params = new MapSqlParameterSource().addValue("idProducto", idProducto);
        return jdbc.query(SELECT + " WHERE r.id_producto = :idProducto", params,
                new RecetaRowMapper()).stream().findFirst();
    }

    /**
     * Indica si el producto ya tiene una receta, sin traerla.
     *
     * <p>Cuenta sobre {@code recetas} a secas y no sobre el {@code SELECT} con {@code JOIN}: los
     * filtros de esta comprobación son solo de la cabecera. Lo usa el servicio para responder
     * {@code PRODUCTO_YA_TIENE_RECETA} antes de intentar el {@code INSERT}; el
     * {@code UNIQUE KEY id_producto} de V1 es la garantía ante dos altas simultáneas (CA-01).
     *
     * @param idProducto identificador del producto
     * @return {@code true} si el producto ya tiene receta, esté activa o no
     */
    public boolean existePorProducto(Integer idProducto) {
        var params = new MapSqlParameterSource().addValue("idProducto", idProducto);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recetas WHERE id_producto = :idProducto",
                params, Long.class);
        return total != null && total > 0;
    }

    /**
     * Inserta una receta y devuelve el identificador que asignó la base.
     *
     * <p>Con {@link GeneratedKeyHolder} el {@code id_receta} se recupera del {@code INSERT} y no
     * de una segunda consulta. La columna {@code estado} se envía explícitamente, igual que en el
     * alta de productos: el {@code DEFAULT 1} de V2 es la red de seguridad para las inserciones
     * hechas fuera de la aplicación, no el mecanismo.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio que crea la cabecera
     * y su detalle, para que un fallo en un ingrediente no deje una receta a medias.
     *
     * @param receta receta a persistir
     * @return identificador asignado a la receta
     */
    public Integer insertar(Receta receta) {
        var params = new MapSqlParameterSource()
                .addValue("idProducto", receta.getIdProducto())
                .addValue("nombre", receta.getNombre())
                .addValue("cantidadProducir", receta.getCantidadProducir())
                .addValue("idUnidad", receta.getIdUnidad())
                .addValue("activo", receta.getActivo());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO recetas (id_producto, nombre, cantidad_producir, id_unidad, estado)
                VALUES (:idProducto, :nombre, :cantidadProducir, :idUnidad, :activo)
                """, params, generadas, new String[] {"id_receta"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException("La base no devolvió el id de la receta insertada.");
        }
        return id;
    }

    /**
     * Extrae la llave generada como {@link Integer}.
     *
     * <p>El conector de MySQL devuelve la llave como {@code BigInteger}; pedirla con
     * {@code getKeyAs(Integer.class)} lanza {@code DataRetrievalFailureException} porque la
     * conversión de tipos no está soportada. Se toma como {@link Number} y se reduce al tipo de la
     * columna.
     */
    private static Integer claveGenerada(GeneratedKeyHolder generadas) {
        Number clave = generadas.getKey();
        return clave == null ? null : clave.intValue();
    }

    /**
     * Actualiza los campos editables de la cabecera.
     *
     * <p>No toca {@code id_producto}: CA-01 ata la receta a un producto concreto y el contrato de
     * HU-12 no lo expone como editable. Tampoco toca {@code estado}: para eso está
     * {@link #cambiarEstado(Integer, boolean)}.
     *
     * @param receta receta con los valores nuevos; debe traer {@code idReceta}
     */
    public void actualizar(Receta receta) {
        var params = new MapSqlParameterSource()
                .addValue("idReceta", receta.getIdReceta())
                .addValue("nombre", receta.getNombre())
                .addValue("cantidadProducir", receta.getCantidadProducir())
                .addValue("idUnidad", receta.getIdUnidad());

        jdbc.update("""
                UPDATE recetas
                SET nombre = :nombre,
                    cantidad_producir = :cantidadProducir,
                    id_unidad = :idUnidad
                WHERE id_receta = :idReceta
                """, params);
    }

    /**
     * Activa o desactiva una receta.
     *
     * @param id     identificador de la receta
     * @param activo estado nuevo
     */
    public void cambiarEstado(Integer id, boolean activo) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("activo", activo);

        jdbc.update("UPDATE recetas SET estado = :activo WHERE id_receta = :id", params);
    }

    /**
     * Devuelve una página de recetas con los datos de su producto y de su unidad de producción.
     *
     * <p>{@code columnaOrden} no se recibe aparte: el orden viaja en el {@link Pageable} y el
     * servicio lo resuelve contra su lista blanca antes de llamar aquí, de modo que concatenar la
     * propiedad es seguro. Si llegara una propiedad que no es columna SQL, sería un error de
     * programación del llamante y produciría un SQL inválido, no un orden silenciosamente
     * distinto.
     *
     * @param nombre   patrón de prefijo para el {@code LIKE} ya normalizado y escapado por
     *                 {@code FiltroTexto}, o {@code null} para no filtrar
     * @param activo   estado buscado o {@code null} para no filtrar
     * @param pageable paginación y orden ya validados por el llamador
     * @return recetas de la página, con su producto y su unidad
     */
    public List<Receta> listar(String nombre, Boolean activo, Pageable pageable) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(nombre, activo, params);
        params.addValue("tamano", pageable.getPageSize())
                .addValue("desplazamiento", pageable.getOffset());

        var sql = SELECT + where + armarOrden(pageable)
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, new RecetaRowMapper());
    }

    /**
     * Cuenta las recetas que cumplen los filtros, sin traerlas.
     *
     * <p>El total de la paginación sale de aquí y no del tamaño de la página: una página con 20
     * filas puede representar miles de recetas. No repite los {@code JOIN}: los filtros tocan solo
     * la cabecera y los {@code JOIN} de {@link #listar} son {@code INNER} sobre llaves foráneas
     * {@code NOT NULL}, así que no descartan filas y el total coincide con la página.
     *
     * @param nombre patrón de prefijo para el {@code LIKE} ya normalizado, o {@code null}
     * @param activo estado buscado o {@code null} para no filtrar
     */
    public long contar(String nombre, Boolean activo) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(nombre, activo, params);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recetas r " + where, params, Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Fragmento {@code WHERE} compartido por {@link #listar} y {@link #contar}, para que ambos
     * apliquen exactamente los mismos filtros y el total no diverja de la página.
     *
     * <p>Solo se concatenan fragmentos constantes; los valores viajan como parámetros con nombre.
     * El {@code ESCAPE} lleva cuatro barras en Java (dos en el SQL final) porque el literal va
     * crudo al driver y con una sola barra el conector MySQL interpreta {@code \'} como comilla
     * escapada y deja el string abierto.
     */
    private static String armarFiltros(String nombre, Boolean activo, MapSqlParameterSource params) {
        params.addValue("nombre", nombre);
        params.addValue("activo", activo);

        return """
                WHERE (:nombre IS NULL OR r.nombre LIKE :nombre ESCAPE '\\\\')
                  AND (:activo IS NULL OR r.estado = :activo)
                """;
    }

    /**
     * Traduce el orden del {@link Pageable} a un {@code ORDER BY}.
     *
     * <p>Cada propiedad del orden es una columna SQL ya resuelta por la lista blanca del servicio.
     */
    private static String armarOrden(Pageable pageable) {
        List<String> orden = new ArrayList<>();
        for (Sort.Order campo : pageable.getSort()) {
            orden.add(campo.getProperty() + (campo.isAscending() ? " ASC" : " DESC"));
        }
        return orden.isEmpty() ? "" : " ORDER BY " + String.join(", ", orden);
    }
}
