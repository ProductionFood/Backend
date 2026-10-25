package co.edu.corposucre.productionfood.inventario;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.inventario.dto.InventarioResponse;
import co.edu.corposucre.productionfood.inventario.dto.MovimientoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints de consulta del inventario de materia prima (HU-10).
 *
 * <p>La historia es de solo lectura (R-01): aquí no hay {@code POST}, {@code PUT}, {@code PATCH}
 * ni {@code DELETE}, y no deben añadirse. El inventario se mueve desde HU-09 (lotes), HU-13
 * (recepción de compras) y HU-23 (consumo de producción), siempre a través de
 * {@link InventarioService}; un endpoint de escritura en este módulo abriría una ruta que dejaría
 * el saldo sin su movimiento de kardex.
 *
 * <p>Todas las consultas se resuelven contra la vista {@code v_inventario_materia_prima} (R-02), de
 * modo que el listado, el detalle y el kardex muestran exactamente los mismos valores calculados:
 * {@code cantidad_disponible} con {@code COALESCE} a cero, {@code estado_stock} ({@code AGOTADO},
 * {@code BAJO}, {@code OK}; R-05) y {@code faltante}.
 *
 * <p>Las materias primas inactivas se incluyen por defecto (R-04): desactivarlas no retira su stock
 * de la bodega, así que ocultarlas haría desaparecer existencias reales del sistema. El cliente
 * puede excluirlas con {@code incluirInactivas=false}.
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, PRODUCCION, COMPRAS y
 * CONSULTA; el kardex queda fuera de CONSULTA porque expone el detalle operativo de quién movió el
 * stock y con qué documento.
 */
@RestController
@RequestMapping("/api/v1/inventario")
@Tag(name = "Inventario", description = "Consulta del inventario de materia prima (HU-10, solo "
        + "lectura)")
public class InventarioController {

    private final InventarioService inventarioService;

    public InventarioController(InventarioService inventarioService) {
        this.inventarioService = inventarioService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','CONSULTA')")
    @Operation(summary = "Listar el inventario de materia prima",
               description = "Listado paginado del inventario resuelto contra la vista "
                        + "v_inventario_materia_prima (R-02). Cada fila trae la cantidad "
                        + "disponible, el stock mínimo, el faltante, la fecha de actualización y "
                        + "el estadoStock (AGOTADO, BAJO u OK; R-05). Los filtros son opcionales "
                        + "y se pueden combinar: idMateriaPrima acota a una materia prima y "
                        + "soloStockBajo deja únicamente las que están BAJO o AGOTADO. Las "
                        + "materias primas inactivas se muestran por defecto porque conservan "
                        + "stock (R-04); incluirInactivas=false las excluye. Se puede ordenar por "
                        + "nombre, cantidadDisponible, faltante o fechaActualizacion, por defecto "
                        + "nombre ascendente. La validación de page, size y sort la hace el "
                        + "servicio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden "
                + "inválidos (PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<InventarioResponse> listar(
            @RequestParam(required = false) Integer idMateriaPrima,
            @RequestParam(required = false) Boolean soloStockBajo,
            @RequestParam(defaultValue = "true") boolean incluirInactivas,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return inventarioService.listar(idMateriaPrima, soloStockBajo, incluirInactivas,
                page, size, sort);
    }

    @GetMapping("/{idMateriaPrima}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','CONSULTA')")
    @Operation(summary = "Obtener el inventario de una materia prima",
               description = "Devuelve el nombre, la unidad, la cantidad disponible, el stock "
                        + "mínimo, el faltante, el estadoStock (AGOTADO, BAJO u OK; R-05), si la "
                        + "materia prima está activa y la fecha de actualización. Se resuelve "
                        + "contra la misma vista que el listado (R-02), así que ambos muestran "
                        + "los mismos valores calculados. Una materia prima inactiva también se "
                        + "devuelve: conserva su stock (R-04). Responde 404 si el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Inventario encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Materia prima inexistente")
    })
    public InventarioResponse obtener(@PathVariable Integer idMateriaPrima) {
        return inventarioService.obtener(idMateriaPrima);
    }

    @GetMapping("/{idMateriaPrima}/movimientos")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS')")
    @Operation(summary = "Consultar el kardex de una materia prima",
               description = "Historial paginado de movimientos de la materia prima (R-03): qué "
                        + "entró o salió, cuándo, quién y por qué documento, con los saldos "
                        + "anterior y posterior. Explica cómo se llegó a la cantidad disponible "
                        + "actual. Se ordena siempre del movimiento más reciente al más antiguo y "
                        + "no acepta el parámetro sort, para seguir aprovechando el índice del "
                        + "kardex. fechaInicio y fechaFin son opcionales, en formato YYYY-MM-DD y "
                        + "ambos inclusivos; si fechaInicio es posterior a fechaFin se responde "
                        + "400. Un movimiento sin usuario asociado se muestra como 'Sistema'. Una "
                        + "materia prima existente sin movimientos devuelve una página vacía; si "
                        + "la materia prima no existe, 404.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Kardex paginado"),
        @ApiResponse(responseCode = "400", description = "Rango de fechas invertido "
                + "(RANGO_FECHAS_INVALIDO) o parámetros de paginación inválidos "
                + "(PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Materia prima inexistente")
    })
    public PageResponse<MovimientoResponse> movimientos(
            @PathVariable Integer idMateriaPrima,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return inventarioService.movimientos(idMateriaPrima, fechaInicio, fechaFin, page, size);
    }
}
