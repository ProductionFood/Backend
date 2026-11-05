package co.edu.corposucre.productionfood.reporte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.inventario.AlertaService;
import co.edu.corposucre.productionfood.inventario.dto.AlertaStockResponse;
import co.edu.corposucre.productionfood.inventario.dto.ListadoAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;
import co.edu.corposucre.productionfood.reporte.dto.ComprasPendientesResponse;
import co.edu.corposucre.productionfood.reporte.dto.PedidosHoyResponse;
import co.edu.corposucre.productionfood.reporte.dto.PorVencerResponse;
import co.edu.corposucre.productionfood.reporte.dto.ProduccionHoyResponse;

/** Pruebas unitarias de {@link DashboardService} (HU-24) con repositorio y alertas simulados. */
class DashboardServiceTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 9, 18, 10, 42, 5);

    private DashboardRepository repository;
    private AlertaService alertaService;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        repository = mock(DashboardRepository.class);
        alertaService = mock(AlertaService.class);
        service = new DashboardService(repository, alertaService);

        when(repository.ahora()).thenReturn(AHORA);
        when(repository.pedidosDelDia()).thenReturn(
                new PedidosHoyResponse(0, new BigDecimal("0.00"), new LinkedHashMap<>()));
        when(repository.comprasPendientes())
                .thenReturn(new ComprasPendientesResponse(0, new BigDecimal("0.00"), null));
        when(repository.produccionDelDia()).thenReturn(
                new ProduccionHoyResponse(0, new LinkedHashMap<>(), new BigDecimal("0.00")));
        when(repository.proximosAVencer(7, 10))
                .thenReturn(new PorVencerResponse(0, 0, List.of()));
    }

    private static AlertaStockResponse alerta(String nombre, String disponible, String nivel) {
        return new AlertaStockResponse(1, nombre, "kg", new BigDecimal(disponible),
                new BigDecimal("5.00"), new BigDecimal("5.00"), nivel, new BigDecimal("1.00"),
                new BigDecimal("5.00"), AHORA);
    }

    @Test
    @DisplayName("R-01: fecha y generadoEn salen de la base de datos, no del reloj de Java")
    void fechaYGeneradoEnSalenDeLaBase() {
        when(alertaService.listar(false)).thenReturn(listado(List.of()));

        var dashboard = service.obtener(7);

        assertThat(dashboard.generadoEn()).isEqualTo(AHORA);
        assertThat(dashboard.fecha()).isEqualTo(LocalDate.of(2026, 9, 18));
    }

    @Test
    @DisplayName("CA-03: el stock bajo viene de AlertaService.listar(false), en su orden, sin SQL propio")
    void stockBajoVieneDeAlertaService() {
        var items = List.of(alerta("Levadura", "0.00", "AGOTADO"),
                alerta("Harina", "1.00", "BAJO"), alerta("Sal", "2.00", "BAJO"));
        when(alertaService.listar(false)).thenReturn(listado(items));

        var stock = service.obtener(7).stockBajo();

        assertThat(stock.cantidad()).isEqualTo(3).isEqualTo(stock.items().size());
        assertThat(stock.agotadas()).isEqualTo(1);
        assertThat(stock.items()).extracting("nombre", "nivel", "faltante")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Levadura", "AGOTADO",
                                new BigDecimal("5.00")),
                        org.assertj.core.groups.Tuple.tuple("Harina", "BAJO",
                                new BigDecimal("5.00")),
                        org.assertj.core.groups.Tuple.tuple("Sal", "BAJO",
                                new BigDecimal("5.00")));
        assertThat(stock.items().get(0).disponible()).isEqualByComparingTo("0.00");
        assertThat(stock.items().get(0).minimo()).isEqualByComparingTo("5.00");
        verify(alertaService).listar(false);
        verifyNoMoreInteractions(alertaService);
    }

    @Test
    @DisplayName("Sin alertas, la tarjeta de stock bajo queda vacía y en cero")
    void sinAlertasLaTarjetaVieneVacia() {
        when(alertaService.listar(false)).thenReturn(listado(List.of()));

        var stock = service.obtener(7).stockBajo();

        assertThat(stock.cantidad()).isZero();
        assertThat(stock.agotadas()).isZero();
        assertThat(stock.items()).isEmpty();
    }

    @Test
    @DisplayName("Arma el DTO con las demás tarjetas del repositorio y el límite de 10 items")
    void armaElDashboardConLasTarjetasDelRepositorio() {
        when(alertaService.listar(false)).thenReturn(listado(List.of()));

        var dashboard = service.obtener(7);

        assertThat(dashboard.pedidosHoy()).isSameAs(repository.pedidosDelDia());
        assertThat(dashboard.comprasPendientes()).isSameAs(repository.comprasPendientes());
        assertThat(dashboard.produccionHoy()).isSameAs(repository.produccionDelDia());
        assertThat(dashboard.porVencer()).isSameAs(repository.proximosAVencer(7, 10));
    }

    @Test
    @DisplayName("CA-05: diasVencimiento se pasa al repositorio como horizonte")
    void diasVencimientoSePasaAlRepositorio() {
        when(alertaService.listar(false)).thenReturn(listado(List.of()));
        when(repository.proximosAVencer(30, 10)).thenReturn(new PorVencerResponse(2, 1, List.of()));

        assertThat(service.obtener(30).porVencer().cantidad()).isEqualTo(2);
    }

    @ParameterizedTest(name = "diasVencimiento={0} es válido")
    @ValueSource(ints = {0, 7, 365})
    @DisplayName("diasVencimiento entre 0 y 365 se acepta")
    void diasEnRangoSeAceptan(int dias) {
        when(alertaService.listar(false)).thenReturn(listado(List.of()));
        when(repository.proximosAVencer(dias, 10)).thenReturn(new PorVencerResponse(0, 0, List.of()));

        assertThat(service.obtener(dias)).isNotNull();
    }

    @ParameterizedTest(name = "diasVencimiento={0} se rechaza")
    @ValueSource(ints = {-1, 366, Integer.MAX_VALUE, Integer.MIN_VALUE})
    @DisplayName("diasVencimiento fuera de 0..365 se rechaza con PARAMETRO_INVALIDO y no consulta")
    void diasFueraDeRangoSeRechazan(int dias) {
        assertThatThrownBy(() -> service.obtener(dias))
                .isInstanceOf(ParametroInvalidoException.class)
                .hasMessageContaining("diasVencimiento")
                .extracting("codigo").isEqualTo("PARAMETRO_INVALIDO");
        verifyNoMoreInteractions(alertaService);
    }

    private static ListadoAlertasResponse listado(List<AlertaStockResponse> items) {
        long agotadas = items.stream().filter(i -> "AGOTADO".equals(i.nivel())).count();
        return new ListadoAlertasResponse(AHORA,
                new ResumenAlertasResponse(items.size(), agotadas, items.size() - agotadas),
                items, BigDecimal.ZERO);
    }
}
