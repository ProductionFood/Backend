package co.edu.corposucre.productionfood.produccion;

import java.net.URI;
import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import co.edu.corposucre.productionfood.produccion.dto.ActualizarPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CambiarEstadoPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.CrearPlanRequest;
import co.edu.corposucre.productionfood.produccion.dto.PlanResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints del ciclo de vida de un plan de producción (HU-20).
 *
 * <p>Esta colección expone el alta de planes, su consulta paginada con filtros, el detalle por
 * id, la edición de cabecera mientras siguen {@code PLANIFICADO} y el cambio de estado según la
 * máquina de {@link EstadoPlan}. El efecto no se decide aquí: lo garantiza
 * {@link PlanProduccionService} —validación de fechas, resolución del responsable, edición solo
 * en {@code PLANIFICADO}, transiciones y exigencia de líneas al iniciar— y el controlador solo
 * lo expone.
 *
 * <p><strong>Ninguna transición de estado mueve inventario (R-06):</strong> este controlador no
 * llama a ningún repositorio de inventario ni de kardex. El consumo de materias primas y la
 * entrada de producto terminado los registran HU-22 y HU-23; cambiar el estado de un plan solo
 * actualiza su columna {@code estado}.
 *
 * <p>No existe un DELETE y no debe añadirse: un plan referencia a su responsable y sus líneas
 * de detalle son el compromiso de producción que HU-21 gestiona. El cierre correcto de un plan
 * es cambiar su estado a {@code CANCELADO} o {@code COMPLETADO}, no borrarlo.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura (listado y detalle) a ADMIN,
 * PRODUCCION y CONSULTA, y escritura (alta, edición de cabecera y transiciones de estado) a
 * ADMIN y PRODUCCION. COMPRAS y VENTAS no participan en la planificación de la producción y
 * quedan fuera de los cinco endpoints.
 */
@RestController
@RequestMapping("/api/v1/planes-produccion")
@Tag(name = "Planes de producción", description = "Registro, consulta y ciclo de estado de los "
        + "planes de producción (HU-20)")
public class PlanProduccionController {

    private final PlanProduccionService planProduccionService;

