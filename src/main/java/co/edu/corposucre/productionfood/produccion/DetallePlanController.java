package co.edu.corposucre.productionfood.produccion;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.produccion.dto.ActualizarPlanificadaRequest;
import co.edu.corposucre.productionfood.produccion.dto.AgregarDetallePlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.DetallePlanResponse;
import co.edu.corposucre.productionfood.produccion.dto.RegistrarProducidaRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Productos (líneas de detalle) de un plan de producción (HU-21).
 *
 * <p>Subrecurso anidado bajo {@code /planes-produccion/{idPlan}/detalles}: expone los productos
 * comprometidos en el plan con su avance y su consumo teórico estimado, y sus cinco operaciones.
 * El controlador solo despacha a {@link DetallePlanService}: que el plan exista, que esté en el
 * estado que cada operación exige, que el producto exista y no se repita, que las cantidades sean
 * válidas y que la línea pertenezca al plan son decisiones del servicio (R-02 a R-08), no de aquí.
 *
 * <p>La consulta no exige estado: un plan {@code COMPLETADO} o {@code CANCELADO} se puede listar.
 * Las escrituras sí dependen del estado: el alta, la edición de lo planificado y el borrado exigen
 * {@code PLANIFICADO}, y el registro de lo producido exige {@code EN_PROCESO}; en otro estado
 * responden 409 ({@code PLAN_NO_EDITABLE} o {@code PLAN_NO_EN_PROCESO}).
 *
 * <p><strong>Ninguna operación mueve inventario (R-07):</strong> este controlador no llama a
 * ningún repositorio de inventario ni de kardex. El consumo real de materias primas y la entrada
 * del lote terminado los registran HU-22 y HU-23.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, PRODUCCION y CONSULTA, y
 * escritura a ADMIN y PRODUCCION. COMPRAS y VENTAS no participan y quedan fuera de los cinco
 * endpoints.
 */
@RestController
@RequestMapping("/api/v1/planes-produccion/{idPlan}/detalles")
@Tag(name = "Detalle de plan de producción", description = "Productos del plan, avance y consumo "
        + "teórico (HU-21)")
public class DetallePlanController {

    private final DetallePlanService detallePlanService;

