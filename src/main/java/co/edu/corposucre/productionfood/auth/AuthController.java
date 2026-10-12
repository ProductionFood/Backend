package co.edu.corposucre.productionfood.auth;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.auth.dto.LoginRequest;
import co.edu.corposucre.productionfood.auth.dto.LoginResponse;
import co.edu.corposucre.productionfood.auth.dto.PerfilResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Autenticación", description = "Login y gestión de sesiones")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesión",
               description = "Valida credenciales y devuelve un JWT.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Autenticación exitosa"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos"),
        @ApiResponse(responseCode = "401", description = "Credenciales incorrectas"),
        @ApiResponse(responseCode = "403", description = "Usuario desactivado")
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    @GetMapping("/perfil")
    @Operation(summary = "Consultar perfil",
               description = "Devuelve los datos del usuario autenticado. No requiere rol: "
                           + "cualquier usuario autenticado ve su propio perfil.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Perfil del usuario autenticado"),
        @ApiResponse(responseCode = "401", description = "Sin sesión válida")
    })
    public ResponseEntity<PerfilResponse> perfil() {
        return ResponseEntity.ok(authService.perfil());
    }
}
