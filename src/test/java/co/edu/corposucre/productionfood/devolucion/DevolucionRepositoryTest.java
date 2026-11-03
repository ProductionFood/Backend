package co.edu.corposucre.productionfood.devolucion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import co.edu.corposucre.productionfood.common.pagination.PageRequest;

/**
 * Pruebas de integración de {@link DevolucionRepository} contra MySQL (HU-18).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea su propia unidad, sus productos, su cliente, sus pedidos y sus líneas, y
 * los elimina al terminar en orden inverso al de las llaves foráneas: las devoluciones antes que
 * el detalle, el detalle antes que la cabecera, y los productos antes que la unidad.
 *
 * <p>Las fechas de siembra viven en noviembre de 1990 —un rango lejano a cualquier dato
 * realista de la base— para que el filtro por rango sea determinista sin importar lo que haya
 * en otras filas.
 */
class DevolucionRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private NamedParameterJdbcTemplate jdbc;
    private DevolucionRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsProducto = new ArrayList<>();
    private Integer idCliente;
    private final List<Integer> idsPedido = new ArrayList<>();
    private final List<Integer> idsDetalle = new ArrayList<>();

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM devoluciones LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla devoluciones; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new DevolucionRepository(jdbc);

        // Sufijo único por prueba: los nombres tienen índice en la base y una corrida anterior
        // que deje datos no debe confundir las lecturas por nombre.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-18 test" + sufijo)
                        .addValue("abreviatura", "u18" + sufijo));
        // Dos productos: uk_detalle_pedido es (id_pedido, id_producto), así que un pedido con
        // dos líneas necesita dos productos distintos.
        idsProducto.add(insertarProducto("Producto A HU-18 test" + sufijo));
        idsProducto.add(insertarProducto("Producto B HU-18 test" + sufijo));
        idCliente = insertarConId("""
                INSERT INTO clientes (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Cliente HU-18 test" + sufijo));
    }

    @AfterEach
    void tearDown() {
        // Las devoluciones primero que el detalle: devoluciones → detalle_pedido es FK.
        if (!idsDetalle.isEmpty()) {
            jdbc.update("DELETE FROM devoluciones WHERE id_detalle_pedido IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsDetalle));
        }
        if (!idsPedido.isEmpty()) {
            jdbc.update("DELETE FROM detalle_pedido WHERE id_pedido IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPedido));
            jdbc.update("DELETE FROM pedidos WHERE id_pedido IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsPedido));
        }
        if (idCliente != null) {
            jdbc.update("DELETE FROM clientes WHERE id_cliente = :id",
                    new MapSqlParameterSource().addValue("id", idCliente));
        }
        if (!idsProducto.isEmpty()) {
            jdbc.update("DELETE FROM productos WHERE id_producto IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsProducto));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    @Test
    @DisplayName("insertar omite fecha_devolucion y la columna queda en el DEFAULT (curdate())")
    void insertarOmiteLaFechaYAplicaElDefaultDeLaBase() {
        var pedido = insertarPedido();
        var linea = insertarLinea(pedido, idsProducto.get(0), "30.00");

        var id = repository.insertar(nueva(linea, "Producto en mal estado",
                "Tres panes llegaron aplastados", "3.00", EstadoDevolucion.PENDIENTE));

        assertThat(id).isNotNull();
        var leida = repository.buscarPorId(id).orElseThrow();
        // La fecha la fija la base con su DEFAULT (curdate()), no la aplicación (R-06).
        var hoy = jdbc.queryForObject("SELECT CURDATE()", new MapSqlParameterSource(),
                LocalDate.class);
        assertThat(leida.fechaDevolucion()).isEqualTo(hoy);
        assertThat(leida.estado()).isEqualTo(EstadoDevolucion.PENDIENTE);
        assertThat(leida.motivo()).isEqualTo("Producto en mal estado");
        assertThat(leida.descripcion()).isEqualTo("Tres panes llegaron aplastados");
        assertThat(leida.cantidadDevuelta()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("buscarPorId trae el pedido, el cliente, el producto y la cantidad pedida del JOIN")
    void buscarPorIdTraeLosJoins() {
        var pedido = insertarPedido();
        var linea = insertarLinea(pedido, idsProducto.get(0), "30.00");
        var id = repository.insertar(nueva(linea, "Error en el pedido", null, "3.00",
                EstadoDevolucion.PENDIENTE));

        var leida = repository.buscarPorId(id).orElseThrow();

        assertThat(leida.idDevolucion()).isEqualTo(id);
        assertThat(leida.idDetallePedido()).isEqualTo(linea);
        assertThat(leida.idPedido()).isEqualTo(pedido);
        assertThat(leida.nombreCliente()).isEqualTo("Cliente HU-18 test" + sufijo);
        assertThat(leida.idProducto()).isEqualTo(idsProducto.get(0));
        assertThat(leida.nombreProducto()).isEqualTo("Producto A HU-18 test" + sufijo);
        assertThat(leida.cantidadPedida()).isEqualByComparingTo("30.00");
        assertThat(leida.estado()).isEqualTo(EstadoDevolucion.PENDIENTE);
        // Id lejano a cualquier fila real: la consulta no devuelve nada y el Optional queda vacío.
        assertThat(repository.buscarPorId(2147483647)).isEmpty();
    }

    @Test
    @DisplayName("cambiarEstado actualiza solo el estado y deja intacta la fecha de registro")
    void cambiarEstadoNoTocaLaFecha() {
        var pedido = insertarPedido();
        var linea = insertarLinea(pedido, idsProducto.get(0), "30.00");
        var id = repository.insertar(nueva(linea, "Producto vencido", null, "2.00",
                EstadoDevolucion.PENDIENTE));
        var antes = repository.buscarPorId(id).orElseThrow();

        var afectadas = repository.cambiarEstado(id, EstadoDevolucion.PENDIENTE,
                EstadoDevolucion.APROBADA);

        var despues = repository.buscarPorId(id).orElseThrow();
        assertThat(afectadas).isEqualTo(1);
        assertThat(despues.estado()).isEqualTo(EstadoDevolucion.APROBADA);
        assertThat(despues.fechaDevolucion()).isEqualTo(antes.fechaDevolucion());

        // La fila ya no está PENDIENTE: cero filas, sin doble transición (R-04).
        assertThat(repository.cambiarEstado(id, EstadoDevolucion.PENDIENTE,
                EstadoDevolucion.RECHAZADA)).isZero();
    }

    @Test
    @DisplayName("buscar aplica estado, rango de fechas e idPedido, y contar devuelve el mismo total")
    void buscarAplicaLosTresFiltrosYContarCoincide() {
        var pedidoA = insertarPedido();
        var lineaA = insertarLinea(pedidoA, idsProducto.get(0), "30.00");
        var pendiente = sembrarDevolucion(lineaA, "Motivo pendiente", "1.00",
                EstadoDevolucion.PENDIENTE, LocalDate.of(1990, 11, 5));
        var aprobada = sembrarDevolucion(lineaA, "Motivo aprobada", "2.00",
                EstadoDevolucion.APROBADA, LocalDate.of(1990, 11, 10));
        sembrarDevolucion(lineaA, "Motivo rechazada", "3.00",
                EstadoDevolucion.RECHAZADA, LocalDate.of(1990, 11, 15));
        var pedidoB = insertarPedido();
        var lineaB = insertarLinea(pedidoB, idsProducto.get(0), "10.00");
        sembrarDevolucion(lineaB, "Motivo de otro pedido", "4.00",
                EstadoDevolucion.PENDIENTE, LocalDate.of(1990, 11, 20));

        // Estado: solo la PENDIENTE del pedido propio; el pedido B queda fuera por idPedido.
        assertThat(repository.buscar(EstadoDevolucion.PENDIENTE, null, null, pedidoA,
                pagina(0, 10)))
                .extracting(fila -> fila.devolucion().idDevolucion())
                .containsExactly(pendiente);
        assertThat(repository.contar(EstadoDevolucion.PENDIENTE, null, null, pedidoA))
                .isEqualTo(1);

        // Rango sobre fecha_devolucion: solo el día 10 cae entre el 8 y el 12.
        var inicio = LocalDate.of(1990, 11, 8);
        var fin = LocalDate.of(1990, 11, 12);
        assertThat(repository.buscar(null, inicio, fin, pedidoA, pagina(0, 10)))
                .extracting(fila -> fila.devolucion().idDevolucion())
                .containsExactly(aprobada);
        assertThat(repository.contar(null, inicio, fin, pedidoA)).isEqualTo(1);

        // idPedido aísla el pedido: 3 en A y 1 en B, sin sumar filas de la base.
        assertThat(repository.contar(null, null, null, pedidoA)).isEqualTo(3);
        assertThat(repository.contar(null, null, null, pedidoB)).isEqualTo(1);
        assertThat(repository.buscar(null, null, null, pedidoA, pagina(0, 10))).hasSize(3);
    }

    @Test
    @DisplayName("la paginación reparte las filas en páginas de 'size' sin repetir ni perder ninguna")
    void laPaginacionReparteLasFilasSinRepetirNiPerderNinguna() {
        var pedido = insertarPedido();
        var linea = insertarLinea(pedido, idsProducto.get(0), "30.00");
        var primera = sembrarDevolucion(linea, "Motivo uno", "1.00",
                EstadoDevolucion.PENDIENTE, LocalDate.of(1990, 11, 5));
        var segunda = sembrarDevolucion(linea, "Motivo dos", "2.00",
                EstadoDevolucion.APROBADA, LocalDate.of(1990, 11, 10));
        var tercera = sembrarDevolucion(linea, "Motivo tres", "3.00",
                EstadoDevolucion.RECHAZADA, LocalDate.of(1990, 11, 15));

        var pagina0 = repository.buscar(null, null, null, pedido, pagina(0, 1));
        var pagina1 = repository.buscar(null, null, null, pedido, pagina(1, 1));
        var pagina2 = repository.buscar(null, null, null, pedido, pagina(2, 1));
        var pagina3 = repository.buscar(null, null, null, pedido, pagina(3, 1));

        assertThat(pagina0).hasSize(1);
        assertThat(pagina1).hasSize(1);
        assertThat(pagina2).hasSize(1);
        assertThat(pagina3).isEmpty();
        // Con la propiedad id, las tres páginas encadenadas recorren las filas en el orden en
        // que se sembraron, sin repetir ni perder ninguna.
        assertThat(Stream.concat(Stream.concat(pagina0.stream(), pagina1.stream()),
                pagina2.stream()))
                .extracting(fila -> fila.devolucion().idDevolucion())
                .containsExactly(primera, segunda, tercera);
        assertThat(repository.contar(null, null, null, pedido)).isEqualTo(3);
    }

    @Test
    @DisplayName("sort por estado resuelve la lista blanca y devuelve las filas en ese orden")
    void sortPorEstadoResuelveLaListaBlanca() {
        var pedido = insertarPedido();
        var linea = insertarLinea(pedido, idsProducto.get(0), "30.00");
        sembrarDevolucion(linea, "Motivo pendiente", "1.00",
                EstadoDevolucion.PENDIENTE, LocalDate.of(1990, 11, 5));
        sembrarDevolucion(linea, "Motivo aprobada", "2.00",
                EstadoDevolucion.APROBADA, LocalDate.of(1990, 11, 10));
        sembrarDevolucion(linea, "Motivo rechazada", "3.00",
                EstadoDevolucion.RECHAZADA, LocalDate.of(1990, 11, 15));

        var ascendente = repository.buscar(null, null, null, pedido,
                PageRequest.of(0, 10, "estado", DevolucionRepository.ORDEN,
                        DevolucionRepository.ORDEN_POR_DEFECTO));

        assertThat(ascendente).extracting(fila -> fila.devolucion().estado())
                .containsExactly(EstadoDevolucion.APROBADA, EstadoDevolucion.PENDIENTE,
                        EstadoDevolucion.RECHAZADA);

        var descendente = repository.buscar(null, null, null, pedido,
                PageRequest.of(0, 10, "estado,desc", DevolucionRepository.ORDEN,
                        DevolucionRepository.ORDEN_POR_DEFECTO));

        assertThat(descendente).extracting(fila -> fila.devolucion().estado())
                .containsExactly(EstadoDevolucion.RECHAZADA, EstadoDevolucion.PENDIENTE,
                        EstadoDevolucion.APROBADA);
    }

    @Test
    @DisplayName("una propiedad de orden fuera de la lista blanca cae al orden por defecto sin error")
    void unaPropiedadFueraDeLaListaBlancaCaeAlOrdenPorDefecto() {
        var pedido = insertarPedido();
        var linea = insertarLinea(pedido, idsProducto.get(0), "30.00");
        var temprana = sembrarDevolucion(linea, "Motivo a", "1.00",
                EstadoDevolucion.PENDIENTE, LocalDate.of(1990, 11, 5));
        var media = sembrarDevolucion(linea, "Motivo b", "2.00",
                EstadoDevolucion.APROBADA, LocalDate.of(1990, 11, 10));
        var tardia = sembrarDevolucion(linea, "Motivo c", "3.00",
                EstadoDevolucion.RECHAZADA, LocalDate.of(1990, 11, 15));

        // 'd.motivo' no pertenece a la lista blanca: el repositorio no la concatena en el
        // ORDER BY —una columna inexistente responde ERROR 1054, el borde que dolió en HU-15—
        // y aplica el orden por defecto completo, fechaDevolucion DESC.
        var filas = repository.buscar(null, null, null, pedido,
                new PageRequest(0, 10, "d.motivo", true));

        assertThat(filas).extracting(fila -> fila.devolucion().idDevolucion())
                .containsExactly(tardia, media, temprana);
    }

    @Test
    @DisplayName("sumarDevueltoPorDetalle suma APROBADA y PENDIENTE y excluye RECHAZADA (R-03)")
    void sumarDevueltoPorDetalleExcluyeLasRechazadas() {
        var pedido = insertarPedido();
        var linea = insertarLinea(pedido, idsProducto.get(0), "30.00");
        var sinDevoluciones = insertarLinea(pedido, idsProducto.get(1), "5.00");

        repository.insertar(nueva(linea, "Producto en mal estado", null, "5.00",
                EstadoDevolucion.APROBADA));
        repository.insertar(nueva(linea, "Error en el pedido", null, "3.00",
                EstadoDevolucion.PENDIENTE));
        repository.insertar(nueva(linea, "Cliente insatisfecho", null, "10.00",
                EstadoDevolucion.RECHAZADA));

        // 5 aprobadas + 3 pendientes = 8; la rechazada de 10 libera su cantidad (R-03).
        assertThat(repository.sumarDevueltoPorDetalle(linea)).isEqualByComparingTo("8.00");
        assertThat(repository.sumarDevueltoPorDetalle(sinDevoluciones))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("comprometidaLinea del listado coincide con sumarDevueltoPorDetalle en todas las filas")
    void comprometidaLineaCoincideConLaSumaDeLaLinea() {
        var pedido = insertarPedido();
        var linea = insertarLinea(pedido, idsProducto.get(0), "30.00");
        repository.insertar(nueva(linea, "Producto en mal estado", null, "5.00",
                EstadoDevolucion.APROBADA));
        repository.insertar(nueva(linea, "Error en el pedido", null, "3.00",
                EstadoDevolucion.PENDIENTE));
        repository.insertar(nueva(linea, "Cliente insatisfecho", null, "10.00",
                EstadoDevolucion.RECHAZADA));

        var esperada = repository.sumarDevueltoPorDetalle(linea);
        var filas = repository.buscar(null, null, null, pedido, pagina(0, 10));

        // La subconsulta correlacionada del SELECT da el mismo resultado que la suma aparte,
        // en la misma consulta y sin una consulta por fila.
        assertThat(esperada).isEqualByComparingTo("8.00");
        assertThat(filas).hasSize(3);
        assertThat(filas).allSatisfy(fila ->
                assertThat(fila.comprometidaLinea()).isEqualByComparingTo(esperada));
    }

    /**
     * Crea una devolución sin id para las pruebas; la fecha la pone el repositorio.
     */
    private Devolucion nueva(Integer idDetalle, String motivo, String descripcion,
                             String cantidad, EstadoDevolucion estado) {
        return new Devolucion(idDetalle, motivo, descripcion, new BigDecimal(cantidad), estado);
    }

    /**
     * Página ordenada por id ascendente, el orden determinista de las pruebas de paginación.
     */
    private static PageRequest pagina(int page, int size) {
        return new PageRequest(page, size, "d.id_devolucion", true);
    }

    /**
     * Siembra un pedido de prueba en estado ENTREGADO —el estado desde el que una devolución
     * tiene sentido (R-05)— y lo registra para que la limpieza lo borre con su detalle.
     *
     * @return identificador del pedido sembrado
     */
    private Integer insertarPedido() {
        var id = insertarConId("""
                INSERT INTO pedidos (id_cliente, estado) VALUES (:idCliente, 'ENTREGADO')
                """, new MapSqlParameterSource().addValue("idCliente", idCliente));
        idsPedido.add(id);
        return id;
    }

    /**
     * Siembra una línea de pedido y la registra para la limpieza en orden inverso de FK.
     *
     * @return identificador de la línea sembrada
     */
    private Integer insertarLinea(Integer idPedido, Integer idProducto, String cantidad) {
        var id = insertarConId("""
                INSERT INTO detalle_pedido (id_pedido, id_producto, cantidad, precio_unitario)
                VALUES (:idPedido, :idProducto, :cantidad, 1250.50)
                """, new MapSqlParameterSource()
                .addValue("idPedido", idPedido)
                .addValue("idProducto", idProducto)
                .addValue("cantidad", new BigDecimal(cantidad)));
        idsDetalle.add(id);
        return id;
    }

    /**
     * Siembra una devolución con fecha y estado explícitos, para los filtros de rango y de
     * estado; la limpieza la borra por su línea.
     *
     * @return identificador de la devolución sembrada
     */
    private Integer sembrarDevolucion(Integer idDetalle, String motivo, String cantidad,
                                      EstadoDevolucion estado, LocalDate fecha) {
        return insertarConId("""
                INSERT INTO devoluciones (id_detalle_pedido, motivo, descripcion,
                                          cantidad_devuelta, fecha_devolucion, estado)
                VALUES (:idDetalle, :motivo, NULL, :cantidad, :fecha, :estado)
                """, new MapSqlParameterSource()
                .addValue("idDetalle", idDetalle)
                .addValue("motivo", motivo)
                .addValue("cantidad", new BigDecimal(cantidad))
                .addValue("fecha", fecha)
                .addValue("estado", estado.name()));
    }

    private Integer insertarProducto(String nombre) {
        return insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, 100.00, :idUnidad, 1)
                """, new MapSqlParameterSource()
                .addValue("nombre", nombre)
                .addValue("idUnidad", idUnidad));
    }

    private Integer insertarConId(String sql, MapSqlParameterSource params) {
        var generadas = new GeneratedKeyHolder();
        jdbc.update(sql, params, generadas);
        Number clave = generadas.getKey();
        if (clave == null) {
            throw new IllegalStateException("La base no devolvió el id del fixture insertado.");
        }
        return clave.intValue();
    }
}
