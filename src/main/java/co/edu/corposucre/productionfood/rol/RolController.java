package co.edu.corposucre.productionfood.rol;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.usuario.dto.RolResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Roles", description = "Gestión de roles del sistema")
public class RolController {

    private final RolService rolService;

    public RolController(RolService rolService) {
        this.rolService = rolService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Listar roles disponibles")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Lista de roles"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public List<RolResponse> listar() {
        return rolService.listar();
    }
}
