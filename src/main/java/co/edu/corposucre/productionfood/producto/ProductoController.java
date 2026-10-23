package co.edu.corposucre.productionfood.producto;

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
import co.edu.corposucre.productionfood.producto.dto.ActualizarProductoRequest;
import co.edu.corposucre.productionfood.producto.dto.CrearProductoRequest;
import co.edu.corposucre.productionfood.producto.dto.ProductoResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints del catálogo de productos terminados (HU-11).
 *
 * <p>No existe un DELETE y no debe añadirse: la regla R-05 lo prohíbe porque {@code detalle_pedido},
 * {@code lotes}, {@code recetas} y {@code detalle_plan_produccion} referencian al producto; borrarlo
 * rompería la trazabilidad. La baja se hace con el PATCH de estado, que desactiva el producto sin
 * retirar su stock (R-03).
 *
 * <p>La matriz de roles (00-base/03-MATRIZ-ROLES.md) da lectura a ADMIN, VENTAS, PRODUCCION y
 * CONSULTA —el catálogo lo consultan ventas y producción— y deja la escritura exclusiva de ADMIN.
 */
@RestController
@RequestMapping("/api/v1/productos")
@Tag(name = "Productos", description = "Gestión del catálogo de productos terminados (HU-11)")
public class ProductoController {

    private final ProductoService productoService;

    public ProductoController(ProductoService productoService) {
        this.productoService = productoService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar productos",
               description = "Listado paginado del catálogo con la unidad de venta, si el producto "
                        + "tiene receta y su stock agregado de producto terminado. El filtro por "
                        + "nombre busca por prefijo sin distinguir mayúsculas y el filtro por "
                        + "estado separa los activos de los inactivos; ambos son opcionales y se "
                        + "pueden combinar. Se puede ordenar por nombre, precio o id, por defecto "
                        + "nombre ascendente. La validación de page, size y sort la hace el "
                        + "servicio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden inválidos "
                + "(PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<ProductoResponse> listar(
            @RequestParam(required = false) String nombre,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return productoService.listar(nombre, activo, page, size, sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Obtener un producto por id",
               description = "Devuelve el producto con su nombre, descripción, precio, unidad de "
                        + "venta, estado, si tiene receta y su stock disponible. 404 si el id no "
                        + "existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Producto encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Producto inexistente")
    })
    public ProductoResponse obtener(@PathVariable Integer id) {
        return productoService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Registrar un producto",
               description = "Crea un producto activo con nombre, precio y unidad de venta "
                        + "obligatorios y descripción opcional. El precio admite cero y se "
                        + "redondea a dos decimales. La unidad de venta debe existir; si no, se "
                        + "responde 409 UNIDAD_INEXISTENTE. A diferencia de las materias primas, "
                        + "el alta NO crea fila de inventario: el stock de producto terminado vive "
                        + "por lote (HU-19). Devuelve 201 con la cabecera Location.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Producto creado"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o validación fallida "
                + "(VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "409", description = "La unidad de medida indicada no existe "
                + "(UNIDAD_INEXISTENTE)")
    })
    public ResponseEntity<ProductoResponse> crear(@Valid @RequestBody CrearProductoRequest req) {
        var creado = productoService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/productos/" + creado.idProducto()))
                .body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Editar un producto",
               description = "Reemplaza todos los datos editables del producto: nombre, "
                        + "descripción, precio y unidad de venta se sobrescriben siempre. Cambiar "
                        + "el precio solo afecta a ventas futuras: las líneas de pedido ya "
                        + "registradas conservan su precio (R-01). El estado no se modifica por "
                        + "este endpoint. La unidad de venta debe existir; si no, se responde 409 "
                        + "UNIDAD_INEXISTENTE.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Producto actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación fallida "
                + "(VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Producto inexistente"),
        @ApiResponse(responseCode = "409", description = "La unidad de medida indicada no existe "
                + "(UNIDAD_INEXISTENTE)")
    })
    public ProductoResponse actualizar(@PathVariable Integer id,
                                       @Valid @RequestBody ActualizarProductoRequest req) {
        return productoService.actualizar(id, req);
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Activar o desactivar un producto",
               description = "Cambio de estado del producto: no existe DELETE (R-05), la baja es "
                        + "la desactivación. Desactivar no retira el stock (R-03), solo impide "
                        + "venderlo o planificar producción nueva; si el producto todavía tiene "
                        + "existencias, la respuesta 200 incluye el campo 'advertencia' con la "
                        + "cantidad conservada, sin bloquear la operación. El cambio es "
                        + "idempotente.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación fallida"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Producto inexistente")
    })
    public ProductoResponse cambiarEstado(@PathVariable Integer id,
                                          @Valid @RequestBody CambiarEstadoRequest req) {
        return productoService.cambiarEstado(id, req);
    }
}
