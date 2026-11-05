package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.inventario.dto.AlertaStockResponse;
import co.edu.corposucre.productionfood.inventario.dto.ListadoAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;

/**
 * Alertas de stock bajo (HU-27). Solo lee; no hay caché porque el conteo alimenta un indicador
 * que debe reflejar el inventario real.
 */
@Service
@Transactional(readOnly = true)
public class AlertaService {

    private static final String AGOTADO = "AGOTADO";
    private static final int ESCALA = 2;

    private final AlertaRepository repository;

    public AlertaService(AlertaRepository repository) {
        this.repository = repository;
    }

    /**
     * Arma el listado de alertas con el costo de reposición de cada una y el total.
     *
     * <p>El resumen se cuenta sobre los mismos items devueltos, de modo que nunca contradice la
     * lista aunque se pidan las inactivas.
     */
    public ListadoAlertasResponse listar(boolean incluirInactivas) {
        var items = repository.listarAlertas(incluirInactivas).stream()
                .map(AlertaService::aRespuesta)
                .toList();

        long agotadas = items.stream().filter(i -> AGOTADO.equals(i.nivel())).count();
        var total = items.stream()
                .map(AlertaStockResponse::costoReposicion)
                .reduce(BigDecimal.ZERO.setScale(ESCALA), BigDecimal::add);

        return new ListadoAlertasResponse(
                LocalDateTime.now(),
                new ResumenAlertasResponse(items.size(), agotadas, items.size() - agotadas),
                items,
                total);
    }

    /** Conteo ligero de alertas de materias primas activas, para el indicador global. */
    public ResumenAlertasResponse resumen() {
        return repository.resumen();
    }

    private static AlertaStockResponse aRespuesta(AlertaStockFila fila) {
        var costoReposicion = fila.faltante().multiply(fila.costoUnitario())
                .setScale(ESCALA, RoundingMode.HALF_UP);
        return new AlertaStockResponse(
                fila.idMateriaPrima(), fila.nombre(), fila.unidad(), fila.cantidadDisponible(),
                fila.stockMinimo(), fila.faltante(), fila.nivel(), fila.costoUnitario(),
                costoReposicion, fila.ultimaActualizacion());
    }
}
