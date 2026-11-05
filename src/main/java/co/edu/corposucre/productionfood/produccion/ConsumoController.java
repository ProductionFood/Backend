package co.edu.corposucre.productionfood.produccion;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.produccion.dto.ActualizarConsumoRequest;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoResponse;
import co.edu.corposucre.productionfood.produccion.dto.ConsumoTeoricoLineaResponse;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarConsumoRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Consumo de materia prima por línea de un plan de producción (HU-22).
 *
 * <p>Los endpoints cuelgan de dos recursos distintos —los consumos de un plan y el consumo
 * teórico, real o corregido de una línea—, por eso el controlador no lleva {@code @RequestMapping}
 * de clase y cada método declara su ruta completa. Solo despacha a {@link ConsumoService}: que la
 * línea y el plan existan, que el plan esté {@code EN_PROCESO}, que la materia prima exista, que
 * no se repita el consumo, que haya stock y que las cantidades sean válidas son decisiones del
 * servicio (R-01 a R-09), no de aquí.
 *
 * <p><strong>La cantidad teórica la calcula el servidor</strong> con la receta del producto
 * (R-01): el cuerpo de registro no la admite. Registrar descuenta inventario en la misma
 * transacción (R-05) y corregir ajusta por diferencia (R-07). Un consumo real de cero es válido y
 * no mueve inventario; en ese caso la eficiencia viaja {@code null} (R-03).
 *
 * <p>Las escrituras están auditadas en {@link ConsumoService} ({@code @Auditable}); las consultas
 * de solo lectura no generan bitácora y por eso no llevan esa anotación en el controlador.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura del listado a ADMIN, PRODUCCION y
 * CONSULTA; el consumo teórico, el registro y la corrección quedan para ADMIN y PRODUCCION.
 * COMPRAS y VENTAS no participan en el consumo de materia prima.
 */
@RestController
@Tag(name = "Consumos de materia prima", description = "Consulta del consumo teórico, registro "
        + "del consumo real y corrección por diferencia (HU-22)")
public class ConsumoController {

    private final ConsumoService consumoService;

    public ConsumoController(ConsumoService consumoService) {
        this.consumoService = consumoService;
    }

