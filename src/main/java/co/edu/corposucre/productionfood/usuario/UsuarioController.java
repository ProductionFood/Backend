package co.edu.corposucre.productionfood.usuario;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import co.edu.corposucre.productionfood.usuario.dto.CrearUsuarioRequest;
import co.edu.corposucre.productionfood.usuario.dto.UsuarioResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/usuarios")
@Tag(name = "Usuarios", description = "Gestión de usuarios del sistema (HU-01, HU-02)")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Listar usuarios",
               description = "Listado paginado con búsqueda por prefijo de nombre o correo, "
                       + "filtro por rol y por estado, y orden por nombre, correo o id.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden inválidos"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<UsuarioResponse> listar(
            @RequestParam(required = false) String busqueda,
            @RequestParam(required = false) Integer idRol,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return usuarioService.listar(busqueda, idRol, activo, page, size, sort);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Obtener un usuario por id",
               description = "Devuelve el usuario con su rol activo. 404 si el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Usuario encontrado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Usuario inexistente")
    })
    public UsuarioResponse obtener(@PathVariable Integer id) {
        return usuarioService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Registrar un usuario",
               description = "Crea un usuario activo con nombre, correo, contraseña y rol.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Usuario creado"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "409", description = "Correo duplicado o rol inexistente")
    })
    public ResponseEntity<UsuarioResponse> crear(@Valid @RequestBody CrearUsuarioRequest req) {
        var creado = usuarioService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/usuarios/" + creado.idUsuario()))
                .body(creado);
    }
}
