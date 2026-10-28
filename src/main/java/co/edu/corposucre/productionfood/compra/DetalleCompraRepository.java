package co.edu.corposucre.productionfood.compra;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Persistencia JDBC del detalle de las compras ({@code detalle_compra}, HU-14).
 *
 * <p>Es la contraparte de {@code DetalleRecetaRepository} (paquete {@code receta}) para el
 * patrón cabecera-detalle de las compras: aquí se escriben las líneas, mientras
 * {@link CompraRepository} solo las lee y las cuenta para los derivados de la cabecera.
 *
 * <p>Las lecturas llevan {@code JOIN} con {@code materias_primas} y {@code unidades_medida} para
 * devolver nombre y unidad junto a cada línea, y así mostrar el detalle sin una consulta extra
 * por materia prima.
 */
@Repository
public class DetalleCompraRepository {

    /**
     * Columnas enumeradas y con alias, compartidas por todas las lecturas. El {@code JOIN} a
     * {@code materias_primas} aporta el nombre de la materia prima; el {@code JOIN} a
     * {@code unidades_medida} aporta la abreviatura con la que se muestra la unidad. Ambos son
     * seguros: {@code id_materia_prima} e {@code id_unidad} son llaves foráneas {@code NOT NULL}
     * y no descartan ninguna línea.
     */
    private static final String SELECT = """
            SELECT d.id_detalle_compra, d.id_compra, d.id_materia_prima,
                   mp.nombre AS nombre_materia_prima, u.abreviatura AS abreviatura_unidad,
                   d.cantidad, d.precio_unitario
            FROM detalle_compra d
            JOIN materias_primas mp ON mp.id_materia_prima = d.id_materia_prima
            JOIN unidades_medida u ON u.id_unidad = mp.id_unidad
            """;

    /** Mapper de la fila base; el de lectura completa la fila con las columnas del {@code JOIN}. */
    private static final DetalleCompraRowMapper FILA_BASE = new DetalleCompraRowMapper();

    /**
     * Traduce la fila unida: la parte de {@code detalle_compra} delega en {@link #FILA_BASE} y
     * el nombre y la abreviatura se leen de los alias del {@code JOIN}.
     */
    private static final RowMapper<DetalleCompra> FILA_CON_JOIN = (rs, rowNum) -> {
        var base = FILA_BASE.mapRow(rs, rowNum);
        return new DetalleCompra(base.idDetalle(), base.idCompra(), base.idMateriaPrima(),
                base.cantidad(), base.precioUnitario(),
                rs.getString("nombre_materia_prima"), rs.getString("abreviatura_unidad"));
    };

    private final NamedParameterJdbcTemplate jdbc;

    public DetalleCompraRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve las líneas de detalle de una compra, en el orden en que se agregaron.
     *
     * <p>El {@code ORDER BY id_detalle_compra} hace determinista la respuesta: sin él, la base
     * podría devolver las líneas en cualquier orden y la misma compra se mostraría con el
     * detalle revuelto entre visitas.
     *
     * @param idCompra identificador de la compra
     * @return líneas con su materia prima y unidad; vacía si la compra no tiene detalle
     */
    public List<DetalleCompra> listarPorCompra(Integer idCompra) {
        var params = new MapSqlParameterSource().addValue("idCompra", idCompra);
        return jdbc.query(SELECT + " WHERE d.id_compra = :idCompra ORDER BY d.id_detalle_compra",
                params, FILA_CON_JOIN);
    }

    /**
     * Devuelve una línea de detalle con los datos de su materia prima, por id.
     *
     * <p>La usan el PUT y el DELETE de HU-14 para comprobar que la línea existe —y, contra la
     * compra de la URL, que le pertenece— antes de tocarla.
     *
     * @param idDetalle identificador de la línea
     * @return la línea, o vacío si no existe
     */
    public Optional<DetalleCompra> obtenerPorId(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("idDetalle", idDetalle);
        return jdbc.query(SELECT + " WHERE d.id_detalle_compra = :idDetalle", params,
                FILA_CON_JOIN).stream().findFirst();
    }