    public PlanProduccionController(PlanProduccionService planProduccionService) {
        this.planProduccionService = planProduccionService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar planes de producción",
               description = "Listado paginado de planes con su responsable embebido, su estado "
                        + "y el número de líneas de detalle. Los cuatro filtros son opcionales y "
                        + "se pueden combinar: estado deja solo los planes en ese estado; "
                        + "idUsuario acota al responsable indicado; fechaInicio y fechaFin "
                        + "delimitan un rango inclusivo sobre la fecha de producción. Se puede "
                        + "ordenar por fechaProduccion, fechaPlanificacion, estado o id, con "
                        + "dirección asc o desc (por defecto fechaProduccion,desc); un campo "
                        + "fuera de esa lista blanca responde 400 CAMPO_ORDEN_INVALIDO. La "
                        + "validación de page y size la hace el servicio (1 ≤ size ≤ 100) y un "
                        + "valor fuera de rango responde 400 PARAMETRO_INVALIDO.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden "
                + "inválidos, o estado no canónico (PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<PlanResponse> listar(
            @RequestParam(required = false) EstadoPlan estado,
            @RequestParam(required = false) Integer idUsuario,
            @RequestParam(required = false) LocalDate fechaInicio,
            @RequestParam(required = false) LocalDate fechaFin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return planProduccionService.listar(estado, idUsuario, fechaInicio, fechaFin, page, size,
                sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','CONSULTA')")
    @Operation(summary = "Obtener un plan de producción por id",
               description = "Devuelve el plan con su responsable, su estado y el número de "
                        + "líneas de detalle, más si todavía es editable. Una lectura no mueve "
                        + "inventario ni cambia estados. Responde 404 si el id no existe y 400 "
                        + "PARAMETRO_INVALIDO si no es numérico.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plan encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico "
                + "(PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente "
                + "(RECURSO_NO_ENCONTRADO)")
    })
    public PlanResponse obtener(@PathVariable Integer id) {
        return planProduccionService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Registrar un plan de producción",
               description = "Las dos fechas son obligatorias y la de producción no puede ser "
                        + "anterior a la de planificación (400 FECHAS_INVALIDAS); omitir "
                        + "cualquiera de ellas responde 400 VALIDACION_FALLIDA. El plan nace "
                        + "siempre en estado PLANIFICADO, sin líneas de detalle y sin que el "
                        + "cuerpo admita estado. El responsable es el usuario autenticado; solo "
                        + "un ADMIN puede informar idUsuario, y si ese usuario no existe responde "
                        + "409 USUARIO_INEXISTENTE. Devuelve 201 con la cabecera Location "
                        + "apuntando al plan creado.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Plan registrado en estado "
                + "PLANIFICADO"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o fechas "
                + "incoherentes (VALIDACION_FALLIDA, FECHAS_INVALIDAS)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "409", description = "El responsable designado no existe "
                + "(USUARIO_INEXISTENTE)")
    })
    public ResponseEntity<PlanResponse> crear(@Valid @RequestBody CrearPlanRequest req) {
        var creado = planProduccionService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/planes-produccion/" + creado.idPlan()))
                .body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Editar la cabecera de un plan de producción",
               description = "Cambia las fechas, las observaciones y el responsable de un plan. "
                        + "Solo un plan PLANIFICADO es editable: en cualquier otro estado "
                        + "responde 409 PLAN_NO_EDITABLE (R-05). La fecha de producción no puede "
                        + "ser anterior a la de planificación (400 FECHAS_INVALIDAS) y un cuerpo "
                        + "sin fechas responde 400 VALIDACION_FALLIDA. Sin idUsuario, o con un "
                        + "rol no administrador, se conserva el responsable actual; un ADMIN que "
                        + "designa un usuario inexistente recibe 409 USUARIO_INEXISTENTE. El "
                        + "estado no se modifica por aquí: tiene su propio endpoint. Responde 404 "
                        + "si el plan no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Plan actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o fechas "
                + "incoherentes (VALIDACION_FALLIDA, FECHAS_INVALIDAS)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Plan no editable o responsable "
                + "inexistente (PLAN_NO_EDITABLE, USUARIO_INEXISTENTE)")
    })
    public PlanResponse actualizar(@PathVariable Integer id,
                                   @Valid @RequestBody ActualizarPlanRequest req) {
        return planProduccionService.actualizar(id, req);
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Cambiar el estado de un plan de producción",
               description = "Transición de estado según la máquina R-01: desde PLANIFICADO se "
                        + "puede pasar a EN_PROCESO o CANCELADO, desde EN_PROCESO a COMPLETADO o "
                        + "CANCELADO, y un plan COMPLETADO o CANCELADO no admite ningún cambio "
                        + "más. Al iniciar (EN_PROCESO) el plan debe tener al menos una línea de "
                        + "detalle, o responde 409 PLAN_SIN_PRODUCTOS (R-02, CP-14). Un salto "
                        + "prohibido o una salida de un estado terminal responde 409 "
                        + "TRANSICION_INVALIDA con el estado actual y los permitidos (contrato "
                        + "§7); un literal no canónico responde 400 VALIDACION_FALLIDA. Ninguna "
                        + "transición mueve inventario (R-06). Devuelve 200 con el plan releído de "
                        + "la base en su estado nuevo y responde 404 si el plan no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido, estado obligatorio "
                + "o no canónico (VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Plan inexistente "
                + "(RECURSO_NO_ENCONTRADO)"),
        @ApiResponse(responseCode = "409", description = "Transición prohibida o plan sin "
                + "productos (TRANSICION_INVALIDA, PLAN_SIN_PRODUCTOS)")
    })
    public PlanResponse cambiarEstado(@PathVariable Integer id,
                                      @Valid @RequestBody CambiarEstadoPlanRequest req) {
        return planProduccionService.cambiarEstado(id, req);
    }
}
