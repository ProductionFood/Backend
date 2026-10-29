package co.edu.corposucre.productionfood.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.common.error.RecursoNoEncontradoException;
import co.edu.corposucre.productionfood.compra.dto.ActualizarCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.CrearCompraRequest;
import co.edu.corposucre.productionfood.compra.dto.GastoProveedorResponse;
import co.edu.corposucre.productionfood.inventario.InventarioService;
import co.edu.corposucre.productionfood.inventario.SaldosInventario;
import co.edu.corposucre.productionfood.inventario.TipoMovimiento;
import co.edu.corposucre.productionfood.proveedor.Proveedor;
import co.edu.corposucre.productionfood.proveedor.ProveedorRepository;

class CompraServiceTest {

    private static final Integer ID_PROVEEDOR = 5;

    private CompraRepository compraRepository;
    private ProveedorRepository proveedorRepository;
    private InventarioService inventarioService;
    private CompraService service;

    @BeforeEach
    void setUp() {
        compraRepository = mock(CompraRepository.class);
        proveedorRepository = mock(ProveedorRepository.class);
        inventarioService = mock(InventarioService.class);
        service = new CompraService(compraRepository, proveedorRepository, inventarioService);
    }

    private void dadoProveedor(String nombre, boolean activo) {
        var proveedor = new Proveedor();
        proveedor.setIdProveedor(ID_PROVEEDOR);
        proveedor.setNombre(nombre);
        proveedor.setEstado(activo);
        when(proveedorRepository.findById(ID_PROVEEDOR)).thenReturn(Optional.of(proveedor));
    }

    private void dadoInsertarConId(Integer id) {
        when(compraRepository.insertar(any(Compra.class))).thenReturn(id);
    }

    private static Compra fila(Integer id, String nombreProveedor, LocalDate fecha,
                               EstadoCompra estado, BigDecimal total, int items) {
        var compra = new Compra();
        compra.setIdCompra(id);
        compra.setIdProveedor(ID_PROVEEDOR);
        compra.setFecha(fecha);
        compra.setEstado(estado);
        compra.setNombreProveedor(nombreProveedor);
        compra.setTotalCompra(total);
        compra.setCantidadItems(items);
        return compra;
    }

    private static CrearCompraRequest alta(LocalDate fecha, EstadoCompra estado) {
        return new CrearCompraRequest(ID_PROVEEDOR, fecha, estado);
    }

    @Test
    @DisplayName("crear inserta PENDIENTE con la fecha dada y responde total 0, ítems 0 y editable")
    void crearInsertaPendienteYRespondeSinDetalle() {
        dadoProveedor("Harinas SA", true);
        dadoInsertarConId(42);
        var fecha = LocalDate.now().minusDays(3);

        var r = service.crear(alta(fecha, EstadoCompra.PENDIENTE));

        var captor = ArgumentCaptor.forClass(Compra.class);
        verify(compraRepository).insertar(captor.capture());
        var guardada = captor.getValue();
        assertThat(guardada.getIdProveedor()).isEqualTo(ID_PROVEEDOR);
        assertThat(guardada.getFecha()).isEqualTo(fecha);
        assertThat(guardada.getEstado()).isEqualTo(EstadoCompra.PENDIENTE);

        assertThat(r.idCompra()).isEqualTo(42);
        assertThat(r.proveedor().idProveedor()).isEqualTo(ID_PROVEEDOR);
        assertThat(r.proveedor().nombre()).isEqualTo("Harinas SA");
        assertThat(r.fecha()).isEqualTo(fecha);
        assertThat(r.estado()).isEqualTo("PENDIENTE");
        // Una compra recién registrada no tiene detalle: su total es 0 con escala 2.
        assertThat(r.totalCompra()).isEqualByComparingTo("0.00");
        assertThat(r.totalCompra().scale()).isEqualTo(2);
        assertThat(r.cantidadItems()).isZero();
        assertThat(r.editable()).isTrue();
    }

