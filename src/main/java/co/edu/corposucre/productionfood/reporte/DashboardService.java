package co.edu.corposucre.productionfood.reporte;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;
import co.edu.corposucre.productionfood.inventario.AlertaService;
import co.edu.corposucre.productionfood.reporte.dto.DashboardResponse;
import co.edu.corposucre.productionfood.reporte.dto.StockBajoResponse;

/**
 * Resumen del día (HU-24). Solo lee; sin caché (R-04): cada llamada consulta datos frescos.
 *
 * <p>R-01: {@code fecha} y {@code generadoEn} salen de la base de datos, la misma zona que usan
 * las consultas con {@code curdate()}; nunca de {@code LocalDate.now()} de Java.
 *
 * <p>El stock bajo (CA-03) no se recalcula: se arma con una única llamada a
 * {@link AlertaService#listar(boolean)} sin inactivas, y {@code cantidad}/{@code agotadas} se
 * toman de su resumen, calculado sobre esos mismos items.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    /** Horizonte de vencimiento por defecto (CA-05). */
    public static final int DIAS_VENCIMIENTO_POR_DEFECTO = 7;
    static final int DIAS_VENCIMIENTO_MAXIMO = 365;
    static final int MAX_ITEMS_POR_VENCER = 10;

    private final DashboardRepository repository;
    private final AlertaService alertaService;

    public DashboardService(DashboardRepository repository, AlertaService alertaService) {
        this.repository = repository;
        this.alertaService = alertaService;
    }

    /**
     * Arma el dashboard completo.
     *
     * @param diasVencimiento horizonte de «por vencer», entre 0 y 365 días
     * @throws ParametroInvalidoException {@code PARAMETRO_INVALIDO} si está fuera de rango
     */
    public DashboardResponse obtener(int diasVencimiento) {
        if (diasVencimiento < 0 || diasVencimiento > DIAS_VENCIMIENTO_MAXIMO) {
            throw new ParametroInvalidoException("PARAMETRO_INVALIDO",
                    "El parámetro 'diasVencimiento' debe estar entre 0 y "
                            + DIAS_VENCIMIENTO_MAXIMO + ".");
        }
        var ahora = repository.ahora();
        return new DashboardResponse(
                ahora.toLocalDate(),
                ahora,
                repository.pedidosDelDia(),
                repository.comprasPendientes(),
                stockBajo(),
                repository.produccionDelDia(),
                repository.proximosAVencer(diasVencimiento, MAX_ITEMS_POR_VENCER));
    }

    private StockBajoResponse stockBajo() {
        var alertas = alertaService.listar(false);
        var items = alertas.items().stream()
                .map(a -> new StockBajoResponse.Item(a.nombre(), a.cantidadDisponible(),
                        a.stockMinimo(), a.faltante(), a.nivel()))
                .toList();
        return new StockBajoResponse(alertas.resumen().total(), alertas.resumen().agotadas(),
                items);
    }
}
