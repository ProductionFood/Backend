package co.edu.corposucre.productionfood.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import co.edu.corposucre.productionfood.compra.dto.GastoProveedorResponse;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.proveedor.ProveedorRepository;

/**
 * Pruebas de integración de {@link CompraRepository#resumenPorPeriodo} y
 * {@link CompraRepository#gastoPorProveedor} contra MySQL (HU-15).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea sus propios proveedores, materias primas y compras, y las elimina al
 * terminar. Las fechas propias viven en 1990 —marzo para el resumen y junio para el gasto por
 * proveedor—: ambas consultas se pueden acotar solo por fecha, no por proveedor propio, así que
 * un rango tan remoto no puede cruzarse con la semilla ni con los datos de uso manual que las
 * tablas ya contienen, y el rango vacío de CP-17 (enero de 1991) está garantizado sin depender
 * del estado de la base.
 */
class CompraRepositoryTest {

    private static final String URL = "jdbc:mysql://"
            + entorno("DB_HOST", "127.0.0.1") + ":" + entorno("DB_PORT", "3306")
            + "/" + entorno("DB_NAME", "productionfood")
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Bogota";
    private static final String USUARIO = entorno("DB_USER", "root");
    private static final String CLAVE = entorno("DB_PASSWORD", "local");

    /** Rango propio de las compras sembradas; lejano a cualquier dato realista de la base. */
    private static final LocalDate INICIO = LocalDate.of(1990, 3, 1);
    private static final LocalDate FIN = LocalDate.of(1990, 3, 31);

    /** Rango propio del gasto por proveedor, separado del del resumen para no cruzarlos. */
    private static final LocalDate INICIO_GASTO = LocalDate.of(1990, 6, 1);
    private static final LocalDate FIN_GASTO = LocalDate.of(1990, 6, 30);

    private NamedParameterJdbcTemplate jdbc;
    private CompraRepository repository;

    private String sufijo;
    private Integer idUnidad;
    private final List<Integer> idsMateriaPrima = new ArrayList<>();
    private Integer idProveedor;
    private final List<Integer> idsProveedorExtra = new ArrayList<>();
    private final List<Integer> idsCompra = new ArrayList<>();

    private static String entorno(String nombre, String defecto) {
        String valor = System.getenv(nombre);
        return valor == null || valor.isBlank() ? defecto : valor;
    }

    @BeforeAll
    static void laBaseDelProyectoEstaDisponible() {
        boolean disponible;
        try (var conexion = java.sql.DriverManager.getConnection(URL, USUARIO, CLAVE);
             var sentencia = conexion.createStatement()) {
            sentencia.executeQuery("SELECT 1 FROM compras LIMIT 1").close();
            disponible = true;
        } catch (SQLException e) {
            disponible = false;
        }
        assumeTrue(disponible,
                "MySQL no responde en " + URL + " con la tabla compras; se omite.");
    }

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(URL, USUARIO, CLAVE);
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        repository = new CompraRepository(jdbc);

        // Sufijo único por prueba: abreviatura y nombres tienen UNIQUE en la base y una
        // corrida anterior que deje datos no debe hacer colisionar esta.
        sufijo = "-" + UUID.randomUUID().toString().substring(0, 8);
        idUnidad = insertarConId("""
                INSERT INTO unidades_medida (nombre, abreviatura)
                VALUES (:nombre, :abreviatura)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Unidad HU-15 test" + sufijo)
                        .addValue("abreviatura", "u15" + sufijo));
        idsMateriaPrima.add(insertarMateriaPrima("Harina HU-15 test" + sufijo));
        idsMateriaPrima.add(insertarMateriaPrima("Azúcar HU-15 test" + sufijo));
        idProveedor = insertarConId("""
                INSERT INTO proveedores (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource()
                        .addValue("nombre", "Proveedor HU-15 test" + sufijo));
    }