    @GetMapping("/api/v1/planes-produccion/{idPlan}/consumos")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar los consumos de materia prima de un plan de producción",
               description = "Devuelve de forma paginada los consumos registrados en las líneas "
                        + "del plan, cada uno con su materia prima, su producto, las cantidades "
                        + "teórica y real, la eficiencia, la desviación, fueraDeReceta y la fecha "
                        + "de registro. Es de solo lectura: no descuenta inventario y el campo "
                        + "inventario viaja null porque el listado no muestra movimientos. "
                        + "Parámetros: page desde 0 (por defecto 0), size entre 1 y 100 (por "
                        + "defecto 20) y sort 'campo,asc|desc' opcional. Los campos de orden "
                        + "válidos son producto, materiaPrima, cantidadTeorica, cantidadReal y "
                        + "fechaRegistro (por defecto producto,asc); un campo fuera de esa lista "
                        + "blanca responde 400 CAMPO_ORDEN_INVALIDO y una página o tamaño fuera "
                        + "de rango 400 PARAMETRO_INVALIDO. Responde 404 PLAN_INEXISTENTE si el "
                        + "plan no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Página de consumos del plan"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden "
                + "inválidos (PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente "
                + "(PLAN_INEXISTENTE)")
    })
    public PageResponse<ConsumoResponse> listar(
            @PathVariable Integer idPlan,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return consumoService.listar(idPlan, page, size, sort);
    }

    @GetMapping("/api/v1/detalles-plan/{idDetallePlan}/consumo-teorico")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Consultar el consumo teórico de una línea del plan",
               description = "Calcula en el servidor el consumo teórico de cada materia prima de "
                        + "la receta para la cantidad planificada de la línea (R-01): el cliente "
                        + "no envía ninguna cantidad. Devuelve el rendimiento de una tanda, el "
                        + "factor de tandas y los ingredientes con su cantidad por tanda, su "
                        + "teórico y lo disponible en inventario. Es de solo lectura: no registra "
                        + "consumo ni descuenta stock. Un producto sin receta devuelve la lista "
                        + "de ingredientes vacía y recetaRinde y factor en null (R-06). Responde "
                        + "404 DETALLE_PLAN_INEXISTENTE si la línea no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Consumo teórico de la línea, con sus "
                + "ingredientes y lo disponible"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Línea del plan inexistente "
                + "(DETALLE_PLAN_INEXISTENTE)")
    })
    public ConsumoTeoricoLineaResponse consultarTeorico(
            @PathVariable Integer idDetallePlan) {
        return consumoService.consumoTeorico(idDetallePlan);
    }

    @PostMapping("/api/v1/detalles-plan/{idDetallePlan}/consumos")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Registrar el consumo real de una materia prima en una línea",
               description = "Registra cuánto se usó realmente de una materia prima en la línea y "
                        + "descuenta esa cantidad del inventario en la misma transacción, con su "
                        + "movimiento de kardex (R-05). La cantidad teórica la calcula el servidor "
                        + "con la receta y no se recibe en el cuerpo (R-01). Una materia prima "
                        + "fuera de la receta se acepta con teórico cero y se marca fuera de "
                        + "receta (R-08). cantidadReal puede ser cero: es válido, no mueve "
                        + "inventario y la eficiencia viaja null (R-03). Un cuerpo sin "
                        + "idMateriaPrima o sin cantidadReal responde 400 VALIDACION_FALLIDA y "
                        + "una cantidad negativa 400 CANTIDAD_INVALIDA. Devuelve 201 con la "
                        + "cabecera Location apuntando al consumo creado.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Consumo registrado, con su eficiencia, "
                + "su desviación y el efecto en inventario"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad negativa "
                + "(VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Línea del plan inexistente "
                + "(DETALLE_PLAN_INEXISTENTE)"),
        @ApiResponse(responseCode = "409", description = "Plan no en proceso, materia prima "
                + "inexistente, consumo duplicado o stock insuficiente (PLAN_NO_EN_PROCESO, "
                + "MATERIA_PRIMA_INEXISTENTE, CONSUMO_DUPLICADO, STOCK_INSUFICIENTE)")
    })
    public ResponseEntity<ConsumoResponse> registrar(
            @PathVariable Integer idDetallePlan,
            @Valid @RequestBody RegistrarConsumoRequest req) {
        var creado = consumoService.registrar(idDetallePlan, req);
        return ResponseEntity
                .created(URI.create("/api/v1/consumos/" + creado.idConsumo()))
                .body(creado);
    }

    @PutMapping("/api/v1/consumos/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Corregir la cantidad real de un consumo registrado",
               description = "Ajusta la cantidad real de un consumo ya registrado y mueve el "
                        + "inventario solo por la diferencia: si sube, descuenta el excedente; si "
                        + "baja, devuelve la diferencia; no revierte todo y vuelve a descontar "
                        + "(R-07). La cantidad teórica guardada no se recalcula. cantidadReal "
                        + "puede ser cero: es válido, no genera movimiento y la eficiencia viaja "
                        + "null (R-03). Un cuerpo sin cantidadReal responde 400 "
                        + "VALIDACION_FALLIDA y una cantidad negativa 400 CANTIDAD_INVALIDA. "
                        + "Responde 404 RECURSO_NO_ENCONTRADO si el consumo no existe. Devuelve "
                        + "200 con el consumo corregido y el efecto del ajuste en inventario.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Consumo corregido, con su eficiencia, "
                + "su desviación y el ajuste de inventario"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad negativa "
                + "(VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Consumo inexistente "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Plan no en proceso o stock "
                + "insuficiente (PLAN_NO_EN_PROCESO, STOCK_INSUFICIENTE)")
    })
    public ConsumoResponse actualizar(@PathVariable Integer id,
                                      @Valid @RequestBody ActualizarConsumoRequest req) {
        return consumoService.actualizar(id, req);
    }
}
