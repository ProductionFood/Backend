package co.edu.corposucre.productionfood.materiaprima;

import java.net.URI;

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

import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.materiaprima.dto.ActualizarMateriaPrimaRequest;
import co.edu.corposucre.productionfood.materiaprima.dto.CrearMateriaPrimaRequest;
import co.edu.corposucre.productionfood.materiaprima.dto.MateriaPrimaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints de gestión de materias primas (HU-08).
 *
 * <p>No existe un DELETE y no debe añadirse: la regla R-05 lo prohíbe porque el historial de
 * movimientos del kardex (HU-10, HU-23, HU-27) y las recetas referencian a la materia prima;
 * borrarla rompería esa trazabilidad. La baja se hace con el PATCH de estado, que desactiva la
 * materia prima sin tocar su fila de inventario.
 *
 * <p>A diferencia de los módulos de clientes y unidades de medida, el rol VENTAS no participa
 * en este módulo: la matriz de roles (00-base/03-MATRIZ-ROLES.md) lo excluye de materias
 * primas, cuyo consumo lo cubren COMPRAS (compra) y PRODUCCION (producción). Las lecturas
 * admiten ADMIN, PRODUCCION, COMPRAS y CONSULTA; la escritura es exclusiva de ADMIN.
 */
@RestController
@RequestMapping("/api/v1/materias-primas")
@Tag(name = "Materias primas", description = "Gestión de materias primas (HU-08)")
public class MateriaPrimaController {

    private final MateriaPrimaService materiaPrimaService;

    public MateriaPrimaController(MateriaPrimaService materiaPrimaService) {
        this.materiaPrimaService = materiaPrimaService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','CONSULTA')")
    @Operation(summary = "Listar materias primas",
               description = "Listado paginado de materias primas con su unidad de medida y "
                        + "la cantidad disponible de inventario. El filtro por nombre busca "
                        + "por prefijo sin distinguir mayúsculas y el filtro por estado "
                        + "separa las activas de las inactivas; ambos son opcionales y se "
                        + "pueden combinar. Se puede ordenar por nombre, stockMinimo, "
                        + "costoUnitario o id, por defecto nombre ascendente. La validación "
                        + "de page, size y sort la hace el servicio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden inválidos "
                + "(PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<MateriaPrimaResponse> listar(
            @RequestParam(required = false) String nombre,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return materiaPrimaService.listar(nombre, activo, page, size, sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','CONSULTA')")
    @Operation(summary = "Obtener una materia prima por id",
               description = "Devuelve la materia prima con su unidad de medida, stock mínimo, "
                        + "costo unitario, estado y la cantidad disponible de inventario. "
                        + "404 si el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Materia prima encontrada"),
        @ApiResponse(responseCode = "400", description = "Id no numérico"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Materia prima inexistente")
    })
    public MateriaPrimaResponse obtener(@PathVariable Integer id) {
        return materiaPrimaService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Registrar una materia prima",
               description = "Crea una materia prima activa con nombre, unidad de medida, stock "
                        + "mínimo y costo unitario obligatorios. Según la regla R-01, el alta "
                        + "crea en la misma transacción la fila de inventario con cantidad 0, "
                        + "de modo que no existan materias primas sin stock registrado. La "
                        + "unidad de medida debe existir; si no, se responde 409 "
                        + "UNIDAD_INEXISTENTE. Devuelve 201 con la cabecera Location "
                        + "apuntando a la materia prima creada.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Materia prima creada"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o validación fallida "
                + "(VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "409", description = "La unidad de medida indicada no existe "
                + "(UNIDAD_INEXISTENTE)")
    })
    public ResponseEntity<MateriaPrimaResponse> crear(
            @Valid @RequestBody CrearMateriaPrimaRequest req) {
        var creado = materiaPrimaService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/materias-primas/" + creado.idMateriaPrima()))
                .body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Editar una materia prima",
               description = "Reemplaza todos los datos editables de la materia prima: nombre, "
                        + "unidad de medida, stock mínimo y costo unitario se sobrescriben "
                        + "siempre. El estado no se modifica por este endpoint: solo cambia "
                        + "con el PATCH de estado, y el stock vive en inventario y tampoco se "
                        + "toca aquí. La unidad de medida debe existir; si no, se responde 409 "
                        + "UNIDAD_INEXISTENTE.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Materia prima actualizada"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación fallida "
                + "(VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Materia prima inexistente"),
        @ApiResponse(responseCode = "409", description = "La unidad de medida indicada no existe "
                + "(UNIDAD_INEXISTENTE)")
    })
    public MateriaPrimaResponse actualizar(@PathVariable Integer id,
                                           @Valid @RequestBody ActualizarMateriaPrimaRequest req) {
        return materiaPrimaService.actualizar(id, req);
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Activar o desactivar una materia prima",
               description = "Cambio de estado de la materia prima: no existe DELETE (regla "
                        + "R-05), la baja es la desactivación. Desactivar no borra la fila de "
                        + "inventario ni el historial de movimientos (R-04), solo impide usarla "
                        + "en operaciones nuevas. Si la materia prima sigue en recetas activas "
                        + "y la regla está habilitada en configuración, la desactivación se "
                        + "rechaza con 409 MATERIA_PRIMA_EN_USO. El cambio es idempotente.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación fallida"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Materia prima inexistente"),
        @ApiResponse(responseCode = "409", description = "Conflicto de negocio: la materia prima "
                + "está en recetas activas (MATERIA_PRIMA_EN_USO)")
    })
    public MateriaPrimaResponse cambiarEstado(@PathVariable Integer id,
                                              @Valid @RequestBody CambiarEstadoRequest req) {
        return materiaPrimaService.cambiarEstado(id, req);
    }
}
