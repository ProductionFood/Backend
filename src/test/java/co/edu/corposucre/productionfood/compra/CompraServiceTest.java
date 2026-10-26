package co.edu.corposucre.productionfood.compra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import co.edu.corposucre.productionfood.proveedor.Proveedor;
import co.edu.corposucre.productionfood.proveedor.ProveedorRepository;

class CompraServiceTest {

    private static final Integer ID_PROVEEDOR = 5;

    private CompraRepository compraRepository;
    private ProveedorRepository proveedorRepository;
    private CompraService service;

    @BeforeEach
    void setUp() {
        compraRepository = mock(CompraRepository.class);
        proveedorRepository = mock(ProveedorRepository.class);
        service = new CompraService(compraRepository, proveedorRepository);
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
}
