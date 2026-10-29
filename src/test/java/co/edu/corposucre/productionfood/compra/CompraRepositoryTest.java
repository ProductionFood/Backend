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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import co.edu.corposucre.productionfood.compra.dto.CompraResponse;
import co.edu.corposucre.productionfood.compra.dto.DetalleCompraResponse;
import co.edu.corposucre.productionfood.compra.dto.GastoProveedorResponse;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.proveedor.ProveedorRepository;

/**
 * Pruebas de integración de {@link CompraRepository#resumenPorPeriodo},
 * {@link CompraRepository#gastoPorProveedor}, {@link CompraRepository#buscarConDetalle} y
 * {@link CompraRepository#buscar} contra MySQL (HU-15).
 *
 * <p>El proyecto no usa Testcontainers ni H2: la única base soportada es la del
 * {@code docker-compose}. La prueba se conecta con los mismos parámetros que
 * {@code application.yml} (variables de entorno con los mismos valores por defecto) y se omite
 * con {@code assumeTrue} si la base o la tabla no están disponibles, para no fallar en un
 * entorno sin el contenedor levantado.
 *
 * <p>Cada prueba crea sus propios proveedores, materias primas y compras, y las elimina al
 * terminar. Las fechas propias viven en 1990 —marzo para el resumen, junio para el gasto por
 * proveedor, abril para el orden por total, septiembre para el listado con detalle y octubre
 * para la prueba de rendimiento—: las consultas se pueden acotar solo por fecha, no por
 * proveedor propio, así que un rango tan remoto no puede cruzarse con la semilla ni con los
 * datos de uso manual que las tablas ya contienen, y el rango vacío de CP-17 (enero de 1991)
 * está garantizado sin depender del estado de la base. Septiembre, octubre y abril se limpian
 * por rango en {@code @AfterEach}, porque sus siembras no se registran todas id a id.
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

    /** Rango propio del listado con detalle, separado de los otros dos para no cruzarlos. */
    private static final LocalDate INICIO_DETALLE = LocalDate.of(1990, 9, 1);
    private static final LocalDate FIN_DETALLE = LocalDate.of(1990, 9, 30);

    /**
     * Rango propio de la prueba de rendimiento y carrera (CP-18), también separado: ahí se
     * siembran cientos de compras y la limpieza es por fecha, no id a id.
     */
    private static final LocalDate INICIO_RENDIMIENTO = LocalDate.of(1990, 10, 1);
    private static final LocalDate FIN_RENDIMIENTO = LocalDate.of(1990, 10, 31);

    /** Rango propio del orden por total y del filtro de proveedor, separado de los otros. */
    private static final LocalDate INICIO_ORDEN = LocalDate.of(1990, 4, 1);
    private static final LocalDate FIN_ORDEN = LocalDate.of(1990, 4, 30);

    /** Umbral fijo de CP-18: la lectura de la página con detalle, en milisegundos. */
    private static final long UMBRAL_MILISEGUNDOS_CP18 = 2000L;

    /** Número de compras de la siembra de rendimiento, el que pide CP-18. */
    private static final int COMPRAS_RENDIMIENTO = 200;

    private static final String INSERTAR_COMPRA = """
            INSERT INTO compras (id_proveedor, fecha, estado)
            VALUES (:idProveedor, :fecha, :estado)
            """;

    private static final String INSERTAR_LINEA = """
            INSERT INTO detalle_compra (id_compra, id_materia_prima, cantidad, precio_unitario)
            VALUES (:idCompra, :idMateriaPrima, :cantidad, :precio)
            """;

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
        // Las siembras de detalle y de rendimiento son de decenas o cientos de filas y no se
        // registran id a id: se limpian por rango de fecha, con el detalle antes que la cabecera
        // porque detalle_compra → compras es FK. Hay que hacerlo antes de borrar materias
        // primas y proveedores, que esas sí referencian estas filas.
        borrarRango(INICIO_DETALLE, FIN_DETALLE);
        borrarRango(INICIO_RENDIMIENTO, FIN_RENDIMIENTO);
        borrarRango(INICIO_ORDEN, FIN_ORDEN);
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

    @Test
    @DisplayName("R-06 sobre la base real: buscarConDetalle devuelve una fila por línea y el "
            + "servicio agrupa la página con subtotales, comparativo de costo y lista vacía "
            + "cuando la compra no tiene líneas")
    void listarConDetalleAgrupaLineasYRespondeVaciaSinLineas() {
        var recibida = insertarCompra(LocalDate.of(1990, 9, 10), EstadoCompra.RECIBIDA);
        insertarLinea(recibida, 0, "3.33", "10.01");
        insertarLinea(recibida, 1, "2.00", "15.00");
        var pendiente = insertarCompra(LocalDate.of(1990, 9, 5), EstadoCompra.PENDIENTE);

        var filas = repository.buscarConDetalle(null, null, INICIO_DETALLE, FIN_DETALLE,
                "c.fecha", false, 20, 0);
        // Una fila por línea: la RECIBIDA sale dos veces con su cabecera repetida y la
        // PENDIENTE, sin líneas, una sola con el detalle en null.
        assertThat(filas).extracting(CompraDetalleFila::idCompra)
                .containsExactly(recibida, recibida, pendiente);
        assertThat(filas).filteredOn(fila -> fila.conDetalle()).hasSize(2);

        var servicio = new CompraService(repository, mock(ProveedorRepository.class),
                mock(InventarioService.class));

        var r = servicio.listar(null, null, INICIO_DETALLE, FIN_DETALLE, 0, 20, null, true);

        assertThat(r.totalElements()).isEqualTo(2);
        // Orden por fecha descendente: la del día 10 antes que la del día 5.
        assertThat(r.content()).extracting(CompraResponse::idCompra)
                .containsExactly(recibida, pendiente);

        var conLineas = r.content().get(0);
        assertThat(conLineas.estado()).isEqualTo("RECIBIDA");
        assertThat(conLineas.cantidadItems()).isEqualTo(2);
        var lineas = conLineas.detalle();
        assertThat(lineas).hasSize(2);
        // 3.33 × 10.01 = 33.3333 → 33.33 con HALF_UP; 2.00 × 15.00 = 30.00 exacto.
        assertThat(lineas).extracting(DetalleCompraResponse::subtotal)
                .containsExactlyInAnyOrder(new BigDecimal("33.33"), new BigDecimal("30.00"));
        assertThat(lineas).allSatisfy(linea -> assertThat(linea.subtotal().scale())
                .isEqualTo(2));
        assertThat(lineas).extracting(linea -> linea.materiaPrima().nombre())
                .containsExactlyInAnyOrder("Harina HU-15 test" + sufijo,
                        "Azúcar HU-15 test" + sufijo);
        // CP-11: el total de la cabecera es exactamente la suma de los subtotales, 63.33.
        var sumaSubtotales = lineas.stream().map(DetalleCompraResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(conLineas.totalCompra()).isEqualByComparingTo(sumaSubtotales);
        assertThat(conLineas.totalCompra()).isEqualByComparingTo("63.33");
        // Comparativo de HU-14 R-04: el costo de referencia es el de materias_primas (el que
        // fija insertarMateriaPrima) y la diferencia es lo pagado menos ese costo.
        assertThat(lineas).allSatisfy(linea -> {
            assertThat(linea.costoReferencia()).isEqualByComparingTo("3200.00");
            assertThat(linea.diferenciaCosto()).isEqualByComparingTo(
                    linea.precioUnitario().subtract(new BigDecimal("3200.00")));
        });

        var sinLineas = r.content().get(1);
        assertThat(sinLineas.estado()).isEqualTo("PENDIENTE");
        assertThat(sinLineas.totalCompra()).isEqualByComparingTo("0.00");
        assertThat(sinLineas.cantidadItems()).isZero();
        // CP-10/R-06: pedida con detalle y sin líneas responde lista vacía, nunca null.
        assertThat(sinLineas.detalle()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("CP-18: la página con detalle responde en menos de 2 s con 200 compras y "
            + "aguanta la carrera de cuatro hilos escribiendo mientras se lee")
    void buscarConDetalleRindeYResisteLaCarrera() throws Exception {
        asegurarCuatroMateriasPrimas();
        sembrarComprasDeRendimiento(COMPRAS_RENDIMIENTO, 28);

        // Calentamiento descartado: la primera consulta paga la preparación del plan en MySQL.
        medirLecturaDePagina();

        var milisSinCarrera = medirLecturaDePagina();
        assertThat(milisSinCarrera).as("lectura de la página sin carrera")
                .isLessThan(UMBRAL_MILISEGUNDOS_CP18);

        var executor = Executors.newFixedThreadPool(4);
        var arranque = new CountDownLatch(1);
        try {
            var futuros = new ArrayList<Future<?>>();
            for (int hilo = 0; hilo < 4; hilo++) {
                futuros.add(executor.submit(() -> {
                    arranque.await();
                    // Diez compras fechadas los días 1 y 2, los más antiguos de octubre: la
                    // página 0 ordena por fecha descendente y no cambia mientras se mide.
                    sembrarComprasDeRendimiento(10, 2);
                    return null;
                }));
            }
            // Las cuatro escrituras arrancan a la vez con la medición, para que la lectura
            // corra en medio de la carrera y no antes ni después.
            arranque.countDown();
            var milisConCarrera = medirLecturaDePagina();
            assertThat(milisConCarrera).as("lectura de la página durante la carrera")
                    .isLessThan(UMBRAL_MILISEGUNDOS_CP18);

            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS))
                    .as("las cuatro escrituras terminan antes de 60 s")
                    .isTrue();
            for (var futuro : futuros) {
                // Propaga el fallo de cualquier escritura concurrente.
                futuro.get();
            }
        } finally {
            executor.shutdownNow();
        }

        // Entre la siembra (200) y la siembra más los 4 × 10 de la carrera (240): este rango
        // de octubre solo lo tocan estas pruebas.
        var total = repository.contar(null, null, INICIO_RENDIMIENTO, FIN_RENDIMIENTO);
        assertThat(total).isBetween(200L, 240L);

        var pagina = repository.buscarConDetalle(null, null, INICIO_RENDIMIENTO,
                FIN_RENDIMIENTO, "c.fecha", false, 20, 0);
        var idsPagina = pagina.stream().map(CompraDetalleFila::idCompra).distinct().toList();
        assertThat(idsPagina).hasSize(20);
    }

    @Test
    @DisplayName("La paginación con detalle es estable: 28 compras se reparten en 20 + 8 sin "
            + "solapamiento y sin perder ninguna")
    void paginarConDetalleNoRepiteNiOmiteCompras() {
        for (int i = 0; i < 28; i++) {
            var compra = insertarCompra(INICIO_DETALLE.plusDays(i), EstadoCompra.PENDIENTE);
            // Dos líneas por compra: si el LIMIT cayera sobre el JOIN, la página de 20
            // llegaría con menos de 20 compras.
            insertarLinea(compra, i % 2, "1.00", "10.00");
            insertarLinea(compra, (i + 1) % 2, "2.00", "5.00");
        }
        var servicio = new CompraService(repository, mock(ProveedorRepository.class),
                mock(InventarioService.class));

        var pagina0 = servicio.listar(null, null, INICIO_DETALLE, FIN_DETALLE, 0, 20, null,
                true);
        var pagina1 = servicio.listar(null, null, INICIO_DETALLE, FIN_DETALLE, 1, 20, null,
                true);

        assertThat(pagina0.content()).hasSize(20);
        assertThat(pagina0.content()).extracting(CompraResponse::idCompra)
                .doesNotHaveDuplicates();
        assertThat(pagina1.content()).hasSize(8);
        assertThat(pagina1.content()).extracting(CompraResponse::idCompra)
                .doesNotHaveDuplicates();
        assertThat(pagina0.totalElements()).isEqualTo(28);
        assertThat(pagina1.totalElements()).isEqualTo(28);

        var ids0 = pagina0.content().stream().map(CompraResponse::idCompra).toList();
        var ids1 = pagina1.content().stream().map(CompraResponse::idCompra).toList();
        assertThat(ids1).doesNotContainAnyElementsOf(ids0);
        var todos = new ArrayList<>(ids0);
        todos.addAll(ids1);
        assertThat(todos).hasSize(28).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("CP-05: buscar con el alias total_compra ordena por total ascendente y "
            + "descendente")
    void buscarPorTotalOrdenaAscYDesc() {
        var esperados = sembrarComprasDelOrden();

        var ascendente = repository.buscar(null, EstadoCompra.RECIBIDA, INICIO_ORDEN,
                FIN_ORDEN, "total_compra", true, 20, 0);
        var descendente = repository.buscar(null, EstadoCompra.RECIBIDA, INICIO_ORDEN,
                FIN_ORDEN, "total_compra", false, 20, 0);

        // La lista blanca del servicio traduce "total" a este alias: los ids salen en el orden
        // de sus totales, sin empates que lo dejen indeterminado.
        assertThat(ascendente).extracting(Compra::getIdCompra)
                .containsExactlyElementsOf(esperados);
        assertThat(descendente).extracting(Compra::getIdCompra)
                .containsExactlyElementsOf(esperados.reversed());
        var totales = ascendente.stream().map(Compra::getTotalCompra).toList();
        var esperadosTotal = List.of("100.00", "200.00", "300.00", "400.00");
        for (int i = 0; i < esperadosTotal.size(); i++) {
            assertThat(totales.get(i)).as("total en la posición %s", i)
                    .isEqualByComparingTo(esperadosTotal.get(i));
        }
    }

    @Test
    @DisplayName("buscarConDetalle ordena por el alias total_compra sin ERROR 1054: la "
            + "subpágina proyecta el total que pide su ORDER BY")
    void buscarConDetallePorTotalOrdenaSinError() {
        var esperados = sembrarComprasDelOrden();

        var descendente = repository.buscarConDetalle(null, EstadoCompra.RECIBIDA, INICIO_ORDEN,
                FIN_ORDEN, "total_compra", false, 20, 0);
        var ascendente = repository.buscarConDetalle(null, EstadoCompra.RECIBIDA, INICIO_ORDEN,
                FIN_ORDEN, "total_compra", true, 20, 0);

        // sort=total con incluirDetalle=true es la combinación que antes respondía 500.
        assertThat(descendente).map(CompraDetalleFila::idCompra).containsExactly(
                esperados.get(3), esperados.get(2), esperados.get(1), esperados.get(0));
        assertThat(ascendente).map(CompraDetalleFila::idCompra).containsExactly(
                esperados.get(0), esperados.get(1), esperados.get(2), esperados.get(3));
    }

    @Test
    @DisplayName("Un idProveedor inexistente en el filtro no revienta la consulta y devuelve "
            + "vacío: el 404 lo pone el servicio")
    void buscarConProveedorInexistenteDevuelveVacia() {
        // El rango sí tiene compras: lo que no existe es el proveedor del filtro.
        sembrarComprasDelOrden();

        var filas = repository.buscar(999999, null, INICIO_ORDEN, FIN_ORDEN, "c.fecha", true,
                20, 0);

        assertThat(filas).isEmpty();
        assertThat(repository.contar(999999, null, INICIO_ORDEN, FIN_ORDEN)).isZero();
    }

    /**
     * Siembra las cuatro compras RECIBIDA del rango propio del orden con líneas de totales
     * distintos (100.00, 200.00, 300.00 y 400.00), para que el orden por total sea determinista
     * sin empates; cada compra rota de materia prima para no repetir la {@code uk} de líneas.
     *
     * @return los ids sembrados, ordenados por total ascendente
     */
    private List<Integer> sembrarComprasDelOrden() {
        var lineas = new String[][] {
            {"1.00", "100.00"},
            {"1.00", "200.00"},
            {"2.00", "150.00"},
            {"4.00", "100.00"},
        };
        var ids = new ArrayList<Integer>();
        for (int i = 0; i < lineas.length; i++) {
            var compra = insertarCompra(INICIO_ORDEN.plusDays(i + 1), EstadoCompra.RECIBIDA);
            insertarLinea(compra, i % 2, lineas[i][0], lineas[i][1]);
            ids.add(compra);
        }
        return ids;
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

    /**
     * Borra por rango de fecha un par detalle–cabecera, en ese orden por la
     * {@code detalle_compra → compras}. Es la limpieza de las siembras grandes, que no se
     * registran id a id en {@code idsCompra}.
     *
     * @param inicio primer día incluido del rango propio
     * @param fin    último día incluido del rango propio
     */
    private void borrarRango(LocalDate inicio, LocalDate fin) {
        var params = new MapSqlParameterSource()
                .addValue("inicio", inicio)
                .addValue("fin", fin);
        jdbc.update("""
                DELETE dc FROM detalle_compra dc
                JOIN compras c ON c.id_compra = dc.id_compra
                WHERE c.fecha BETWEEN :inicio AND :fin
                """, params);
        jdbc.update("DELETE FROM compras WHERE fecha BETWEEN :inicio AND :fin", params);
    }

    /**
     * Completa el conjunto propio de materias primas hasta cuatro, que es lo que la siembra de
     * rendimiento necesita para dar hasta cuatro líneas por compra sin repetir materia prima
     * ({@code uk_detalle_compra}).
     */
    private void asegurarCuatroMateriasPrimas() {
        while (idsMateriaPrima.size() < 4) {
            idsMateriaPrima.add(insertarMateriaPrima(
                    "Materia HU-15 test" + idsMateriaPrima.size() + sufijo));
        }
    }

    /**
     * Ejecuta la lectura de CP-18 —la página de 20 compras con detalle y su conteo— y devuelve
     * lo que tardó, en milisegundos.
     *
     * @return milisegundos transcurridos desde el inicio de la página hasta el fin del conteo
     */
    private long medirLecturaDePagina() {
        var inicio = System.nanoTime();
        repository.buscarConDetalle(null, null, INICIO_RENDIMIENTO, FIN_RENDIMIENTO,
                "c.fecha", false, 20, 0);
        repository.contar(null, null, INICIO_RENDIMIENTO, FIN_RENDIMIENTO);
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - inicio);
    }

    /**
     * Crea compras de rendimiento del rango propio de octubre por batch: la fecha rota sobre
     * los {@code dias} primeros días de octubre, el estado reparte los tres en ciclo y cada
     * compra recibe entre 2 y 4 líneas sobre las materias primas propias, que rotan para no
     * repetirse dentro de la misma compra.
     *
     * <p>Cabeceras y líneas viajan en dos {@code batchUpdate}, nunca en un bucle de updates:
     * la siembra de CP-18 son cientos de filas y la carrera añade 40 más. La llamada de la
     * carrera usa 2 días para caer en los más antiguos de octubre, fuera de la página 0 que
     * ordena por fecha descendente.
     *
     * @param cantidad número de compras a crear
     * @param dias     días de octubre que rotan las fechas, contando desde el 1
     */
    private void sembrarComprasDeRendimiento(int cantidad, int dias) {
        var estados = EstadoCompra.values();
        var cabeceras = new SqlParameterSource[cantidad];
        for (int i = 0; i < cantidad; i++) {
            cabeceras[i] = new MapSqlParameterSource()
                    .addValue("idProveedor", idProveedor)
                    .addValue("fecha", INICIO_RENDIMIENTO.plusDays(i % dias))
                    .addValue("estado", estados[i % 3].name());
        }
        var ids = insertarComprasPorBatch(cabeceras);

        var lineas = new ArrayList<SqlParameterSource>();
        for (int i = 0; i < ids.size(); i++) {
            for (int k = 0; k < 2 + (i % 3); k++) {
                lineas.add(new MapSqlParameterSource()
                        .addValue("idCompra", ids.get(i))
                        .addValue("idMateriaPrima",
                                idsMateriaPrima.get((i + k) % idsMateriaPrima.size()))
                        .addValue("cantidad", BigDecimal.valueOf(i + k + 1, 2))
                        .addValue("precio", BigDecimal.valueOf(1000 + i + k, 2)));
            }
        }
        jdbc.batchUpdate(INSERTAR_LINEA, lineas.toArray(SqlParameterSource[]::new));
    }

    /**
     * Inserta cabeceras en un solo batch y devuelve sus ids generados.
     *
     * <p>MySQL devuelve todas las llaves del batch en el orden de inserción; cada fila del
     * {@link GeneratedKeyHolder} trae una sola columna, la del identificador. Si el conector
     * no devolviera una llave por cabecera, la aserción del tamaño corta la prueba en seco en
     * vez de sembrar líneas contra ids que no existen.
     *
     * @param cabeceras parámetros de las compras a insertar
     * @return los ids insertados, en el mismo orden
     */
    private List<Integer> insertarComprasPorBatch(SqlParameterSource[] cabeceras) {
        var claves = new GeneratedKeyHolder();
        jdbc.batchUpdate(INSERTAR_COMPRA, cabeceras, claves);

        var ids = new ArrayList<Integer>(cabeceras.length);
        for (var fila : claves.getKeyList()) {
            Object clave = fila.size() == 1 ? fila.values().iterator().next()
                    : fila.getOrDefault("id_compra", fila.get("GENERATED_KEY"));
            assertThat(clave).as("llave generada de una cabecera del batch")
                    .isInstanceOf(Number.class);
            ids.add(((Number) clave).intValue());
        }
        assertThat(ids).as("llaves devueltas por el batch de cabeceras")
                .hasSize(cabeceras.length);
        return ids;
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
        var id = insertarConId(INSERTAR_COMPRA, new MapSqlParameterSource()
                        .addValue("idProveedor", proveedor)
                        .addValue("fecha", fecha)
                        .addValue("estado", estado.name()));
        idsCompra.add(id);
        return id;
    }

    private void insertarLinea(Integer idCompra, int indiceMateriaPrima, String cantidad,
                               String precio) {
        jdbc.update(INSERTAR_LINEA, new MapSqlParameterSource()
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
