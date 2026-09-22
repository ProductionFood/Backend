package co.edu.corposucre.productionfood.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import co.edu.corposucre.productionfood.auth.dto.LoginRequest;
import co.edu.corposucre.productionfood.auth.dto.LoginResponse;
import co.edu.corposucre.productionfood.common.error.ConflictoNegocioException;
import co.edu.corposucre.productionfood.security.JwtService;
import co.edu.corposucre.productionfood.security.UsuarioAutenticado;
import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UsuarioRepository usuarioRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.usuarioRepository = usuarioRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest req) {
        var usuario = usuarioRepository.findByCorreo(req.correo().trim().toLowerCase())
                .orElseThrow(() -> new ConflictoNegocioException(
                        "CREDENCIALES_INVALIDAS",
                        "Correo o contraseña incorrectos."));

        if (!passwordEncoder.matches(req.password(), usuario.getPassword())) {
            throw new ConflictoNegocioException(
                    "CREDENCIALES_INVALIDAS",
                    "Correo o contraseña incorrectos.");
        }

        var autenticado = new UsuarioAutenticado(
                usuario.getIdUsuario(),
                usuario.getCorreo(),
                usuario.getNombre(),
                usuario.getRol().getNombre());

        var token = jwtService.generar(autenticado);

        return new LoginResponse(
                token,
                "Bearer",
                3600,
                usuario.getCorreo(),
                usuario.getNombre(),
                usuario.getRol().getNombre());
    }
}