    @AfterEach
    void tearDown() {
        for (Integer idCompra : idsCompra) {
            jdbc.update("DELETE FROM detalle_compra WHERE id_compra = :id",
                    new MapSqlParameterSource().addValue("id", idCompra));
            jdbc.update("DELETE FROM compras WHERE id_compra = :id",
                    new MapSqlParameterSource().addValue("id", idCompra));
        }
        if (!idsMateriaPrima.isEmpty()) {
            jdbc.update("DELETE FROM materias_primas WHERE id_materia_prima IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsMateriaPrima));
        }
        if (!idsProveedorExtra.isEmpty()) {
            jdbc.update("DELETE FROM proveedores WHERE id_proveedor IN (:ids)",
                    new MapSqlParameterSource().addValue("ids", idsProveedorExtra));
        }
        if (idProveedor != null) {
            jdbc.update("DELETE FROM proveedores WHERE id_proveedor = :id",
                    new MapSqlParameterSource().addValue("id", idProveedor));
        }
        if (idUnidad != null) {
            jdbc.update("DELETE FROM unidades_medida WHERE id_unidad = :id",
                    new MapSqlParameterSource().addValue("id", idUnidad));
        }
    }

    @Test
    @DisplayName("CP-13/COUNT(DISTINCT): agrupa por estado y una RECIBIDA con dos líneas cuenta 1")
    void resumenAgrupaPorEstadoYNoInflaLaCantidadConElDetalle() {
        sembrarComprasDelPeriodo();

        var filas = repository.resumenPorPeriodo(INICIO, FIN);

        assertThat(filas).hasSize(3);
        var recibidas = fila(filas, EstadoCompra.RECIBIDA);
        // COUNT(DISTINCT): la RECIBIDA tiene dos líneas y aun así es una sola compra.
        assertThat(recibidas.cantidad()).isEqualTo(1);
        // El total se recalcula del detalle: 10 × 3200.00 + 5 × 1500.00.
        assertThat(recibidas.total()).isEqualByComparingTo("39500.00");
        assertThat(recibidas.total().scale()).isEqualTo(2);
        var pendientes = fila(filas, EstadoCompra.PENDIENTE);
        assertThat(pendientes.cantidad()).isEqualTo(1);
        assertThat(pendientes.total()).isEqualByComparingTo("200.00");
        var canceladas = fila(filas, EstadoCompra.CANCELADA);
        // CP-13: las canceladas tienen su propio bloque, con su total, separado del de las
        // recibidas; los dos gastos del endpoint salen de bloques distintos.
        assertThat(canceladas.cantidad()).isEqualTo(1);
        assertThat(canceladas.total()).isEqualByComparingTo("85.00");
    }

    @Test
    @DisplayName("CP-14: las cifras de los tres estados son distintas entre sí")
    void resumenDistingueLosTresEstados() {
        sembrarComprasDelPeriodo();

        var filas = repository.resumenPorPeriodo(INICIO, FIN);

        assertThat(filas).extracting(ResumenEstadoFila::total)
                .doesNotHaveDuplicates()
                .hasSize(3);
        var recibidas = fila(filas, EstadoCompra.RECIBIDA);
        var pendientes = fila(filas, EstadoCompra.PENDIENTE);
        var canceladas = fila(filas, EstadoCompra.CANCELADA);
        assertThat(recibidas.total()).isNotEqualTo(pendientes.total());
        assertThat(recibidas.total()).isNotEqualTo(canceladas.total());
        assertThat(pendientes.total()).isNotEqualTo(canceladas.total());
    }

    @Test
    @DisplayName("El filtro de fechas es inclusivo y deja fuera las compras del rango vecino")
    void resumenAcotaElRangoPorFecha() {
        sembrarComprasDelPeriodo();

        // 1990-03-11..14 incluye la PENDIENTE del día 12 y excluye la RECIBIDA (10) y la
        // CANCELADA (15): los dos límites del rango son inclusivos.
        var filas = repository.resumenPorPeriodo(LocalDate.of(1990, 3, 11),
                LocalDate.of(1990, 3, 14));

        assertThat(filas).hasSize(1);
        var pendientes = fila(filas, EstadoCompra.PENDIENTE);
        assertThat(pendientes.cantidad()).isEqualTo(1);
        assertThat(pendientes.total()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("CP-17: un rango sin compras devuelve la lista vacía, no un error")
    void resumenDeUnRangoVacioDevuelveListaVacia() {
        sembrarComprasDelPeriodo();

        var filas = repository.resumenPorPeriodo(LocalDate.of(1991, 1, 1),
                LocalDate.of(1991, 1, 31));

        // El servicio convierte esta lista vacía en cantidades 0 y totales 0.00 (se comprueba
        // en CompraServiceTest); el repositorio no inventa filas de estados ausentes.
        assertThat(filas).isEmpty();
    }

    @Test
    @DisplayName("CP-13/CP-17 sobre el servicio: el gasto ejecutado omite las canceladas y un "
            + "rango vacío responde todo en cero")
    void resumenDelServicioSobreLaBaseReal() {
        sembrarComprasDelPeriodo();
        // resumen() no usa ni el proveedor ni el inventario: esas dos dependencias del servicio
        // se dejan simuladas para componer el flujo completo sin salir de esta prueba.
        var servicio = new CompraService(repository, mock(ProveedorRepository.class),
                mock(InventarioService.class));

        var r = servicio.resumen(INICIO, FIN);

        assertThat(r.periodo().desde()).isEqualTo(INICIO);
        assertThat(r.periodo().hasta()).isEqualTo(FIN);
        assertThat(r.recibidas().cantidad()).isEqualTo(1);
        assertThat(r.recibidas().total()).isEqualByComparingTo("39500.00");
        assertThat(r.pendientes().cantidad()).isEqualTo(1);
        assertThat(r.pendientes().total()).isEqualByComparingTo("200.00");
        assertThat(r.canceladas().cantidad()).isEqualTo(1);
        assertThat(r.canceladas().total()).isEqualByComparingTo("85.00");
        // CP-13: ejecutado es solo el total de la RECIBIDA; la CANCELADA no se suma.
        assertThat(r.gastoEjecutado()).isEqualByComparingTo("39500.00");
        assertThat(r.gastoComprometido()).isEqualByComparingTo("200.00");

        var vacio = servicio.resumen(LocalDate.of(1991, 1, 1), LocalDate.of(1991, 1, 31));
        // CP-17: rango sin compras, sin error y con todo en cero a escala 2.
        assertThat(vacio.recibidas().cantidad()).isZero();
        assertThat(vacio.recibidas().total()).isEqualByComparingTo("0.00");
        assertThat(vacio.recibidas().total().scale()).isEqualTo(2);
        assertThat(vacio.pendientes().cantidad()).isZero();
        assertThat(vacio.canceladas().cantidad()).isZero();
        assertThat(vacio.gastoEjecutado()).isEqualByComparingTo("0.00");
        assertThat(vacio.gastoComprometido()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("CP-15/COUNT(DISTINCT): el gasto por proveedor ordena por total descendente, "
            + "cuenta compras distintas y excluye las que no son RECIBIDA")
    void gastoPorProveedorOrdenaPorTotalDescYExcluyeLasNoRecibidas() {
        var nombres = sembrarComprasDelGasto();

        var filas = repository.gastoPorProveedor(INICIO_GASTO, FIN_GASTO);

        assertThat(filas).hasSize(2);
        // CP-15: el mayor total va primero.
        assertThat(filas.get(0).proveedor()).isEqualTo(nombres[0]);
        // COUNT(DISTINCT): la RECIBIDA del primer proveedor tiene dos líneas y aun así es una
        // sola compra; el total se recalcula del detalle: 10 × 3200.00 + 5 × 1500.00.
        assertThat(filas.get(0).cantidadCompras()).isEqualTo(1);
        assertThat(filas.get(0).total()).isEqualByComparingTo("39500.00");
        assertThat(filas.get(0).total().scale()).isEqualTo(2);
        assertThat(filas.get(1).proveedor()).isEqualTo(nombres[1]);
        assertThat(filas.get(1).cantidadCompras()).isEqualTo(1);
        assertThat(filas.get(1).total()).isEqualByComparingTo("200.00");
        // El tercer proveedor solo tiene PENDIENTE y CANCELADA en el rango: no es gasto
        // ejecutado y por eso no aparece.
        assertThat(filas).extracting(GastoProveedorFila::proveedor)
                .doesNotContain(nombres[2]);
    }

    @Test
    @DisplayName("CP-16 sobre el servicio: los porcentajes del gasto por proveedor suman 100 "
            + "con su residuo de redondeo")
    void gastoPorProveedorDelServicioSumaCien() {
        var nombres = sembrarComprasDelGasto();
        // gastoPorProveedor() no usa ni el proveedor ni el inventario: esas dos dependencias
        // del servicio se dejan simuladas, igual que en la prueba del resumen sobre base real.
        var servicio = new CompraService(repository, mock(ProveedorRepository.class),
                mock(InventarioService.class));

        var r = servicio.gastoPorProveedor(INICIO_GASTO, FIN_GASTO);

        assertThat(r).hasSize(2);
        assertThat(r.get(0).proveedor()).isEqualTo(nombres[0]);
        assertThat(r.get(0).total()).isEqualByComparingTo("39500.00");
        // 39500 / 39700 × 100 = 99.4962… → 99.50 y 200 / 39700 × 100 = 0.5038… → 0.50.
        assertThat(r.get(0).porcentaje()).isEqualByComparingTo("99.50");
        assertThat(r.get(0).porcentaje().scale()).isEqualTo(2);
        assertThat(r.get(1).proveedor()).isEqualTo(nombres[1]);
        assertThat(r.get(1).porcentaje()).isEqualByComparingTo("0.50");
        assertThat(r.get(1).porcentaje().scale()).isEqualTo(2);
        var suma = r.stream().map(GastoProveedorResponse::porcentaje)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(suma).isBetween(new BigDecimal("99.99"), new BigDecimal("100.01"));
    }

    /**
     * Siembra las tres compras del período propio: una RECIBIDA con dos líneas, una PENDIENTE
     * con una y una CANCELADA con otra.
     *
     * <p>La fecha de la CANCELADA (día 15) queda fuera del rango vecino del filtro inclusivo
     * (11..14) y la RECIBIDA (día 10) también, de modo que ese filtro distingue los tres casos
     * sin datos auxiliares.
     */
    private void sembrarComprasDelPeriodo() {
        var recibida = insertarCompra(LocalDate.of(1990, 3, 10), EstadoCompra.RECIBIDA);
        insertarLinea(recibida, 0, "10.00", "3200.00");
        insertarLinea(recibida, 1, "5.00", "1500.00");
        var pendiente = insertarCompra(LocalDate.of(1990, 3, 12), EstadoCompra.PENDIENTE);
        insertarLinea(pendiente, 0, "2.00", "100.00");
        var cancelada = insertarCompra(LocalDate.of(1990, 3, 15), EstadoCompra.CANCELADA);
        insertarLinea(cancelada, 1, "1.00", "85.00");
    }

    /**
     * Siembra las compras del período de gasto propio contra tres proveedores: el primero con
     * una RECIBIDA de dos líneas, el segundo con una RECIBIDA de una sola línea, y el tercero
     * solo con una PENDIENTE y una CANCELADA, que no deben aparecer en el gasto ejecutado.
     *
     * @return los tres nombres sembrados, en el orden en que los dos primeros deben aparecer
     *         en la respuesta y con el tercero que debe quedar fuera
     */
    private String[] sembrarComprasDelGasto() {
        var nombreA = "Gasto A HU-15 test" + sufijo;
        var nombreB = "Gasto B HU-15 test" + sufijo;
        var nombreC = "Gasto C HU-15 test" + sufijo;
        var proveedorA = insertarProveedorExtra(nombreA);
        var proveedorB = insertarProveedorExtra(nombreB);
        var proveedorC = insertarProveedorExtra(nombreC);

        var recibidaA = insertarCompraEn(proveedorA, LocalDate.of(1990, 6, 5),
                EstadoCompra.RECIBIDA);
        insertarLinea(recibidaA, 0, "10.00", "3200.00");
        insertarLinea(recibidaA, 1, "5.00", "1500.00");
        var recibidaB = insertarCompraEn(proveedorB, LocalDate.of(1990, 6, 8),
                EstadoCompra.RECIBIDA);
        insertarLinea(recibidaB, 0, "2.00", "100.00");
        var pendienteC = insertarCompraEn(proveedorC, LocalDate.of(1990, 6, 10),
                EstadoCompra.PENDIENTE);
        insertarLinea(pendienteC, 0, "2.00", "100.00");
        var canceladaC = insertarCompraEn(proveedorC, LocalDate.of(1990, 6, 12),
                EstadoCompra.CANCELADA);
        insertarLinea(canceladaC, 1, "1.00", "85.00");

        return new String[] {nombreA, nombreB, nombreC};
    }

    private Integer insertarProveedorExtra(String nombre) {
        var id = insertarConId("""
                INSERT INTO proveedores (nombre, estado) VALUES (:nombre, 1)
                """, new MapSqlParameterSource().addValue("nombre", nombre));
        idsProveedorExtra.add(id);
        return id;
    }

    private static ResumenEstadoFila fila(List<ResumenEstadoFila> filas, EstadoCompra estado) {
        return filas.stream()
                .filter(fila -> fila.estado() == estado)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Falta la fila del estado " + estado));
    }

    private Integer insertarCompra(LocalDate fecha, EstadoCompra estado) {
        return insertarCompraEn(idProveedor, fecha, estado);
    }

    private Integer insertarCompraEn(Integer proveedor, LocalDate fecha, EstadoCompra estado) {
        var id = insertarConId("""
                INSERT INTO compras (id_proveedor, fecha, estado)
                VALUES (:idProveedor, :fecha, :estado)
                """, new MapSqlParameterSource()
                        .addValue("idProveedor", proveedor)
                        .addValue("fecha", fecha)
                        .addValue("estado", estado.name()));
        idsCompra.add(id);
        return id;
    }

    private void insertarLinea(Integer idCompra, int indiceMateriaPrima, String cantidad,
                               String precio) {
        jdbc.update("""
                INSERT INTO detalle_compra (id_compra, id_materia_prima, cantidad, precio_unitario)
                VALUES (:idCompra, :idMateriaPrima, :cantidad, :precio)
                """, new MapSqlParameterSource()
                        .addValue("idCompra", idCompra)
                        .addValue("idMateriaPrima", idsMateriaPrima.get(indiceMateriaPrima))
                        .addValue("cantidad", new BigDecimal(cantidad))
                        .addValue("precio", new BigDecimal(precio)));
    }

    private Integer insertarMateriaPrima(String nombre) {
        return insertarConId("""
                INSERT INTO materias_primas (nombre, id_unidad, stock_minimo, costo_unitario, estado)
                VALUES (:nombre, :idUnidad, 10.00, 3200.00, 1)
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
