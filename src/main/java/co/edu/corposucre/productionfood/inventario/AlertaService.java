package co.edu.corposucre.productionfood.inventario;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.corposucre.productionfood.inventario.dto.AlertaStockResponse;
import co.edu.corposucre.productionfood.inventario.dto.ListadoAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.SugerenciaCompraResponse;
import co.edu.corposucre.productionfood.inventario.dto.SugerenciaCompraResponse.ItemSugerido;
import co.edu.corposucre.productionfood.inventario.dto.SugerenciaCompraResponse.ProveedorSugerido;

/**
 * Alertas de stock bajo (HU-27). Solo lee; no hay caché porque el conteo alimenta un indicador
 * que debe reflejar el inventario real.
 */
@Service
@Transactional(readOnly = true)
public class AlertaService {

    private static final String AGOTADO = "AGOTADO";
    private static final int ESCALA = 2;
    private static final BigDecimal MARGEN_COMPRA = new BigDecimal("1.2");

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

    /**
     * Propuesta de reposición agrupada por proveedor (R-06). Es una sugerencia, no una compra.
     *
     * <p>Cada item pide el faltante más un 20 % de margen, redondeado hacia arriba a 2 decimales;
     * el costo estimado es esa cantidad por el costo unitario. Los grupos salen en el orden de
     * urgencia de su primer item y, dentro de cada uno, los items conservan el orden de las
     * alertas. Las materias primas sin compras previas van en un grupo con proveedor {@code null}.
     */
    public List<SugerenciaCompraResponse> sugerenciaCompra() {
        Map<Integer, List<SugerenciaFila>> grupos = new LinkedHashMap<>();
        for (SugerenciaFila fila : repository.sugerenciaCompra()) {
            grupos.computeIfAbsent(fila.idProveedor(), k -> new ArrayList<>()).add(fila);
        }
        return grupos.values().stream().map(AlertaService::aGrupo).toList();
    }

    private static SugerenciaCompraResponse aGrupo(List<SugerenciaFila> filas) {
        var primera = filas.get(0);
        var proveedor = primera.idProveedor() == null ? null
                : new ProveedorSugerido(primera.idProveedor(), primera.nombreProveedor());
        var items = filas.stream().map(AlertaService::aItem).toList();
        var total = items.stream()
                .map(ItemSugerido::costoEstimado)
                .reduce(BigDecimal.ZERO.setScale(ESCALA), BigDecimal::add);
        return new SugerenciaCompraResponse(proveedor, items, total);
    }

    private static ItemSugerido aItem(SugerenciaFila fila) {
        var alerta = fila.alerta();
        var cantidad = alerta.faltante().multiply(MARGEN_COMPRA).setScale(ESCALA, RoundingMode.CEILING);
        var costo = cantidad.multiply(alerta.costoUnitario()).setScale(ESCALA, RoundingMode.HALF_UP);
        return new ItemSugerido(alerta.nombre(), alerta.faltante(), cantidad, costo);
    }
}
