package co.edu.corposucre.productionfood.compra;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Persistencia JDBC de las compras ({@code compras} y {@code detalle_compra}, HU-13).
 *
 * <p>Se usa JDBC y no JPA por la misma razón que el módulo de recetas: el modelo ya está descrito
 * por el esquema y no debe duplicarse en Hibernate. Además, el total y la cantidad de ítems de una
 * compra son derivados que conviene calcular en una sola consulta con subconsultas correlacionadas,
 * en vez de cargar todas las líneas para sumarlas en memoria.
 *
 * <p>Las escrituras del detalle de la compra ({@code INSERT/UPDATE/DELETE} sobre
 * {@code detalle_compra}) son de HU-14 y no viven aquí: en este módulo el detalle solo se lee y se
 * cuenta.
 */
@Repository
public class CompraRepository {

    /**
     * Origen de la consulta, compartido por {@link #buscar} y {@link #buscarPorId} para que el
     * listado y la vista de detalle devuelvan exactamente las mismas columnas.
     *
     * <p>El {@code INNER JOIN} con {@code proveedores} es seguro porque {@code id_proveedor} es
     * {@code NOT NULL} con llave foránea: no descarta compras. Los totales llegan de dos
     * subconsultas correlacionadas sobre {@code detalle_compra}: {@code COALESCE} devuelve
     * {@code 0} mientras la compra no tenga líneas, que es el estado válido de una compra recién
     * registrada (el detalle llega después, HU-14).
     */
    private static final String SELECT = """
            SELECT c.id_compra, c.id_proveedor, c.fecha, c.estado,
                   p.nombre AS nombre_proveedor,
                   COALESCE((SELECT SUM(dc.cantidad * dc.precio_unitario)
                             FROM detalle_compra dc
                             WHERE dc.id_compra = c.id_compra), 0) AS total_compra,
                   COALESCE((SELECT COUNT(*)
                             FROM detalle_compra dc
                             WHERE dc.id_compra = c.id_compra), 0) AS cantidad_items
            FROM compras c
            JOIN proveedores p ON p.id_proveedor = c.id_proveedor
            """;

    /**
     * Origen del resumen por estado (HU-15): agrupa las compras del rango con un
     * {@code LEFT JOIN} a {@code detalle_compra} para sumar sus líneas en la misma consulta.
     *
     * <p>{@code COUNT(DISTINCT c.id_compra)} y no {@code COUNT(*)}: con el {@code LEFT JOIN}
     * cada línea de detalle duplica la fila de su compra, y un {@code COUNT(*)} contaría una
     * RECIBIDA con dos líneas como dos compras. {@code COALESCE} cubre las compras sin líneas,
     * cuyo {@code SUM} es {@code NULL} y debe responder {@code 0}.
     */
    private static final String SELECT_RESUMEN = """
            SELECT c.estado,
                   COUNT(DISTINCT c.id_compra) AS cantidad,
                   COALESCE(SUM(d.cantidad * d.precio_unitario), 0) AS total
            FROM compras c
            LEFT JOIN detalle_compra d ON d.id_compra = c.id_compra
            """;

    /**
     * Origen del gasto por proveedor (HU-15): agrupa las compras recibidas del rango por
     * proveedor para sumar su gasto ejecutado.
     *
     * <p>Aquí los {@code JOIN} son interiores y no un {@code LEFT JOIN}: el filtro de estado
     * {@code RECIBIDA} garantiza que cada compra agrupada tiene al menos una línea de detalle
     * —recibir la compra lo exige (COMPRA_SIN_DETALLE)—, así que el {@code INNER JOIN} con
     * {@code detalle_compra} no descarta ninguna fila y evita el {@code COALESCE} de un
     * {@code SUM} sobre un conjunto vacío. El {@code JOIN} con {@code proveedores} es seguro
     * porque {@code id_proveedor} es {@code NOT NULL} con llave foránea. Igual que en el
     * resumen, {@code COUNT(DISTINCT c.id_compra)} evita contar una RECIBIDA con dos líneas
     * como dos compras.
     */
    private static final String SELECT_GASTO_PROVEEDOR = """
            SELECT p.nombre AS proveedor,
                   COUNT(DISTINCT c.id_compra) AS cantidad,
                   COALESCE(SUM(d.cantidad * d.precio_unitario), 0) AS total
            FROM compras c
            JOIN proveedores p ON p.id_proveedor = c.id_proveedor
            JOIN detalle_compra d ON d.id_compra = c.id_compra
            """;

