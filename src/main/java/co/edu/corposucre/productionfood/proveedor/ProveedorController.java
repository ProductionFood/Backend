package co.edu.corposucre.productionfood.proveedor;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.proveedor.dto.ActualizarProveedorRequest;
import co.edu.corposucre.productionfood.proveedor.dto.CrearProveedorRequest;
import co.edu.corposucre.productionfood.proveedor.dto.ProveedorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints de gestión de proveedores (HU-06).
 *
 * <p>No existe un DELETE y no debe añadirse: CA-05 lo prohíbe porque compras.id_proveedor
 * referencia al proveedor y borrarlo rompería el historial de compras de HU-15. La baja se hace
 * con el PATCH de estado, que desactiva al proveedor sin cancelar las compras ya registradas.
 */
@RestController
@RequestMapping("/api/v1/proveedores")
@Tag(name = "Proveedores", description = "Gestión de proveedores (HU-06)")
public class ProveedorController {

    private final ProveedorService proveedorService;

    public ProveedorController(ProveedorService proveedorService) {
        this.proveedorService = proveedorService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS','CONSULTA')")
    @Operation(summary = "Listar proveedores",
               description = "Listado paginado de proveedores. El filtro por nombre busca por "
                        + "prefijo sin distinguir mayúsculas y el filtro por estado separa los "
                        + "proveedores activos de los inactivos; ambos son opcionales y se pueden "
                        + "combinar. Se puede ordenar por nombre o por id, por defecto nombre "
                        + "ascendente. La validación de page, size y sort la hace el servicio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden inválidos "
                + "(PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<ProveedorResponse> listar(
            @RequestParam(required = false) String nombre,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return proveedorService.listar(nombre, activo, page, size, sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS','CONSULTA')")
    @Operation(summary = "Obtener un proveedor por id",
               description = "Devuelve el proveedor con su nombre, contacto, teléfono, correo y "
                        + "estado. 404 si el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Proveedor encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Proveedor inexistente")
    })
    public ProveedorResponse obtener(@PathVariable Integer id) {
        return proveedorService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Registrar un proveedor",
               description = "Crea un proveedor activo con nombre obligatorio y contacto, teléfono "
                        + "y correo opcionales. El correo es opcional, una cadena vacía se guarda "
                        + "como null y no es único: varios proveedores pueden compartirlo. "
                        + "Devuelve 201 con la cabecera Location apuntando al proveedor creado.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Proveedor creado"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o validación fallida"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public ResponseEntity<ProveedorResponse> crear(@Valid @RequestBody CrearProveedorRequest req) {
        var creado = proveedorService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/proveedores/" + creado.idProveedor()))
                .body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','COMPRAS')")
    @Operation(summary = "Editar un proveedor",
               description = "Reemplaza todos los datos del proveedor: nombre, contacto, teléfono "
                        + "y correo se sobrescriben siempre, de modo que un valor nulo borra el "
                        + "anterior en lugar de conservarlo. El correo es opcional, una cadena "
                        + "vacía se guarda como null y no es único. El estado no se modifica por "
                        + "este endpoint: solo cambia con el PATCH de estado.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Proveedor actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación fallida"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Proveedor inexistente")
    })
    public ProveedorResponse actualizar(@PathVariable Integer id,
                                        @Valid @RequestBody ActualizarProveedorRequest req) {
        return proveedorService.actualizar(id, req);
    }
}
