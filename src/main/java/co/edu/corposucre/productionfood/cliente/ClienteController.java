package co.edu.corposucre.productionfood.cliente;

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

import co.edu.corposucre.productionfood.cliente.dto.ActualizarClienteRequest;
import co.edu.corposucre.productionfood.cliente.dto.ClienteResponse;
import co.edu.corposucre.productionfood.cliente.dto.CrearClienteRequest;
import co.edu.corposucre.productionfood.common.dto.CambiarEstadoRequest;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints de gestión de clientes (HU-05).
 *
 * <p>No existe un DELETE y no debe añadirse: CA-05 lo prohíbe porque los pedidos históricos
 * referencian al cliente y borrarlo rompería ese historial. La baja se hace con el PATCH de
 * estado, que desactiva al cliente sin cancelar los pedidos ya creados.
 */
@RestController
@RequestMapping("/api/v1/clientes")
@Tag(name = "Clientes", description = "Gestión de clientes (HU-05)")
public class ClienteController {

    private final ClienteService clienteService;

    public ClienteController(ClienteService clienteService) {
        this.clienteService = clienteService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar clientes",
               description = "Listado paginado de clientes. El filtro por nombre busca por prefijo "
                        + "sin distinguir mayúsculas y el filtro por estado separa los clientes "
                        + "activos de los inactivos; ambos son opcionales y se pueden combinar. "
                        + "Se puede ordenar por nombre o por id, por defecto nombre ascendente. "
                        + "La validación de page, size y sort la hace el servicio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden inválidos "
                + "(PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<ClienteResponse> listar(
            @RequestParam(required = false) String nombre,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return clienteService.listar(nombre, activo, page, size, sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS','PRODUCCION','CONSULTA')")
    @Operation(summary = "Obtener un cliente por id",
               description = "Devuelve el cliente con su nombre, contacto, teléfono y estado. "
                        + "404 si el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cliente encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Cliente inexistente")
    })
    public ClienteResponse obtener(@PathVariable Integer id) {
        return clienteService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Registrar un cliente",
               description = "Crea un cliente activo con nombre obligatorio y contacto y teléfono "
                        + "opcionales. Devuelve 201 con la cabecera Location apuntando al cliente "
                        + "creado.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Cliente creado"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o validación fallida"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public ResponseEntity<ClienteResponse> crear(@Valid @RequestBody CrearClienteRequest req) {
        var creado = clienteService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/clientes/" + creado.idCliente()))
                .body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Editar un cliente",
               description = "Reemplaza todos los datos del cliente: nombre, contacto y teléfono "
                        + "se sobrescriben siempre, de modo que un contacto o un teléfono nulo "
                        + "borra el valor anterior en lugar de conservarlo. El estado no se "
                        + "modifica por este endpoint: solo cambia con el PATCH de estado.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Cliente actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación fallida"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Cliente inexistente")
    })
    public ClienteResponse actualizar(@PathVariable Integer id,
                                      @Valid @RequestBody ActualizarClienteRequest req) {
        return clienteService.actualizar(id, req);
    }

    @PatchMapping("/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMIN','VENTAS')")
    @Operation(summary = "Activar o desactivar un cliente",
               description = "Cambio de estado del cliente. Desactivar no cancela los pedidos ya "
                        + "creados: solo impide ventas nuevas. Si el cliente tiene pedidos en "
                        + "curso la desactivación se rechaza con 409 mientras la regla esté "
                        + "habilitada en configuración. El cambio es idempotente.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación fallida"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Cliente inexistente"),
        @ApiResponse(responseCode = "409", description = "Conflicto de negocio: el cliente tiene "
                + "pedidos en curso (CLIENTE_CON_PEDIDOS_ACTIVOS)")
    })
    public ClienteResponse cambiarEstado(@PathVariable Integer id,
                                         @Valid @RequestBody CambiarEstadoRequest req) {
        return clienteService.cambiarEstado(id, req);
    }
}