    public DetallePlanController(DetallePlanService detallePlanService) {
        this.detallePlanService = detallePlanService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar los productos de un plan de producción",
               description = "Devuelve las líneas del plan de forma paginada, cada una con su "
                        + "producto embebido, la cantidad planificada, la producida, el avance "
                        + "en porcentaje y el consumo teórico estimado por materia prima (vacío "
                        + "si el producto no tiene receta). No exige un estado concreto: un plan "
                        + "COMPLETADO o CANCELADO se puede listar. Se puede ordenar por producto, "
                        + "cantidadPlanificada o avance, con dirección asc o desc (por defecto "
                        + "producto,asc); un campo fuera de esa lista blanca responde 400 "
                        + "CAMPO_ORDEN_INVALIDO. La validación de page y size la hace el servicio "
                        + "(1 ≤ size ≤ 100) y un valor fuera de rango responde 400 "
                        + "PARAMETRO_INVALIDO. Responde 404 PLAN_INEXISTENTE si el plan no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado de líneas del plan"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden "
                + "inválidos (PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente "
                + "(PLAN_INEXISTENTE)")
    })
    public PageResponse<DetallePlanResponse> listar(
            @PathVariable Integer idPlan,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return detallePlanService.listar(idPlan, page, size, sort);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Agregar un producto al plan de producción",
               description = "Registra una línea con su producto y su cantidad planificada "
                        + "mientras el plan esté PLANIFICADO; en cualquier otro estado responde "
                        + "409 PLAN_NO_EDITABLE (R-05). El producto debe existir y estar activo "
                        + "(409 PRODUCTO_INEXISTENTE) y no puede repetirse en el plan (409 "
                        + "PRODUCTO_DUPLICADO, R-05). La cantidad debe ser mayor que cero (400 "
                        + "CANTIDAD_INVALIDA). Un cuerpo sin idProducto o sin cantidadPlanificada "
                        + "responde 400 VALIDACION_FALLIDA. El alta no toca inventario ni lotes "
                        + "(R-07) y la línea nace con cantidad producida cero. Devuelve 201 con "
                        + "la cabecera Location apuntando a la línea creada.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Línea agregada, con su avance en cero "
                + "y su consumo teórico estimado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad no "
                + "positiva (VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente "
                + "(PLAN_INEXISTENTE)"),
        @ApiResponse(responseCode = "409", description = "Plan no editable, producto "
                + "inexistente o inactivo, o producto duplicado (PLAN_NO_EDITABLE, "
                + "PRODUCTO_INEXISTENTE, PRODUCTO_DUPLICADO)")
    })
    public ResponseEntity<DetallePlanResponse> agregar(
            @PathVariable Integer idPlan,
            @Valid @RequestBody AgregarDetallePlanRequest req) {
        var detalle = detallePlanService.agregar(idPlan, req);
        return ResponseEntity
                .created(URI.create("/api/v1/planes-produccion/" + idPlan + "/detalles/"
                        + detalle.idDetallePlan()))
                .body(detalle);
    }

    @PutMapping("/{idDetalle}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Editar la cantidad planificada de un producto del plan",
               description = "Ajusta la cantidad planificada de una línea mientras el plan siga "
                        + "PLANIFICADO; en cualquier otro estado responde 409 PLAN_NO_EDITABLE "
                        + "(R-02). La cantidad nueva debe ser mayor que cero (400 "
                        + "CANTIDAD_INVALIDA); un cuerpo sin cantidadPlanificada responde 400 "
                        + "VALIDACION_FALLIDA. El producto de la línea no se reemplaza. Devuelve "
                        + "200 con la línea actualizada y su consumo teórico recalculado. "
                        + "Responde 404 PLAN_INEXISTENTE si el plan no existe, o "
                        + "RECURSO_NO_ENCONTRADO si la línea no existe o pertenece a otro plan.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Línea actualizada, con su avance y "
                + "consumo teórico"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad no "
                + "positiva (VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente (PLAN_INEXISTENTE), "
                + "o línea inexistente o de otro plan (RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Plan no editable (PLAN_NO_EDITABLE)")
    })
    public DetallePlanResponse actualizar(
            @PathVariable Integer idPlan,
            @PathVariable Integer idDetalle,
            @Valid @RequestBody ActualizarPlanificadaRequest req) {
        return detallePlanService.actualizarPlanificada(idPlan, idDetalle, req);
    }

    @PatchMapping("/{idDetalle}/producido")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Registrar la cantidad producida de un producto del plan",
               description = "Registra el avance real de una línea mientras el plan esté "
                        + "EN_PROCESO (R-03); en cualquier otro estado responde 409 "
                        + "PLAN_NO_EN_PROCESO. La cantidad producida no puede ser negativa (400 "
                        + "CANTIDAD_INVALIDA); el cero es válido y no hay tope superior, porque "
                        + "producir de más es un dato real (R-04) y el avance puede superar el "
                        + "100 %. Un cuerpo sin cantidadProducida responde 400 "
                        + "VALIDACION_FALLIDA. No toca inventario, lotes ni stock (R-07). "
                        + "Devuelve 200 con la línea y su avance recalculado. Responde 404 "
                        + "PLAN_INEXISTENTE si el plan no existe, o RECURSO_NO_ENCONTRADO si la "
                        + "línea no existe o pertenece a otro plan.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Producción registrada, con el avance "
                + "recalculado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o cantidad negativa "
                + "(VALIDACION_FALLIDA, CANTIDAD_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente (PLAN_INEXISTENTE), "
                + "o línea inexistente o de otro plan (RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Plan no en proceso "
                + "(PLAN_NO_EN_PROCESO)")
    })
    public DetallePlanResponse registrarProducida(
            @PathVariable Integer idPlan,
            @PathVariable Integer idDetalle,
            @Valid @RequestBody RegistrarProducidaRequest req) {
        return detallePlanService.registrarProducida(idPlan, idDetalle, req);
    }

    @DeleteMapping("/{idDetalle}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Quitar un producto del plan de producción",
               description = "Elimina una línea mientras el plan siga PLANIFICADO (R-08); en "
                        + "cualquier otro estado responde 409 PLAN_NO_EDITABLE. La línea no "
                        + "puede tener consumos de materia prima registrados: si los tiene, "
                        + "responde 409 DETALLE_CON_CONSUMOS y no se borra. La línea debe "
                        + "pertenecer al plan de la ruta; si no, se responde 404 "
                        + "RECURSO_NO_ENCONTRADO, igual que si el plan no existe. Devuelve 204 "
                        + "sin cuerpo.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Línea eliminada"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente (PLAN_INEXISTENTE), "
                + "o línea inexistente o de otro plan (RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Plan no editable o línea con consumos "
                + "(PLAN_NO_EDITABLE, DETALLE_CON_CONSUMOS)")
    })
    public ResponseEntity<Void> eliminar(
            @PathVariable Integer idPlan,
            @PathVariable Integer idDetalle) {
        detallePlanService.eliminar(idPlan, idDetalle);
        return ResponseEntity.noContent().build();
    }
}
