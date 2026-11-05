package co.edu.corposucre.productionfood.inventario;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.inventario.dto.ListadoAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.ResumenAlertasResponse;
import co.edu.corposucre.productionfood.inventario.dto.SugerenciaCompraResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints de alerta de stock bajo de materia prima (HU-27).
 *
 * <p>La historia es de solo lectura y sin notificaciones automáticas (R-07): aquí solo hay
 * {@code GET}. No hay caché: el indicador debe reflejar el stock actual.
 *
 * <p>Matriz de roles: el listado lo leen ADMIN, PRODUCCION, COMPRAS y CONSULTA; el resumen, al
 * alimentar el indicador global de la barra superior, cualquier rol autenticado; la sugerencia de
 * compra solo ADMIN y COMPRAS.
 *
 * <p>Un {@code incluirInactivas} que no sea booleano ({@code true}/{@code false}) lo rechaza la
 * conversión de parámetros de Spring y el manejador global lo traduce a 400
 * {@code PARAMETRO_INVALIDO}, igual que el resto de controladores del proyecto con parámetros de
 * tipo inválido. Se usa ese código y no {@code RANGO_INVALIDO}, que ningún otro módulo emite.
 */
@RestController
@RequestMapping("/api/v1/inventario/alertas")
@Tag(name = "Alertas de stock", description = "Alertas de stock bajo de materia prima (HU-27, "
        + "solo lectura)")
public class AlertaController {

    private final AlertaService alertaService;

    public AlertaController(AlertaService alertaService) {
        this.alertaService = alertaService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','CONSULTA')")
    @Operation(summary = "Listar las materias primas bajo el mínimo",
               description = "Materias primas con stock por debajo del mínimo (R-02), ordenadas "
                        + "por urgencia: primero AGOTADO y luego BAJO por faltante descendente "
                        + "(R-03). Las de stock mínimo 0 no alertan (R-04). Las inactivas se "
                        + "excluyen salvo incluirInactivas=true y, si tienen stock, siguen "
                        + "alertando (R-05). Incluye el resumen y el costo de reposición total.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado de alertas",
                content = @Content(examples = @ExampleObject(value = """
                        {"generadoEn":"2026-09-18T10:00:00",
                         "resumen":{"total":1,"agotadas":0,"bajas":1},
                         "items":[{"idMateriaPrima":5,"nombre":"Harina de trigo","unidad":"kg",
                          "cantidadDisponible":12.50,"stockMinimo":50.00,"faltante":37.50,
                          "nivel":"BAJO","costoUnitario":2.40,"costoReposicion":90.00,
                          "ultimaActualizacion":"2026-09-18T09:12:44"}],
                         "costoReposicionTotal":90.00}"""))),
        @ApiResponse(responseCode = "400", description = "incluirInactivas no es booleano "
                + "(PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public ListadoAlertasResponse listar(
            @RequestParam(defaultValue = "false") boolean incluirInactivas) {
        return alertaService.listar(incluirInactivas);
    }

    @GetMapping("/resumen")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Contar las materias primas en alerta",
               description = "Conteo para el indicador global de la barra superior: total, "
                        + "agotadas y bajas, con el mismo criterio que el listado por defecto "
                        + "(sin inactivas). Disponible para cualquier rol autenticado.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Conteo de alertas",
                content = @Content(examples = @ExampleObject(
                        value = "{\"total\":3,\"agotadas\":1,\"bajas\":2}"))),
        @ApiResponse(responseCode = "401", description = "Sin token")
    })
    public ResumenAlertasResponse resumen() {
        return alertaService.resumen();
    }

    @GetMapping("/sugerencia-compra")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Proponer la reposición agrupada por proveedor",
               description = "Para cada proveedor (el de la última compra recibida, R-06) las "
                        + "materias primas en alerta con el faltante, la cantidad sugerida "
                        + "(faltante más 20 %) y el costo estimado. Las que nunca se han "
                        + "comprado van en un grupo con proveedor null. Es una propuesta: no "
                        + "crea ninguna compra.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Grupos de la sugerencia",
                content = @Content(examples = @ExampleObject(value = """
                        [{"proveedor":{"idProveedor":2,"nombre":"Molinos S.A."},
                          "items":[{"materiaPrima":"Harina de trigo","faltante":37.50,
                           "cantidadSugerida":45.00,"costoEstimado":108.00}],
                          "totalEstimado":108.00}]"""))),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public List<SugerenciaCompraResponse> sugerenciaCompra() {
        return alertaService.sugerenciaCompra();
    }
}
