package co.edu.corposucre.productionfood.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * Pruebas de integración de {@link InventarioPtRepository} contra MySQL (HU-17).
 *
 * <p>Igual que las pruebas de pedidos, no usa Testcontainers ni H2: la única base soportada es la
 * del {@code docker-compose}. Se conecta con las mismas variables de entorno que
 * {@code application.yml} y se omite con {@code assumeTrue} si la base o la vista
 * {@code v_lotes_disponibles_fefo} no están disponibles.
 *
 * <p>Cada prueba siembra su propio producto, sus lotes y su inventario, y los elimina al
 * terminar, en orden inverso al de las llaves foráneas. Los códigos de lote y los orígenes del
 * kardex llevan un sufijo aleatorio para no coincidir con datos reales ni con otra corrida.
 */
class InventarioPtRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    private static final String TABLA_ORIGEN = "detalle_pedido";

    private NamedParameterJdbcTemplate jdbc;
    private InventarioPtRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private Integer idProducto;
    private final List<Integer> idsLote = new ArrayList<>();
    private Integer idOrigen;
    private Integer idOrigenAjeno;

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM v_lotes_disponibles_fefo LIMIT 1").close();
            sentencia.executeQuery("SELECT 1 FROM movimientos_inventario_pt LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con las tablas de inventario PT; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new InventarioPtRepository(jdbc);

        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idOrigen = ThreadLocalRandom.current().nextInt(1_500_000_000, 2_000_000_000);
        idOrigenAjeno = idOrigen + 1;
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-17 test" + sufijo)
                        .addValue("abreviatura", "u17" + sufijo));
        idProducto = insertarConId("""
                INSERT INTO productos (nombre, precio, id_unidad, estado)
                VALUES (:nombre, 100.00, :idUnidad, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Producto HU-17 test" + sufijo)
                        .addValue("idUnidad", idUnidad));
    }

    @AfterEach
    void tearDown() {
        // Orden inverso a las FK: kardex → inventario → lote → producto → unidad.
        if (!idsLote.isEmpty()) {
            jdbc.update("DELETE FROM movimientos_inventario_pt WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
            jdbc.update("DELETE FROM inventario_producto_terminado WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
            jdbc.update("DELETE FROM lotes WHERE id_lote IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsLote));
        }
        if (idProducto != null) {
            jdbc.update("DELETE FROM productos WHERE id_producto = :id",
                    new MapSqlParameterSource().addValue("id", idProducto));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    @Test
    @DisplayName("R-02/R-03: devuelve solo lotes vigentes con saldo, ordenados por vencimiento")
    void lotesDisponiblesFefoOrdenaEIgnoraVencidosYSinSaldo() {
        // Se siembra en orden inverso al FEFO a propósito (CP-04): manda el vencimiento, no el
        // orden de alta.
        var despues = insertarLote(LocalDate.now().plusDays(20), "7.00");
        var antes = insertarLote(LocalDate.now().plusDays(10), "4.00");
        var mismoDia = insertarLote(LocalDate.now().plusDays(10), "3.00");
        insertarLote(LocalDate.now().minusDays(5), "10.00");
        var sinSaldo = insertarLote(LocalDate.now().plusDays(3), "0.00");

        var lotes = repository.lotesDisponiblesFefo(idProducto);

        assertThat(lotes).extracting(LoteDisponible::idLote)
                .containsExactly(antes, mismoDia, despues);
        assertThat(lotes).extracting(LoteDisponible::cantidadDisponible)
                .containsExactly(new BigDecimal("4.00"), new BigDecimal("3.00"),
                        new BigDecimal("7.00"));
        assertThat(lotes).extracting(LoteDisponible::idLote)
                .doesNotContain(sinSaldo);
        assertThat(lotes).extracting(LoteDisponible::codigoLote)
                .allSatisfy(codigo -> assertThat(codigo).startsWith("LH17"));
    }

    @Test
    @DisplayName("Netea SALIDA_VENTA menos ENTRADA_DEVOLUCION por lote y descarta netos <= 0")
    void movimientosNetosDeOrigenNeteaYDescartaSaldados() {
        var saldado = insertarLote(LocalDate.now().plusDays(30), "10.00");
        var conNeto = insertarLote(LocalDate.now().plusDays(10), "10.00");
        var negativo = insertarLote(LocalDate.now().plusDays(20), "10.00");
        // Neto 3: 5 vendidos menos 2 devueltos.
        insertarMovimiento(conNeto, "SALIDA_VENTA", "5.00", idOrigen);
        insertarMovimiento(conNeto, "ENTRADA_DEVOLUCION", "2.00", idOrigen);
        // Neto 0: todo devuelto, ya no se repone.
        insertarMovimiento(saldado, "SALIDA_VENTA", "4.00", idOrigen);
        insertarMovimiento(saldado, "ENTRADA_DEVOLUCION", "4.00", idOrigen);
        // Neto -1 sin salida previa: no puede tener algo que devolver.
        insertarMovimiento(negativo, "ENTRADA_DEVOLUCION", "1.00", idOrigen);
        // Otro origen con la misma tabla no debe mezclarse.
        insertarMovimiento(conNeto, "SALIDA_VENTA", "9.00", idOrigenAjeno);

        var netos = repository.movimientosNetosDeOrigen(TABLA_ORIGEN, idOrigen);

        assertThat(netos).containsExactly(new LoteConsumido(conNeto, codigoDe(conNeto),
                fechaVencimientoDe(conNeto), new BigDecimal("3.00")));
    }

    /**
     * Siembra un lote de producto terminado con su fila de inventario y lo registra para que la
     * limpieza lo borre antes que su producto.
     *
     * <p>{@code lotes.cantidad} es la cantidad producida y tiene {@code CHECK > 0}; el saldo
     * disponible es un dato distinto y sí puede ser cero. Por eso el lote se siembra con al menos
     * una unidad aunque su inventario quede en cero.
     */
    private Integer insertarLote(LocalDate vencimiento, String saldo) {
        var cantidadLote = new BigDecimal(saldo).signum() > 0 ? new BigDecimal(saldo)
                : BigDecimal.ONE;
        var id = insertarConId("""
                INSERT INTO lotes (codigo_lote, id_producto, fecha_produccion, fecha_vencimiento,
                                   cantidad)
                VALUES (:codigo, :idProducto, :produccion, :vencimiento, :cantidad)
                """, new MapSqlParameterSource()
                        .addValue("codigo", "LH17" + sufijo + "-"
                                + UUID.randomUUID().toString().substring(0, 8))
                        .addValue("idProducto", idProducto)
                        .addValue("produccion", vencimiento.minusDays(30))
                        .addValue("vencimiento", vencimiento)
                        .addValue("cantidad", cantidadLote));
        idsLote.add(id);
        jdbc.update("""
                INSERT INTO inventario_producto_terminado (id_lote, cantidad_disponible)
                VALUES (:idLote, :cantidad)
                """, new MapSqlParameterSource()
                        .addValue("idLote", id)
                        .addValue("cantidad", new BigDecimal(saldo)));
        return id;
    }

    private void insertarMovimiento(Integer idLote, String tipo, String cantidad,
                                    Integer origen) {
        jdbc.update("""
                INSERT INTO movimientos_inventario_pt
                    (id_lote, tipo_movimiento, cantidad, saldo_anterior, saldo_posterior,
                     tabla_origen, id_origen)
                VALUES (:idLote, :tipo, :cantidad, 10.00, 10.00, :tablaOrigen, :idOrigen)
                """, new MapSqlParameterSource()
                        .addValue("idLote", idLote)
                        .addValue("tipo", tipo)
                        .addValue("cantidad", new BigDecimal(cantidad))
                        .addValue("tablaOrigen", TABLA_ORIGEN)
                        .addValue("idOrigen", origen));
    }

    private String codigoDe(Integer idLote) {
        return jdbc.queryForObject(
                "SELECT codigo_lote FROM lotes WHERE id_lote = :id",
                new MapSqlParameterSource().addValue("id", idLote), String.class);
    }

    private LocalDate fechaVencimientoDe(Integer idLote) {
        return jdbc.queryForObject(
                "SELECT fecha_vencimiento FROM lotes WHERE id_lote = :id",
                new MapSqlParameterSource().addValue("id", idLote), LocalDate.class);
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
