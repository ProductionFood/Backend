package co.edu.corposucre.productionfood.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.SugerenciaCompraResponse;

class AlertaServiceTest {

    private AlertaRepository repository;
    private AlertaService servicio;

    @BeforeEach
    void setUp() {
        repository = mock(AlertaRepository.class);
        servicio = new AlertaService(repository);
    }

    @Test
    @DisplayName("costoReposicion es faltante por costo unitario con escala 2 y el total los suma")
    void calculaCostoDeReposicionYTotal() {
        when(repository.listarAlertas(false)).thenReturn(List.of(
                fila(7, "Levadura", "0.00", "5.00", "5.00", "AGOTADO", "180.00"),
                fila(12, "Mantequilla", "4.50", "20.00", "15.50", "BAJO", "18500.00"),
                fila(5, "Harina de trigo", "46.30", "50.00", "3.70", "BAJO", "3200.00")));

        var r = servicio.listar(false);

        assertThat(r.items()).extracting(i -> i.costoReposicion())
                .containsExactly(new BigDecimal("900.00"), new BigDecimal("286750.00"),
                        new BigDecimal("11840.00"));
        assertThat(r.costoReposicionTotal()).isEqualTo(new BigDecimal("299490.00"));
    }

    @Test
    @DisplayName("alertaDeMateriaPrima calcula el costo de reposición como el listado")
    void alertaDeUnaMateriaPrimaCalculaElCosto() {
        when(repository.buscarAlertaPorMateriaPrima(12)).thenReturn(Optional.of(
                fila(12, "Mantequilla", "4.50", "20.00", "15.50", "BAJO", "18500.00")));

        var r = servicio.alertaDeMateriaPrima(12);

        assertThat(r).hasValueSatisfying(a -> {
            assertThat(a.idMateriaPrima()).isEqualTo(12);
            assertThat(a.nivel()).isEqualTo("BAJO");
            assertThat(a.costoReposicion()).isEqualTo(new BigDecimal("286750.00"));
        });
    }

    @Test
    @DisplayName("alertaDeMateriaPrima es vacío si el repositorio no encuentra alerta")
    void alertaDeUnaMateriaPrimaSinAlertaEsVacia() {
        when(repository.buscarAlertaPorMateriaPrima(5)).thenReturn(Optional.empty());

        assertThat(servicio.alertaDeMateriaPrima(5)).isEmpty();
    }

    @Test
    @DisplayName("Conserva el orden del repositorio y copia los campos de la fila")
    void copiaLosCamposYConservaElOrden() {
        var ultima = LocalDateTime.of(2026, 9, 18, 9, 12, 44);
        when(repository.listarAlertas(false)).thenReturn(List.of(
                new AlertaStockFila(7, "Levadura", "kg", new BigDecimal("0.00"),
                        new BigDecimal("5.00"), new BigDecimal("5.00"), "AGOTADO",
                        new BigDecimal("180.00"), ultima),
                fila(12, "Mantequilla", "4.50", "20.00", "15.50", "BAJO", "18500.00")));

        var r = servicio.listar(false);

        assertThat(r.items()).extracting(i -> i.idMateriaPrima()).containsExactly(7, 12);
        var primero = r.items().get(0);
        assertThat(primero.nombre()).isEqualTo("Levadura");
        assertThat(primero.unidad()).isEqualTo("kg");
        assertThat(primero.nivel()).isEqualTo("AGOTADO");
        assertThat(primero.stockMinimo()).isEqualByComparingTo("5.00");
        assertThat(primero.ultimaActualizacion()).isEqualTo(ultima);
    }

    @Test
    @DisplayName("El resumen del listado se calcula de los items devueltos")
    void resumenCoincideConLosItems() {
        when(repository.listarAlertas(true)).thenReturn(List.of(
                fila(1, "A", "0.00", "5.00", "5.00", "AGOTADO", "10.00"),
                fila(2, "B", "1.00", "5.00", "4.00", "BAJO", "10.00"),
                fila(3, "C", "2.00", "5.00", "3.00", "BAJO", "10.00")));

        var r = servicio.listar(true);

        assertThat(r.resumen()).isEqualTo(new ResumenAlertasResponse(3, 1, 2));
        verify(repository).listarAlertas(true);
    }

    @Test
    @DisplayName("Sin alertas devuelve listado vacío, resumen en cero y total 0.00")
    void sinAlertasDevuelveCeros() {
        when(repository.listarAlertas(false)).thenReturn(List.of());
        var antes = LocalDateTime.now();

        var r = servicio.listar(false);

        assertThat(r.items()).isEmpty();
        assertThat(r.resumen()).isEqualTo(new ResumenAlertasResponse(0, 0, 0));
        assertThat(r.costoReposicionTotal()).isEqualTo(new BigDecimal("0.00"));
        assertThat(r.generadoEn()).isBetween(antes, LocalDateTime.now());
    }

    @Test
    @DisplayName("resumen delega en el repositorio")
    void resumenDelegaEnElRepositorio() {
        var esperado = new ResumenAlertasResponse(3, 1, 2);
        when(repository.resumen()).thenReturn(esperado);

        assertThat(servicio.resumen()).isEqualTo(esperado);
    }