    /**
     * Origen del listado con el detalle unido (HU-15 R-06), para {@link #buscarConDetalle}.
     *
     * <p>Trae la cabecera —las mismas subconsultas correlacionadas de {@link #SELECT}, que
     * devuelven el mismo valor por cada fila de su compra— junto a las columnas de cada línea,
     * con {@code LEFT JOIN} porque una compra sin líneas debe aparecer en el listado con el
     * detalle vacío y no desaparecer.
     *
     * <p>El {@code %s} es la página de cabeceras, que se pagina <strong>antes</strong> de unir el
     * detalle: un {@code LIMIT} aplicado sobre el {@code JOIN} cortaría por filas y una página
     * de 20 devolvería menos de 20 compras.
     */
    private static final String SELECT_CON_DETALLE = """
            SELECT c.id_compra, c.id_proveedor, c.fecha, c.estado,
                   p.nombre AS nombre_proveedor,
                   COALESCE((SELECT SUM(dc.cantidad * dc.precio_unitario)
                             FROM detalle_compra dc
                             WHERE dc.id_compra = c.id_compra), 0) AS total_compra,
                   COALESCE((SELECT COUNT(*)
                             FROM detalle_compra dc
                             WHERE dc.id_compra = c.id_compra), 0) AS cantidad_items,
                   d.id_materia_prima, d.id_detalle_compra, d.cantidad, d.precio_unitario,
                   mp.nombre AS nombre_materia_prima, u.abreviatura AS abreviatura_unidad,
                   mp.costo_unitario AS costo_referencia
            FROM (%s) c
            JOIN proveedores p ON p.id_proveedor = c.id_proveedor
            LEFT JOIN detalle_compra d ON d.id_compra = c.id_compra
            LEFT JOIN materias_primas mp ON mp.id_materia_prima = d.id_materia_prima
            LEFT JOIN unidades_medida u ON u.id_unidad = mp.id_unidad
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CompraRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Devuelve una página de compras con los datos de su proveedor y sus totales derivados.
     *
     * <p>{@code propiedadOrden} no se recibe aparte del resto porque no viaja como texto del
     * cliente: el servicio la resuelve antes contra su lista blanca ({@code fecha}, {@code estado},
     * {@code id} → {@code c.fecha}, {@code c.estado}, {@code c.id_compra}) y aquí se concatena ya
     * validada. Si llegara una propiedad que no es columna SQL, sería un error de programación del
     * llamante y produciría un SQL inválido, no un orden silenciosamente distinto.
     *
     * @param idProveedor     proveedor a filtrar, o {@code null} para no filtrar
     * @param estado          estado a filtrar, o {@code null} para no filtrar
     * @param fechaInicio     primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin        último día incluido del rango, o {@code null} sin límite superior
     * @param propiedadOrden  columna ya validada por la lista blanca del servicio
     * @param ascendente      {@code true} para orden ascendente, {@code false} para descendente
     * @param size            tamaño de página
     * @param offset          desplazamiento de la página; es {@code long} porque {@code page} no
     *                        tiene cota superior y {@code page * size} desbordaría un int
     * @return compras de la página, con su proveedor y sus totales
     */
    public List<Compra> buscar(Integer idProveedor, EstadoCompra estado, LocalDate fechaInicio,
                               LocalDate fechaFin, String propiedadOrden, boolean ascendente,
                               int size, long offset) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idProveedor, estado, fechaInicio, fechaFin, params);
        params.addValue("tamano", size).addValue("desplazamiento", offset);

        var sql = SELECT + where
                + " ORDER BY " + propiedadOrden + (ascendente ? " ASC" : " DESC")
                + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(sql, params, new CompraRowMapper());
    }

    /**
     * Devuelve una página de compras con sus líneas de detalle en una sola consulta (HU-15 R-06).
     *
     * <p>Es la contraparte de {@link #buscar} cuando el listado se pide con
     * {@code incluirDetalle=true}: un {@code LEFT JOIN} a {@code detalle_compra},
     * {@code materias_primas} y {@code unidades_medida} trae cabecera y líneas juntas, de modo
     * que una página de 20 compras con líneas resuelve en <strong>una</strong> consulta y nunca
     * en un bucle de consultas por compra (N+1). Los {@code LEFT JOIN} son interiores a propósito:
     * una compra sin líneas debe devolverse con sus columnas de detalle en {@code null} para que
     * el servicio responda {@code detalle: []}.
     *
     * <p>La página se calcula sobre la cabecera —la subconsulta interna filtra, ordena y aplica
     * {@code LIMIT/OFFSET} sobre {@code compras}— y después se une el detalle, porque de otro
     * modo el {@code LIMIT} contaría filas del {@code JOIN} y una página pedida de 20 compras
     * llegaría con menos de 20. Las subconsultas correlacionadas de total e ítems se mantienen:
     * son las mismas de {@link #SELECT} y devuelven la misma cifra en cada fila de su compra.
     *
     * <p>El {@code WHERE} es el de {@link #armarFiltros}, el mismo que usan {@link #buscar} y
     * {@link #contar}, para que la página y el total no diverjan.
     *
     * <p>Devuelve una fila plana por combinación compra/línea —y una fila con el detalle a
     * {@code null} por cada compra sin líneas—, sin agrupar: la agrupación en memoria por
     * {@code id_compra}, preservando el orden de la primera aparición de cada compra, la hace el
     * servicio al montar cada {@code CompraResponse} con su lista de detalle.
     *
     * @param idProveedor     proveedor a filtrar, o {@code null} para no filtrar
     * @param estado          estado a filtrar, o {@code null} para no filtrar
     * @param fechaInicio     primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin        último día incluido del rango, o {@code null} sin límite superior
     * @param propiedadOrden  columna ya validada por la lista blanca del servicio
     * @param ascendente      {@code true} para orden ascendente, {@code false} para descendente
     * @param size            número de <strong>compras</strong> de la página
     * @param offset          desplazamiento de la página; es {@code long} porque {@code page} no
     *                        tiene cota superior y {@code page * size} desbordaría un int
     * @return una fila por cada línea de cada compra de la página, y una fila sin detalle por
     *         cada compra sin líneas, en el orden de la página
     */
    public List<CompraDetalleFila> buscarConDetalle(Integer idProveedor, EstadoCompra estado,
                                                    LocalDate fechaInicio, LocalDate fechaFin,
                                                    String propiedadOrden, boolean ascendente,
                                                    int size, long offset) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idProveedor, estado, fechaInicio, fechaFin, params);
        params.addValue("tamano", size).addValue("desplazamiento", offset);

        var orden = " ORDER BY " + propiedadOrden + (ascendente ? " ASC" : " DESC");
        // La subpágina proyecta también total_compra porque su ORDER BY puede pedirlo
        // (sort=total): el alias solo existiría en la proyección exterior y MySQL respondería
        // ERROR 1054 Unknown column en la cláusula de orden de la subconsulta.
        var pagina = "SELECT id_compra, id_proveedor, fecha, estado,"
                + " COALESCE((SELECT SUM(dc.cantidad * dc.precio_unitario)"
                + "          FROM detalle_compra dc WHERE dc.id_compra = c.id_compra), 0)"
                + "   AS total_compra"
                + " FROM compras c" + where
                + orden + " LIMIT :tamano OFFSET :desplazamiento";

        return jdbc.query(SELECT_CON_DETALLE.formatted(pagina) + orden, params,
                new CompraDetalleFilaRowMapper());
    }

    /**
     * Cuenta las compras que cumplen los filtros, sin traerlas.
     *
     * <p>Reutiliza el mismo {@code WHERE} que {@link #buscar}: el total de la paginación sale de
     * aquí y debe corresponder exactamente a las filas que se paginan. No repite el {@code JOIN}
     * con {@code proveedores} porque los filtros tocan solo columnas de {@code compras} y el
     * nombre del proveedor no hace falta para contar.
     *
     * @param idProveedor proveedor a filtrar, o {@code null} para no filtrar
     * @param estado      estado a filtrar, o {@code null} para no filtrar
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return total de compras que cumplen los filtros
     */
    public long contar(Integer idProveedor, EstadoCompra estado, LocalDate fechaInicio,
                       LocalDate fechaFin) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(idProveedor, estado, fechaInicio, fechaFin, params);

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM compras c " + where, params,
                Long.class);
        return total == null ? 0L : total;
    }

    /**
     * Resume las compras de un período agrupadas por estado (HU-15).
     *
     * <p>Reutiliza el {@code WHERE} de {@link #armarFiltros} con los filtros de proveedor y
     * estado anulados —el resumen justamente agrupa todos los estados y no se parte por
     * proveedor—: las fechas viajan como parámetros con nombre, nunca concatenadas en el SQL.
     *
     * <p>Los estados sin compras en el rango no devuelven fila: el servicio los rellena con
     * {@code 0}/{@code 0.00}. Un rango vacío devuelve la lista vacía y no un error (CP-17).
     *
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return una fila por estado presente en el rango; vacía si el rango no tiene compras
     */
    public List<ResumenEstadoFila> resumenPorPeriodo(LocalDate fechaInicio, LocalDate fechaFin) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(null, null, fechaInicio, fechaFin, params);

        return jdbc.query(SELECT_RESUMEN + where + " GROUP BY c.estado", params,
                new ResumenEstadoFilaRowMapper());
    }

    /**
     * Resume las compras RECIBIDA de un período agrupadas por proveedor (HU-15).
     *
     * <p>Reutiliza el {@code WHERE} de {@link #armarFiltros} con el estado fijo en
     * {@code RECIBIDA} —el único que cuenta como gasto ejecutado— y el filtro de proveedor
     * anulado: la consulta justamente agrupa todos los proveedores. Las fechas viajan como
     * parámetros con nombre, nunca concatenadas en el SQL.
     *
     * <p>Los proveedores sin compras recibidas en el rango no devuelven fila. Un rango vacío
     * devuelve la lista vacía y no un error.
     *
     * @param fechaInicio primer día incluido del rango, o {@code null} sin límite inferior
     * @param fechaFin    último día incluido del rango, o {@code null} sin límite superior
     * @return una fila por proveedor con recibidas en el rango, ordenada por total descendente;
     *         vacía si el rango no tiene compras recibidas
     */
    public List<GastoProveedorFila> gastoPorProveedor(LocalDate fechaInicio, LocalDate fechaFin) {
        var params = new MapSqlParameterSource();
        var where = armarFiltros(null, EstadoCompra.RECIBIDA, fechaInicio, fechaFin, params);

        return jdbc.query(SELECT_GASTO_PROVEEDOR + where
                + " GROUP BY p.id_proveedor, p.nombre"
                + " ORDER BY total DESC", params, new GastoProveedorFilaRowMapper());
    }

    /**
     * Devuelve una compra por su identificador, con su proveedor y sus totales derivados.
     *
     * @param id identificador de la compra
     * @return la compra, o vacío si no existe
     */
    public Optional<Compra> buscarPorId(Integer id) {
        var params = new MapSqlParameterSource().addValue("id", id);
        return jdbc.query(SELECT + " WHERE c.id_compra = :id", params, new CompraRowMapper())
                .stream().findFirst();
    }

    /**
     * Inserta la cabecera de una compra y devuelve el identificador que asignó la base.
     *
     * <p>El estado viaja como su nombre canónico, que es el literal del
     * {@code CHECK ck_compras_estado}. No lleva {@code @Transactional}: la transacción la abre el
     * servicio que crea la cabecera y su detalle, para que un fallo en una línea no deje una
     * compra a medias.
     *
     * @param compra compra a persistir; debe traer proveedor, fecha y estado
     * @return identificador asignado a la compra
     */
    public Integer insertar(Compra compra) {
        var params = new MapSqlParameterSource()
                .addValue("idProveedor", compra.getIdProveedor())
                .addValue("fecha", compra.getFecha())
                .addValue("estado", compra.getEstado().name());

        var generadas = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO compras (id_proveedor, fecha, estado)
                VALUES (:idProveedor, :fecha, :estado)
                """, params, generadas, new String[] {"id_compra"});

        Integer id = claveGenerada(generadas);
        if (id == null) {
            throw new IllegalStateException("La base no devolvió el id de la compra insertada.");
        }
        return id;
    }

    /**
     * Actualiza los campos editables de la cabecera.
     *
     * <p>No toca {@code estado}: para eso está {@link #cambiarEstado(Integer, EstadoCompra)}, que
     * es el único camino que la máquina de estados de {@link EstadoCompra} permite auditar.
     *
     * @param id          identificador de la compra
     * @param idProveedor proveedor nuevo
     * @param fecha       fecha nueva
     */
    public void actualizar(Integer id, Integer idProveedor, LocalDate fecha) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("idProveedor", idProveedor)
                .addValue("fecha", fecha);

        jdbc.update("""
                UPDATE compras
                SET id_proveedor = :idProveedor,
                    fecha = :fecha
                WHERE id_compra = :id
                """, params);
    }

    /**
     * Cambia el estado de una compra.
     *
     * <p>El repositorio solo escribe el valor: la máquina de transiciones de
     * {@link EstadoCompra} la valida el servicio antes de llegar aquí, porque un
     * {@code UPDATE} directo no puede saber si la compra estaba en un estado que admite el salto.
     *
     * @param id     identificador de la compra
     * @param estado estado nuevo
     */
    public void cambiarEstado(Integer id, EstadoCompra estado) {
        var params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("estado", estado.name());

        jdbc.update("UPDATE compras SET estado = :estado WHERE id_compra = :id", params);
    }

    /**
     * Cuenta las líneas de detalle de una compra, sin traerlas.
     *
     * <p>Es la misma cifra que la subconsulta {@code cantidad_items} de {@link #SELECT}, pero
     * resuelta contra la base en el momento: útil tras persistir las líneas de HU-14, cuando el
     * valor derivado de la compra leída antes del {@code INSERT} ya está desactualizado.
     *
     * @param idCompra identificador de la compra
     * @return número de líneas de {@code detalle_compra} de esa compra; {@code 0} si no tiene
     */
    public int contarDetalles(Integer idCompra) {
        var params = new MapSqlParameterSource().addValue("idCompra", idCompra);

        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM detalle_compra WHERE id_compra = :idCompra",
                params, Integer.class);
        return total == null ? 0 : total;
    }

    /**
     * Devuelve las líneas de detalle de una compra, en el orden en que se agregaron.
     *
     * <p>Solo lectura: las escrituras sobre {@code detalle_compra} son de HU-14. La cantidad y el
     * precio se fijan a escala 2, la de sus columnas {@code decimal(10,2)}.
     *
     * <p>El {@code INNER JOIN} con {@code materias_primas} trae el nombre junto a cada línea para
     * que la recepción de la compra (HU-13 R-01) pueda describir cada movimiento generado sin una
     * consulta adicional por materia prima; es seguro porque {@code id_materia_prima} es llave
     * foránea {@code NOT NULL} y no descarta ninguna línea.
     *
     * @param idCompra identificador de la compra
     * @return líneas de detalle, con su materia prima y nombre; vacía si la compra no tiene líneas
     */
    public List<LineaDetalleCompra> listarDetalles(Integer idCompra) {
        var params = new MapSqlParameterSource().addValue("idCompra", idCompra);
        return jdbc.query("""
                SELECT d.id_detalle_compra, d.id_materia_prima, d.cantidad, d.precio_unitario,
                       mp.nombre AS nombre_materia_prima
                FROM detalle_compra d
                JOIN materias_primas mp ON mp.id_materia_prima = d.id_materia_prima
                WHERE d.id_compra = :idCompra
                ORDER BY d.id_detalle_compra
                """, params, (rs, rowNum) -> new LineaDetalleCompra(
                        rs.getObject("id_detalle_compra", Integer.class),
                        rs.getObject("id_materia_prima", Integer.class),
                        rs.getBigDecimal("cantidad"),
                        rs.getBigDecimal("precio_unitario"),
                        rs.getString("nombre_materia_prima")));
    }

    /**
     * Fragmento {@code WHERE} compartido por {@link #buscar}, {@link #contar} y
     * {@link #resumenPorPeriodo}, para que todos apliquen exactamente los mismos filtros y el
     * total no diverja de la página.
     *
     * <p>Los cuatro filtros son opcionales y se acumulan. Solo se concatenan fragmentos
     * constantes; los valores viajan como parámetros con nombre. {@code c.fecha} es una columna
     * {@code date}, así que el {@link LocalDate} viaja sin convertir.
     */
    private static String armarFiltros(Integer idProveedor, EstadoCompra estado,
                                       LocalDate fechaInicio, LocalDate fechaFin,
                                       MapSqlParameterSource params) {
        var where = new StringBuilder(" WHERE 1=1");
        if (idProveedor != null) {
            where.append(" AND c.id_proveedor = :idProveedor");
            params.addValue("idProveedor", idProveedor);
        }
        if (estado != null) {
            where.append(" AND c.estado = :estado");
            params.addValue("estado", estado.name());
        }
        if (fechaInicio != null) {
            where.append(" AND c.fecha >= :fechaInicio");
            params.addValue("fechaInicio", fechaInicio);
        }
        if (fechaFin != null) {
            where.append(" AND c.fecha <= :fechaFin");
            params.addValue("fechaFin", fechaFin);
        }
        return where.toString();
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
}