    /**
     * Inserta una línea de detalle y devuelve el identificador que asignó la base.
     *
     * <p>El {@code UNIQUE KEY uk_detalle_compra (id_compra, id_materia_prima)} de V2 respalda
     * R-02: el servicio comprueba antes para devolver un {@code 409 MATERIA_PRIMA_DUPLICADA}
     * legible, pero dos peticiones simultáneas pueden pasar ambas la comprobación y solo la
     * base las detiene.
     *
     * <p>No lleva {@code @Transactional}: la transacción la abre el servicio, igual que en el
     * resto del módulo.
     *
     * @param detalle línea a persistir; debe traer compra, materia prima, cantidad y precio
     * @return identificador asignado a la línea
     */
    public Integer insertar(DetalleCompra detalle) {
        var params = new MapSqlParameterSource()
                .addValue("idCompra", detalle.idCompra())
                .addValue("idMateriaPrima", detalle.idMateriaPrima())
                .addValue("cantidad", detalle.cantidad())
                .addValue("precioUnitario", detalle.precioUnitario());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO detalle_compra (id_compra, id_materia_prima, cantidad, precio_unitario)
                VALUES (:idCompra, :idMateriaPrima, :cantidad, :precioUnitario)
                """, params, generadas, new String[] {"id_detalle_compra"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException("La base no devolvió el id de la línea insertada.");
        }
        return id;
    }

    /**
     * Reemplaza la cantidad y el precio unitario de una línea de detalle.
     *
     * <p>No toca {@code id_materia_prima}: la materia prima de la línea no se reemplaza, porque
     * el {@code UNIQUE} de la compra prohíbe repetirla (R-02). Para cambiar de materia prima se
     * elimina la línea y se agrega otra.
     *
     * @param detalle línea con el id y los valores nuevos; de la línea solo se usan el id,
     *                la cantidad y el precio
     */
    public void actualizar(DetalleCompra detalle) {
        var params = new MapSqlParameterSource()
                .addValue("idDetalle", detalle.idDetalle())
                .addValue("cantidad", detalle.cantidad())
                .addValue("precioUnitario", detalle.precioUnitario());

        jdbc.update("""
                UPDATE detalle_compra
                SET cantidad = :cantidad,
                    precio_unitario = :precioUnitario
                WHERE id_detalle_compra = :idDetalle
                """, params);
    }

    /**
     * Elimina una línea de detalle de una compra (R-06).
     *
     * @param idDetalle identificador de la línea
     */
    public void eliminar(Integer idDetalle) {
        var params = new MapSqlParameterSource().addValue("idDetalle", idDetalle);
        jdbc.update("DELETE FROM detalle_compra WHERE id_detalle_compra = :idDetalle", params);
    }

    /**
     * Indica si la materia prima ya está en la compra (R-02).
     *
     * <p>Lo usa el servicio para responder {@code MATERIA_PRIMA_DUPLICADA} antes de intentar el
     * {@code INSERT}; el {@code UNIQUE KEY uk_detalle_compra} es la garantía ante dos peticiones
     * simultáneas.
     *
     * @param idCompra       identificador de la compra
     * @param idMateriaPrima identificador de la materia prima
     * @return {@code true} si la materia prima ya es un ítem de la compra
     */
    public boolean existeMateriaPrimaEnCompra(Integer idCompra, Integer idMateriaPrima) {
        var params = new MapSqlParameterSource()
                .addValue("idCompra", idCompra)
                .addValue("idMateriaPrima", idMateriaPrima);

        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM detalle_compra
                WHERE id_compra = :idCompra AND id_materia_prima = :idMateriaPrima
                """, params, Long.class);
        return total != null && total > 0;
    }

    /**
     * Costo de referencia de una materia prima ({@code materias_primas.costo_unitario}), por id.
     *
     * <p>Es la columna que R-04 de HU-14 compara con el precio pagado para exponer
     * {@code costoReferencia} y {@code diferenciaCosto} en la respuesta de una línea. Solo se
     * lee: registrar o editar una línea nunca escribe este campo, que sigue siendo de HU-08.
     *
     * @param idMateriaPrima identificador de la materia prima
     * @return el costo vigente, o vacío si la materia prima no existe o no tiene costo
     */
    public Optional<BigDecimal> costoReferenciaDeMateriaPrima(Integer idMateriaPrima) {
        var costo = costosReferenciaMateriaPrima(List.of(idMateriaPrima)).get(idMateriaPrima);
        return Optional.ofNullable(costo);
    }

    /**
     * Costos de referencia de varias materias primas resueltos en una sola consulta.
     *
     * <p>El listado de una compra puede traer materias primas distintas por cada línea: pedir el
     * costo una a una sería un N+1 que solo crece con el número de ítems. Aquí se resuelven todos
     * con un {@code IN (:ids)} y el servicio reparte el mapa entre las líneas.
     *
     * <p>Una colección vacía se corta antes de consultar: un {@code IN ()} vacío no es SQL
     * válida, y una compra sin líneas no tiene ningún costo que buscar.
     *
     * @param idsMateriaPrima identificadores de las materias primas; puede venir vacío
     * @return mapa de materia prima a su costo vigente; las materias primas inexistentes o sin
     *         costo no aparecen o quedan con {@code null}, y en ambos casos el servicio las trata
     *         como «sin costo»
     */
    public Map<Integer, BigDecimal> costosReferenciaMateriaPrima(
            Collection<Integer> idsMateriaPrima) {
        if (idsMateriaPrima == null || idsMateriaPrima.isEmpty()) {
            return Map.of();
        }

        var params = new MapSqlParameterSource().addValue("ids", idsMateriaPrima);
        return jdbc.query("""
                SELECT id_materia_prima, costo_unitario
                FROM materias_primas
                WHERE id_materia_prima IN (:ids)
                """, params, (ResultSetExtractor<Map<Integer, BigDecimal>>) rs -> {
            var costos = new HashMap<Integer, BigDecimal>();
            while (rs.next()) {
                costos.put(rs.getInt("id_materia_prima"), rs.getBigDecimal("costo_unitario"));
            }
            return costos;
        });
    }

    /**
     * Suma en la base los subtotales de las líneas de una compra (R-03).
     *
     * <p>El total lo calcula siempre el servidor. El producto de dos {@code decimal(10,2)}
     * devuelve en MySQL un decimal de escala 4 —por ejemplo 33.33 × 3333.33 da 111099.8889—,
     * así que el servicio redondea este resultado a escala 2 con {@code HALF_UP} antes de
     * exponerlo. {@code COALESCE} cubre la compra sin líneas, que es el estado válido de una
     * compra recién registrada.
     *
     * @param idCompra identificador de la compra
     * @return la suma de {@code cantidad * precio_unitario}; {@code 0} si la compra no tiene
     *         líneas
     */
    public BigDecimal calcularTotal(Integer idCompra) {
        var params = new MapSqlParameterSource().addValue("idCompra", idCompra);

        BigDecimal total = jdbc.queryForObject("""
                SELECT COALESCE(SUM(d.cantidad * d.precio_unitario), 0)
                FROM detalle_compra d
                WHERE d.id_compra = :idCompra
                """, params, BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
    }

    /**
     * Extrae la llave generada como {@link Integer}.
     *
     * <p>El conector de MySQL devuelve la llave como {@code BigInteger}; pedirla con
     * {@code getKeyAs(Integer.class)} lanza {@code DataRetrievalFailureException} porque la
     * conversión de tipos no está soportada. Se toma como {@link Number} y se reduce al tipo de
     * la columna.
     */
    private static Integer claveGenerada(GeneratedKeyHolder generadas) {
        Number clave = generadas.getKey();
        return clave == null ? null : clave.intValue();
    }
}