    @Test
    @DisplayName("R-06: cantidadSugerida es faltante x 1.2 y costoEstimado usa la cantidad sugerida")
    void sugerenciaDelEjemploDelDocumento() {
        when(repository.sugerenciaCompra()).thenReturn(List.of(
                sug(12, "Mantequilla", "15.50", "BAJO", "18500.00", 5, "Distribuidora Lácteos"),
                sug(5, "Harina de trigo", "3.70", "BAJO", "3200.00", 3, "Harinas del Caribe")));

        var r = servicio.sugerenciaCompra();

        assertThat(r).hasSize(2);
        var mantequilla = r.get(0).items().get(0);
        assertThat(mantequilla.cantidadSugerida()).isEqualTo(new BigDecimal("18.60"));
        assertThat(mantequilla.costoEstimado()).isEqualTo(new BigDecimal("344100.00"));
        var harina = r.get(1).items().get(0);
        assertThat(harina.materiaPrima()).isEqualTo("Harina de trigo");
        assertThat(harina.faltante()).isEqualByComparingTo("3.70");
        assertThat(harina.cantidadSugerida()).isEqualTo(new BigDecimal("4.44"));
        assertThat(harina.costoEstimado()).isEqualTo(new BigDecimal("14208.00"));
        assertThat(r.get(1).totalEstimado()).isEqualTo(new BigDecimal("14208.00"));
        assertThat(r.get(1).proveedor().idProveedor()).isEqualTo(3);
        assertThat(r.get(1).proveedor().nombre()).isEqualTo("Harinas del Caribe");
    }

    @Test
    @DisplayName("El redondeo de la cantidad sugerida es siempre hacia arriba a 2 decimales")
    void redondeaHaciaArribaADosDecimales() {
        when(repository.sugerenciaCompra()).thenReturn(List.of(
                sug(1, "A", "0.01", "BAJO", "1.00", 1, "P"),     // 0.012 -> 0.02
                sug(2, "B", "0.83", "BAJO", "1.00", 1, "P"),     // 0.996 -> 1.00
                sug(3, "C", "1.00", "BAJO", "1.00", 1, "P"),     // 1.2 -> 1.20 exacto
                sug(4, "D", "0.001", "BAJO", "1.00", 1, "P")));  // 0.0012 -> 0.01

        var cantidades = servicio.sugerenciaCompra().get(0).items().stream()
                .map(i -> i.cantidadSugerida()).toList();

        assertThat(cantidades).containsExactly(new BigDecimal("0.02"), new BigDecimal("1.00"),
                new BigDecimal("1.20"), new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("Agrupa por proveedor conservando el orden de urgencia y suma el total de cada grupo")
    void agrupaPorProveedorConOrdenYTotales() {
        when(repository.sugerenciaCompra()).thenReturn(List.of(
                sug(7, "Levadura", "5.00", "AGOTADO", "180.00", 3, "Harinas"),
                sug(12, "Mantequilla", "15.50", "BAJO", "18500.00", 5, "Lácteos"),
                sug(5, "Harina", "3.70", "BAJO", "3200.00", 3, "Harinas")));

        var r = servicio.sugerenciaCompra();

        assertThat(r).extracting(g -> g.proveedor().idProveedor()).containsExactly(3, 5);
        assertThat(r.get(0).items()).extracting(i -> i.materiaPrima())
                .containsExactly("Levadura", "Harina");
        assertThat(r.get(0).totalEstimado()).isEqualTo(new BigDecimal("15288.00"));
        assertThat(r.get(1).totalEstimado()).isEqualTo(new BigDecimal("344100.00"));
    }

    @Test
    @DisplayName("Las materias primas sin compras previas van en un grupo con proveedor null")
    void sinProveedorVaEnUnGrupoAparte() {
        when(repository.sugerenciaCompra()).thenReturn(List.of(
                sug(1, "Nueva 1", "10.00", "AGOTADO", "100.00", null, null),
                sug(5, "Harina", "3.70", "BAJO", "3200.00", 3, "Harinas"),
                sug(2, "Nueva 2", "2.00", "BAJO", "50.00", null, null)));

        var r = servicio.sugerenciaCompra();

        assertThat(r).hasSize(2);
        assertThat(r.get(0).proveedor()).isNull();
        assertThat(r.get(0).items()).extracting(i -> i.materiaPrima())
                .containsExactly("Nueva 1", "Nueva 2");
        assertThat(r.get(0).totalEstimado()).isEqualTo(new BigDecimal("1320.00"));
        assertThat(r.get(1).proveedor().idProveedor()).isEqualTo(3);
    }

    @Test
    @DisplayName("Sin alertas la sugerencia es una lista vacía")
    void sinAlertasNoHayGrupos() {
        when(repository.sugerenciaCompra()).thenReturn(List.of());

        List<SugerenciaCompraResponse> r = servicio.sugerenciaCompra();

        assertThat(r).isEmpty();
    }

    private static SugerenciaFila sug(int id, String nombre, String faltante, String nivel,
                                      String costo, Integer idProveedor, String proveedor) {
        return new SugerenciaFila(fila(id, nombre, "0.00", faltante, faltante, nivel, costo),
                idProveedor, proveedor);
    }

    private static AlertaStockFila fila(int id, String nombre, String disponible, String minimo,
                                        String faltante, String nivel, String costo) {
        return new AlertaStockFila(id, nombre, "kg", new BigDecimal(disponible),
                new BigDecimal(minimo), new BigDecimal(faltante), nivel, new BigDecimal(costo),
                LocalDateTime.of(2026, 9, 18, 10, 0));
    }
}