    @Test
    @DisplayName("CP-04: crear sin fecha registra la de hoy")
    void crearSinFechaRegistraLaDeHoy() {
        dadoProveedor("Harinas SA", true);
        dadoInsertarConId(42);

        var r = service.crear(alta(null, null));

        var captor = ArgumentCaptor.forClass(Compra.class);
        verify(compraRepository).insertar(captor.capture());
        assertThat(captor.getValue().getFecha()).isEqualTo(LocalDate.now());
        assertThat(r.fecha()).isEqualTo(LocalDate.now());
        assertThat(r.estado()).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("CP-06: crear con fecha pasada es válido")
    void crearConFechaPasadaEsValido() {
        dadoProveedor("Harinas SA", true);
        dadoInsertarConId(42);
        var pasada = LocalDate.now().minusDays(90);

        var r = service.crear(alta(pasada, null));

        assertThat(r.fecha()).isEqualTo(pasada);
        verify(compraRepository).insertar(any(Compra.class));
    }

    @Test
    @DisplayName("R-07: crear con fecha futura responde 400 FECHA_FUTURA sin insertar")
    void crearConFechaFuturaResponde400() {
        dadoProveedor("Harinas SA", true);

        assertThatThrownBy(() -> service.crear(alta(LocalDate.now().plusDays(1), null)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FECHA_FUTURA");

        verify(compraRepository, never()).insertar(any(Compra.class));
    }

    @Test
    @DisplayName("CP-02: crear con proveedor inexistente responde 409 PROVEEDOR_INEXISTENTE")
    void crearConProveedorInexistenteResponde409() {
        when(proveedorRepository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.crear(new CrearCompraRequest(
                9999, LocalDate.now(), null)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PROVEEDOR_INEXISTENTE");

        verify(compraRepository, never()).insertar(any(Compra.class));
    }

    @Test
    @DisplayName("CP-03/R-06: crear con proveedor inactivo responde 409 PROVEEDOR_INACTIVO")
    void crearConProveedorInactivoResponde409() {
        dadoProveedor("Harinas SA", false);

        assertThatThrownBy(() -> service.crear(alta(null, null)))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PROVEEDOR_INACTIVO");

        verify(compraRepository, never()).insertar(any(Compra.class));
    }

    @ParameterizedTest
    @EnumSource(value = EstadoCompra.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDIENTE")
    @DisplayName("R-01: crear con estado terminal responde 400 y nunca inserta la compra")
    void crearConEstadoTerminalResponde400SinInsertar(EstadoCompra estado) {
        dadoProveedor("Harinas SA", true);

        assertThatThrownBy(() -> service.crear(alta(null, estado)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "VALIDACION_FALLIDA");

        verify(compraRepository, never()).insertar(any(Compra.class));
    }

    @Test
    @DisplayName("crear sin estado nace PENDIENTE")
    void crearSinEstadoNacePendiente() {
        dadoProveedor("Harinas SA", true);
        dadoInsertarConId(42);

        var r = service.crear(alta(null, null));

        var captor = ArgumentCaptor.forClass(Compra.class);
        verify(compraRepository).insertar(captor.capture());
        assertThat(captor.getValue().getEstado()).isEqualTo(EstadoCompra.PENDIENTE);
        assertThat(r.estado()).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("R-04: actualizar una compra PENDIENTE escribe los campos y responde la relectura")
    void actualizarPendienteEscribeYRelee() {
        var hoy = LocalDate.now();
        when(compraRepository.buscarPorId(7)).thenReturn(
                Optional.of(fila(7, "Harinas SA", hoy.minusDays(5), EstadoCompra.PENDIENTE,
                        new BigDecimal("150.00"), 2)),
                Optional.of(fila(7, "Granos Ltda", hoy, EstadoCompra.PENDIENTE,
                        new BigDecimal("150.00"), 2)));
        dadoProveedor("Granos Ltda", true);

        var r = service.actualizar(7, new ActualizarCompraRequest(ID_PROVEEDOR, hoy));

        verify(compraRepository).actualizar(7, ID_PROVEEDOR, hoy);
        assertThat(r.idCompra()).isEqualTo(7);
        assertThat(r.proveedor().idProveedor()).isEqualTo(ID_PROVEEDOR);
        assertThat(r.proveedor().nombre()).isEqualTo("Granos Ltda");
        assertThat(r.fecha()).isEqualTo(hoy);
        assertThat(r.estado()).isEqualTo("PENDIENTE");
        // El total y los ítems salen de la base, no de la fila leída antes de escribir.
        assertThat(r.totalCompra()).isEqualByComparingTo("150.00");
        assertThat(r.cantidadItems()).isEqualTo(2);
        assertThat(r.editable()).isTrue();
    }

    @Test
    @DisplayName("actualizar una compra inexistente responde 404 sin escribir")
    void actualizarInexistenteResponde404() {
        when(compraRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(9999,
                new ActualizarCompraRequest(ID_PROVEEDOR, LocalDate.now())))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la compra con id 9999.");

        verify(compraRepository, never()).actualizar(any(), any(), any());
        verifyNoInteractions(proveedorRepository);
    }

    @ParameterizedTest
    @EnumSource(value = EstadoCompra.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDIENTE")
    @DisplayName("R-04: actualizar una compra RECIBIDA o CANCELADA responde 409 sin escribir")
    void actualizarConEstadoTerminalResponde409SinEscribir(EstadoCompra estado) {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now().minusDays(5), estado,
                        new BigDecimal("150.00"), 2)));

        assertThatThrownBy(() -> service.actualizar(7,
                new ActualizarCompraRequest(ID_PROVEEDOR, LocalDate.now())))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "COMPRA_NO_EDITABLE");

        verify(compraRepository, never()).actualizar(any(), any(), any());
        // El estado se valida antes que el proveedor, así que ni se consulta.
        verifyNoInteractions(proveedorRepository);
    }

    @Test
    @DisplayName("CP-02: actualizar a un proveedor inexistente responde 409 sin escribir")
    void actualizarAProveedorInexistenteResponde409() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now().minusDays(5), EstadoCompra.PENDIENTE,
                        BigDecimal.ZERO, 0)));
        when(proveedorRepository.findById(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.actualizar(7,
                new ActualizarCompraRequest(9999, LocalDate.now())))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "PROVEEDOR_INEXISTENTE");

        verify(compraRepository, never()).actualizar(any(), any(), any());
    }

    @Test
    @DisplayName("R-06: actualizar a un proveedor inactivo está permitido")
    void actualizarAProveedorInactivoEsPermitido() {
        var hoy = LocalDate.now();
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", hoy.minusDays(5), EstadoCompra.PENDIENTE,
                        BigDecimal.ZERO, 0)));
        dadoProveedor("Vieja SA", false);

        var r = service.actualizar(7, new ActualizarCompraRequest(ID_PROVEEDOR, hoy));

        verify(compraRepository).actualizar(7, ID_PROVEEDOR, hoy);
        assertThat(r.editable()).isTrue();
    }

    @Test
    @DisplayName("R-07: actualizar con fecha futura responde 400 FECHA_FUTURA sin escribir")
    void actualizarConFechaFuturaResponde400() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now().minusDays(5), EstadoCompra.PENDIENTE,
                        BigDecimal.ZERO, 0)));
        dadoProveedor("Harinas SA", true);

        assertThatThrownBy(() -> service.actualizar(7,
                new ActualizarCompraRequest(ID_PROVEEDOR, LocalDate.now().plusDays(1))))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "FECHA_FUTURA");

        verify(compraRepository, never()).actualizar(any(), any(), any());
    }

    @Test
    @DisplayName("Si la relectura posterior a la escritura no devuelve la compra responde 404")
    void actualizarSinRelecturaResponde404() {
        var hoy = LocalDate.now();
        when(compraRepository.buscarPorId(7)).thenReturn(
                Optional.of(fila(7, "Harinas SA", hoy.minusDays(5), EstadoCompra.PENDIENTE,
                        BigDecimal.ZERO, 0)),
                Optional.empty());
        dadoProveedor("Harinas SA", true);

        assertThatThrownBy(() -> service.actualizar(7,
                new ActualizarCompraRequest(ID_PROVEEDOR, hoy)))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la compra con id 7.");

        verify(compraRepository).actualizar(7, ID_PROVEEDOR, hoy);
    }

    @Test
    @DisplayName("listar pagina con los cuatro filtros, el orden resuelto y el total del contar")
    void listarConFiltrosUsaElRepositorioYDevuelveLaPagina() {
        var hoy = LocalDate.now();
        var inicio = hoy.minusDays(10);
        var fin = hoy.minusDays(1);
        var compra = fila(7, "Harinas SA", hoy.minusDays(5), EstadoCompra.PENDIENTE,
                new BigDecimal("150.00"), 2);
        when(compraRepository.buscar(eq(ID_PROVEEDOR), eq(EstadoCompra.PENDIENTE), eq(inicio),
                eq(fin), eq("c.fecha"), eq(false), eq(10), eq(20L)))
                .thenReturn(List.of(compra));
        when(compraRepository.contar(ID_PROVEEDOR, EstadoCompra.PENDIENTE, inicio, fin))
                .thenReturn(21L);

        var r = service.listar(ID_PROVEEDOR, EstadoCompra.PENDIENTE, inicio, fin, 2, 10, null);

        assertThat(r.content()).hasSize(1);
        assertThat(r.content().get(0).idCompra()).isEqualTo(7);
        assertThat(r.content().get(0).estado()).isEqualTo("PENDIENTE");
        assertThat(r.page()).isEqualTo(2);
        assertThat(r.size()).isEqualTo(10);
        assertThat(r.totalElements()).isEqualTo(21L);
        // Por defecto "fecha,desc": page 2 con size 10 es offset 20, calculado en long.
        verify(compraRepository).buscar(eq(ID_PROVEEDOR), eq(EstadoCompra.PENDIENTE), eq(inicio),
                eq(fin), eq("c.fecha"), eq(false), eq(10), eq(20L));
        verify(compraRepository).contar(ID_PROVEEDOR, EstadoCompra.PENDIENTE, inicio, fin);
    }

    @Test
    @DisplayName("listar sin filtros ni sort usa el orden por defecto fecha,desc")
    void listarSinFiltrosUsaElOrdenPorDefecto() {
        when(compraRepository.buscar(any(), any(), any(), any(), eq("c.fecha"), eq(false),
                eq(20), eq(0L))).thenReturn(List.of());
        when(compraRepository.contar(any(), any(), any(), any())).thenReturn(0L);

        var r = service.listar(null, null, null, null, 0, 20, null);

        assertThat(r.content()).isEmpty();
        assertThat(r.totalElements()).isZero();
    }

    @Test
    @DisplayName("RANGO_FECHAS_INVALIDO: listar con el rango invertido no consulta la base")
    void listarConRangoInvertidoResponde400() {
        var hoy = LocalDate.now();

        assertThatThrownBy(() -> service.listar(null, null, hoy, hoy.minusDays(1), 0, 10, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");

        verifyNoInteractions(compraRepository);
    }

    @Test
    @DisplayName("listar con un campo de orden no listado responde 400 CAMPO_ORDEN_INVALIDO")
    void listarConCampoDeOrdenNoListadoResponde400() {
        assertThatThrownBy(() -> service.listar(null, null, null, null, 0, 10, "proveedor"))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "CAMPO_ORDEN_INVALIDO");

        verifyNoInteractions(compraRepository);
    }

    @Test
    @DisplayName("listar con size mayor a 100 responde 400 PARAMETRO_INVALIDO")
    void listarConSizeGrandeResponde400() {
        assertThatThrownBy(() -> service.listar(null, null, null, null, 0, 101, null))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "PARAMETRO_INVALIDO");

        verifyNoInteractions(compraRepository);
    }

    private static ResumenEstadoFila filaResumen(EstadoCompra estado, long cantidad,
                                                 String total) {
        return new ResumenEstadoFila(estado, cantidad, new BigDecimal(total));
    }

    @Test
    @DisplayName("RANGO_FECHAS_INVALIDO: resumen con el rango invertido no consulta la base")
    void resumenConRangoInvertidoResponde400() {
        var hoy = LocalDate.now();

        assertThatThrownBy(() -> service.resumen(hoy, hoy.minusDays(1)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");

        verifyNoInteractions(compraRepository);
    }

    @Test
    @DisplayName("R-04: el resumen agrupa por estado y excluye las canceladas de ambos gastos")
    void resumenAgrupaPorEstadoYExcluyeCanceladasDeLosGastos() {
        var inicio = LocalDate.of(2026, 9, 1);
        var fin = LocalDate.of(2026, 9, 30);
        // El total llega con escala 4 del SUM de MySQL; la respuesta lo fija a 2.
        when(compraRepository.resumenPorPeriodo(inicio, fin)).thenReturn(List.of(
                filaResumen(EstadoCompra.RECIBIDA, 8, "4850000.0000"),
                filaResumen(EstadoCompra.PENDIENTE, 2, "620000.00"),
                filaResumen(EstadoCompra.CANCELADA, 1, "85000.00")));

        var r = service.resumen(inicio, fin);

        assertThat(r.periodo().desde()).isEqualTo(inicio);
        assertThat(r.periodo().hasta()).isEqualTo(fin);
        assertThat(r.recibidas().cantidad()).isEqualTo(8);
        assertThat(r.recibidas().total()).isEqualByComparingTo("4850000.00");
        assertThat(r.recibidas().total().scale()).isEqualTo(2);
        assertThat(r.pendientes().cantidad()).isEqualTo(2);
        assertThat(r.pendientes().total()).isEqualByComparingTo("620000.00");
        assertThat(r.canceladas().cantidad()).isEqualTo(1);
        assertThat(r.canceladas().total()).isEqualByComparingTo("85000.00");
        // R-04: ejecutado solo RECIBIDA, comprometido solo PENDIENTE; 85000.00 de canceladas
        // no financia ninguno de los dos.
        assertThat(r.gastoEjecutado()).isEqualByComparingTo("4850000.00");
        assertThat(r.gastoEjecutado().scale()).isEqualTo(2);
        assertThat(r.gastoComprometido()).isEqualByComparingTo("620000.00");
        assertThat(r.gastoComprometido().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("CP-17: un estado sin compras en el rango responde 0 y 0.00, no un error")
    void resumenConEstadosAusentesRespondeCeros() {
        var hoy = LocalDate.now();
        when(compraRepository.resumenPorPeriodo(hoy, hoy))
                .thenReturn(List.of(filaResumen(EstadoCompra.RECIBIDA, 1, "150.00")));

        var r = service.resumen(hoy, hoy);

        assertThat(r.recibidas().cantidad()).isEqualTo(1);
        assertThat(r.recibidas().total()).isEqualByComparingTo("150.00");
        assertThat(r.pendientes().cantidad()).isZero();
        assertThat(r.pendientes().total()).isEqualByComparingTo("0.00");
        assertThat(r.pendientes().total().scale()).isEqualTo(2);
        assertThat(r.canceladas().cantidad()).isZero();
        assertThat(r.canceladas().total()).isEqualByComparingTo("0.00");
        assertThat(r.canceladas().total().scale()).isEqualTo(2);
        assertThat(r.gastoEjecutado()).isEqualByComparingTo("150.00");
        assertThat(r.gastoComprometido()).isEqualByComparingTo("0.00");
        assertThat(r.gastoComprometido().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("resumen sin filtros pasa las fechas nulas y el periodo responde null")
    void resumenSinFiltrosDevuelvePeriodoNulo() {
        when(compraRepository.resumenPorPeriodo(null, null)).thenReturn(List.of());

        var r = service.resumen(null, null);

        verify(compraRepository).resumenPorPeriodo(null, null);
        assertThat(r.periodo().desde()).isNull();
        assertThat(r.periodo().hasta()).isNull();
        // CP-17: un rango sin compras responde 200 con todo en cero, no un 404 ni un 400.
        assertThat(r.recibidas().cantidad()).isZero();
        assertThat(r.recibidas().total()).isEqualByComparingTo("0.00");
        assertThat(r.recibidas().total().scale()).isEqualTo(2);
        assertThat(r.gastoEjecutado()).isEqualByComparingTo("0.00");
        assertThat(r.gastoEjecutado().scale()).isEqualTo(2);
        assertThat(r.gastoComprometido()).isEqualByComparingTo("0.00");
        assertThat(r.gastoComprometido().scale()).isEqualTo(2);
    }

    private static GastoProveedorFila filaGasto(String proveedor, long cantidad, String total) {
        return new GastoProveedorFila(proveedor, cantidad, new BigDecimal(total));
    }

    @Test
    @DisplayName("RANGO_FECHAS_INVALIDO: el gasto por proveedor con rango invertido no consulta "
            + "la base")
    void gastoPorProveedorConRangoInvertidoResponde400() {
        var hoy = LocalDate.now();

        assertThatThrownBy(() -> service.gastoPorProveedor(hoy, hoy.minusDays(1)))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasFieldOrPropertyWithValue("codigo", "RANGO_FECHAS_INVALIDO");

        verifyNoInteractions(compraRepository);
    }

    @Test
    @DisplayName("CP-15: el gasto por proveedor preserva el orden por total descendente del "
            + "repositorio y solo trae RECIBIDA")
    void gastoPorProveedorPreservaElOrdenYSoloAgrupaRecibidas() {
        var inicio = LocalDate.of(2026, 9, 1);
        var fin = LocalDate.of(2026, 9, 30);
        // El repositorio ya filtra RECIBIDA y ordena por total descendente (se comprueba en
        // CompraRepositoryTest); el servicio no reordena ni reinterpreta las filas.
        when(compraRepository.gastoPorProveedor(inicio, fin)).thenReturn(List.of(
                filaGasto("Harinas del Caribe S.A.S.", 4, "2340000.00"),
                filaGasto("Distribuidora Lácteos", 3, "1810000.00")));

        var r = service.gastoPorProveedor(inicio, fin);

        assertThat(r).extracting(GastoProveedorResponse::proveedor)
                .containsExactly("Harinas del Caribe S.A.S.", "Distribuidora Lácteos");
        assertThat(r.get(0).total()).isEqualByComparingTo("2340000.00");
        assertThat(r.get(1).total()).isEqualByComparingTo("1810000.00");
        assertThat(r.get(0).cantidadCompras()).isEqualTo(4);
        assertThat(r.get(1).cantidadCompras()).isEqualTo(3);
    }

    @Test
    @DisplayName("CP-16: el porcentaje de cada proveedor se calcula sobre la suma global del "
            + "período, con HALF_UP a escala 2")
    void gastoPorProveedorCalculaElPorcentajeSobreElTotalGlobal() {
        var inicio = LocalDate.of(2026, 9, 1);
        var fin = LocalDate.of(2026, 9, 30);
        when(compraRepository.gastoPorProveedor(inicio, fin)).thenReturn(List.of(
                filaGasto("Harinas del Caribe S.A.S.", 4, "2340000.00"),
                filaGasto("Distribuidora Lácteos", 3, "1810000.00")));

        var r = service.gastoPorProveedor(inicio, fin);

        // Suma global 4150000.00: 2340000/4150000 × 100 = 56.3855… → 56.39 y
        // 1810000/4150000 × 100 = 43.6144… → 43.61; los dos suman exactamente 100.00.
        assertThat(r.get(0).porcentaje()).isEqualByComparingTo("56.39");
        assertThat(r.get(0).porcentaje().scale()).isEqualTo(2);
        assertThat(r.get(1).porcentaje()).isEqualByComparingTo("43.61");
        assertThat(r.get(1).porcentaje().scale()).isEqualTo(2);
    }

    @Test
    @DisplayName("CP-16: los porcentajes con residuo de redondeo suman 100 ± 0.01 sin ajuste "
            + "del último")
    void gastoPorProveedorAdmiteElResiduoDeRedondeo() {
        var hoy = LocalDate.now();
        // Tres totales iguales: cada uno es 33.333…% y HALF_UP deja 33.33; la suma es 99.99,
        // dentro del residuo que CP-16 permite y sin inflar artificialmente el último.
        when(compraRepository.gastoPorProveedor(hoy, hoy)).thenReturn(List.of(
                filaGasto("Proveedor A", 1, "1.00"),
                filaGasto("Proveedor B", 1, "1.00"),
                filaGasto("Proveedor C", 1, "1.00")));

        var r = service.gastoPorProveedor(hoy, hoy);

        assertThat(r).extracting(GastoProveedorResponse::porcentaje)
                .allSatisfy(porcentaje -> {
                    assertThat(porcentaje).isEqualByComparingTo("33.33");
                    assertThat(porcentaje.scale()).isEqualTo(2);
                });
        var suma = r.stream().map(GastoProveedorResponse::porcentaje)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(suma).isBetween(new BigDecimal("99.99"), new BigDecimal("100.01"));
    }

    @Test
    @DisplayName("CP-17: un rango sin compras recibidas devuelve la lista vacía, no un error")
    void gastoPorProveedorDeUnRangoVacioDevuelveListaVacia() {
        when(compraRepository.gastoPorProveedor(null, null)).thenReturn(List.of());

        var r = service.gastoPorProveedor(null, null);

        verify(compraRepository).gastoPorProveedor(null, null);
        assertThat(r).isEmpty();
    }

    @Test
    @DisplayName("obtener devuelve la compra con su proveedor y sus totales")
    void obtenerDevuelveLaCompra() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now().minusDays(5), EstadoCompra.RECIBIDA,
                        new BigDecimal("150.00"), 2)));

        var r = service.obtener(7);

        assertThat(r.idCompra()).isEqualTo(7);
        assertThat(r.proveedor().nombre()).isEqualTo("Harinas SA");
        assertThat(r.estado()).isEqualTo("RECIBIDA");
        assertThat(r.totalCompra()).isEqualByComparingTo("150.00");
        assertThat(r.cantidadItems()).isEqualTo(2);
        assertThat(r.editable()).isFalse();
    }

    @Test
    @DisplayName("obtener una compra inexistente responde 404")
    void obtenerInexistenteResponde404() {
        when(compraRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.obtener(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la compra con id 9999.");
    }

    @Test
    @DisplayName("esEditable solo es true para una compra PENDIENTE existente")
    void esEditableSoloParaPendiente() {
        when(compraRepository.buscarPorId(1)).thenReturn(Optional.of(
                fila(1, "A", LocalDate.now(), EstadoCompra.PENDIENTE, BigDecimal.ZERO, 0)));
        when(compraRepository.buscarPorId(2)).thenReturn(Optional.of(
                fila(2, "A", LocalDate.now(), EstadoCompra.RECIBIDA, BigDecimal.ZERO, 0)));
        when(compraRepository.buscarPorId(3)).thenReturn(Optional.of(
                fila(3, "A", LocalDate.now(), EstadoCompra.CANCELADA, BigDecimal.ZERO, 0)));
        when(compraRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThat(service.esEditable(1)).isTrue();
        assertThat(service.esEditable(2)).isFalse();
        assertThat(service.esEditable(3)).isFalse();
        // Una compra inexistente no es editable y no lanza: HU-14 consulta esto antes de actuar.
        assertThat(service.esEditable(9999)).isFalse();
    }

    @Test
    @DisplayName("R-01/CP-09: recibir suma cada línea por InventarioService y cambia el estado")
    void recibirSumaCadaLineaYDevuelveLosMovimientos() {
        var hoy = LocalDate.now();
        cuandoRecibirEsPosible();
        when(inventarioService.registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_COMPRA, "detalle_compra", 1, "Compra #7"))
                .thenReturn(new SaldosInventario(new BigDecimal("12.50"),
                        new BigDecimal("112.50")));
        when(inventarioService.registrarEntrada(6, new BigDecimal("50.00"),
                TipoMovimiento.ENTRADA_COMPRA, "detalle_compra", 2, "Compra #7"))
                .thenReturn(new SaldosInventario(new BigDecimal("45.00"),
                        new BigDecimal("95.00")));

        var r = service.recibir(7);

        // R-01: las entradas pasan por InventarioService, nunca un UPDATE directo.
        verify(inventarioService).registrarEntrada(5, new BigDecimal("100.00"),
                TipoMovimiento.ENTRADA_COMPRA, "detalle_compra", 1, "Compra #7");
        verify(inventarioService).registrarEntrada(6, new BigDecimal("50.00"),
                TipoMovimiento.ENTRADA_COMPRA, "detalle_compra", 2, "Compra #7");
        verify(compraRepository).cambiarEstado(7, EstadoCompra.RECIBIDA);

        assertThat(r.idCompra()).isEqualTo(7);
        assertThat(r.estado()).isEqualTo("RECIBIDA");
        assertThat(r.totalCompra()).isEqualByComparingTo("1400.00");
        assertThat(r.movimientosGenerados()).hasSize(2);
        assertThat(r.movimientosGenerados().get(0).materiaPrima()).isEqualTo("Harina de trigo");
        assertThat(r.movimientosGenerados().get(0).cantidad()).isEqualByComparingTo("100.00");
        assertThat(r.movimientosGenerados().get(0).saldoAnterior()).isEqualByComparingTo("12.50");
        assertThat(r.movimientosGenerados().get(0).saldoPosterior()).isEqualByComparingTo("112.50");
        assertThat(r.movimientosGenerados().get(1).materiaPrima()).isEqualTo("Azúcar");
        assertThat(r.movimientosGenerados().get(1).saldoAnterior()).isEqualByComparingTo("45.00");
        assertThat(r.movimientosGenerados().get(1).saldoPosterior()).isEqualByComparingTo("95.00");
    }

    @Test
    @DisplayName("CP-14/R-03: recibir sin detalle responde 409 sin tocar inventario ni estado")
    void recibirSinDetalleNoTocaInventarioNiEstado() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.PENDIENTE,
                        new BigDecimal("1400.00"), 2)));
        when(compraRepository.contarDetalles(7)).thenReturn(0);

        assertThatThrownBy(() -> service.recibir(7))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "COMPRA_SIN_DETALLE");

        verifyNoInteractions(inventarioService);
        verify(compraRepository, never()).cambiarEstado(any(), any());
    }

    @Test
    @DisplayName("CP-15: recibir una compra ya RECIBIDA responde TRANSICION_INVALIDA sin inventario")
    void recibirDesdeRecibidaResponde409SinInventario() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.RECIBIDA,
                        new BigDecimal("1400.00"), 2)));

        assertThatThrownBy(() -> service.recibir(7))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");

        // CP-15: la validación va antes del inventario; una doble recepción no dobla el stock.
        verifyNoInteractions(inventarioService);
        verify(compraRepository, never()).cambiarEstado(any(), any());
    }

    @Test
    @DisplayName("recibir una compra CANCELADA responde TRANSICION_INVALIDA sin inventario")
    void recibirDesdeCanceladaResponde409() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.CANCELADA,
                        new BigDecimal("1400.00"), 2)));

        assertThatThrownBy(() -> service.recibir(7))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");

        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("CP-16: si una línea falla la excepción se propaga y no se cambia el estado")
    void recibirConFalloEnUnaLineaSePropagaSinCambiarElEstado() {
        cuandoRecibirEsPosible();
        when(inventarioService.registrarEntrada(eq(5), any(), any(), any(), any(), any()))
                .thenReturn(new SaldosInventario(new BigDecimal("12.50"),
                        new BigDecimal("112.50")));
        when(inventarioService.registrarEntrada(eq(6), any(), any(), any(), any(), any()))
                .thenThrow(new ConflictoNegocioException("FALLO_INVENTARIO",
                        "No se pudo registrar la entrada."));

        assertThatThrownBy(() -> service.recibir(7))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "FALLO_INVENTARIO");

        // El rollback lo da la transacción; el servicio no oculta el fallo ni cambia el estado.
        verify(compraRepository, never()).cambiarEstado(any(), any());
    }

    @Test
    @DisplayName("recibir una compra inexistente responde 404 sin tocar inventario")
    void recibirInexistenteResponde404() {
        when(compraRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recibir(9999))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la compra con id 9999.");

        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("CP-13: cancelar desde PENDIENTE cambia el estado y no toca el inventario")
    void cancelarPendienteCambiaElEstadoSinInventario() {
        when(compraRepository.buscarPorId(7)).thenReturn(
                Optional.of(fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.PENDIENTE,
                        new BigDecimal("150.00"), 2)),
                Optional.of(fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.CANCELADA,
                        new BigDecimal("150.00"), 2)));

        var r = service.cancelar(7);

        verify(compraRepository).cambiarEstado(7, EstadoCompra.CANCELADA);
        // CP-13: cancelar no cambia el stock: nunca suma ni resta inventario.
        verifyNoInteractions(inventarioService);
        assertThat(r.idCompra()).isEqualTo(7);
        assertThat(r.estado()).isEqualTo("CANCELADA");
    }

    @Test
    @DisplayName("CP-12: cancelar una compra RECIBIDA responde TRANSICION_INVALIDA")
    void cancelarDesdeRecibidaResponde409() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.RECIBIDA,
                        new BigDecimal("150.00"), 2)));

        assertThatThrownBy(() -> service.cancelar(7))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");

        verify(compraRepository, never()).cambiarEstado(any(), any());
        verifyNoInteractions(inventarioService);
    }

    @Test
    @DisplayName("cancelar una compra ya CANCELADA responde TRANSICION_INVALIDA")
    void cancelarDesdeCanceladaResponde409() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.CANCELADA,
                        new BigDecimal("150.00"), 2)));

        assertThatThrownBy(() -> service.cancelar(7))
                .isInstanceOf(ConflictoNegocioException.class)
                .hasFieldOrPropertyWithValue("codigo", "TRANSICION_INVALIDA");

        verify(compraRepository, never()).cambiarEstado(any(), any());
    }

    @Test
    @DisplayName("transicionInvalida devuelve la excepción con el estado actual y los permitidos")
    void transicionInvalidaDevuelveLaExcepcionConstruida() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.RECIBIDA,
                        BigDecimal.ZERO, 0)));

        var excepcion = service.transicionInvalida(7, EstadoCompra.PENDIENTE);

        assertThat(excepcion).isInstanceOf(ConflictoNegocioException.class);
        assertThat(excepcion.getCodigo()).isEqualTo("TRANSICION_INVALIDA");
        // Contrato §7: menciona el estado actual, el destino y las transiciones permitidas.
        assertThat(excepcion.getMessage())
                .contains("RECIBIDA", "PENDIENTE")
                .contains("no hay transiciones disponibles");
    }

    @Test
    @DisplayName("transicionInvalida desde un estado con destinos lista los permitidos")
    void transicionInvalidaDesdeEstadoNoTerminalListaLosPermitidos() {
        when(compraRepository.buscarPorId(7)).thenReturn(Optional.of(
                fila(7, "Harinas SA", LocalDate.now(), EstadoCompra.PENDIENTE,
                        BigDecimal.ZERO, 0)));

        var excepcion = service.transicionInvalida(7, EstadoCompra.PENDIENTE);

        assertThat(excepcion.getCodigo()).isEqualTo("TRANSICION_INVALIDA");
        assertThat(excepcion.getMessage())
                .contains("las transiciones disponibles son")
                .contains("CANCELADA", "RECIBIDA");
    }

    @Test
    @DisplayName("transicionInvalida de una compra inexistente responde 404")
    void transicionInvalidaInexistenteResponde404() {
        when(compraRepository.buscarPorId(9999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.transicionInvalida(9999, EstadoCompra.RECIBIDA))
                .isInstanceOf(RecursoNoEncontradoException.class)
                .hasMessage("No se encontró la compra con id 9999.");
    }

    /**
     * Deja una compra 7 PENDIENTE con dos líneas de detalle, lista para recibir.
     *
     * <p>La primera {@code buscarPorId} es la carga inicial y la segunda la relectura posterior a
     * {@code cambiarEstado}, con la que el servicio monta el total de la respuesta.
     */
    private void cuandoRecibirEsPosible() {
        var hoy = LocalDate.now();
        when(compraRepository.buscarPorId(7)).thenReturn(
                Optional.of(fila(7, "Harinas SA", hoy, EstadoCompra.PENDIENTE,
                        new BigDecimal("1400.00"), 2)),
                Optional.of(fila(7, "Harinas SA", hoy, EstadoCompra.RECIBIDA,
                        new BigDecimal("1400.00"), 2)));
        when(compraRepository.contarDetalles(7)).thenReturn(2);
        when(compraRepository.listarDetalles(7)).thenReturn(List.of(
                new LineaDetalleCompra(1, 5, new BigDecimal("100.00"),
                        new BigDecimal("12.00"), "Harina de trigo"),
                new LineaDetalleCompra(2, 6, new BigDecimal("50.00"),
                        new BigDecimal("8.00"), "Azúcar")));
    }
}
