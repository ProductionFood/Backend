package co.edu.corposucre.productionfood.inventario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;

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

    private static AlertaStockFila fila(int id, String nombre, String disponible, String minimo,
                                        String faltante, String nivel, String costo) {
        return new AlertaStockFila(id, nombre, "kg", new BigDecimal(disponible),
                new BigDecimal(minimo), new BigDecimal(faltante), nivel, new BigDecimal(costo),
                LocalDateTime.of(2026, 9, 18, 10, 0));
    }
}
