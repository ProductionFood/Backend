package co.edu.corposucre.productionfood.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import co.edu.corposucre.productionfood.usuario.UsuarioRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UsuarioRepository usuarioRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UsuarioRepository usuarioRepository) {
        this.jwtService = jwtService;
        this.usuarioRepository = usuarioRepository;
    }

    /**
     * ¿La petición va a la ruta pública de login?
     *
     * Comparación por igualdad exacta a propósito (no {@code startsWith}): un
     * prefijo "/api/v1/auth/" dejaría públicas rutas protegidas como
     * /api/v1/auth/perfil. Ver {@link RutasPublicas#LOGIN}, que es la misma
     * constante que usa SecurityConfig para el permitAll.
     */
    private static boolean esLogin(HttpServletRequest req) {
        // requestURI incluye el contextPath; los matchers de Spring no, así que se
        // compara sobre la ruta relativa al contexto para que ambos coincidan.
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return RutasPublicas.LOGIN.equals(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        // El login es la única ruta pública y este filtro corre ANTES que las reglas
        // de autorización: el único modo de respetar `permitAll` es no cortarla nunca.
        // Cortarla impedía que un cliente con un token caducado, de un usuario dado de
        // baja o de un id que ya no existe en la base pudiera volver a iniciar sesión.
        // Se resuelve sin mirar la cabecera ni tocar la base (aquí no hay nada que
        // autenticar) y limpiando el contexto, para que ninguna identidad caducada
        // llegue al controlador.
        if (esLogin(req)) {
            SecurityContextHolder.clearContext();
            chain.doFilter(req, res);
            return;
        }

        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.validarYExtraer(header.substring(7));

                // SEC-02: el `sub` es el id numérico que emite JwtService.generar.
                // Si no lo es, el token está malformado: NumberFormatException NO es
                // JwtException, así que el catch de abajo no lo capturaría y subiría
                // al GlobalExceptionHandler como 500 ERROR_INTERNO. Un token que no
                // podemos ni identificar es, para el cliente, un token no válido.
                int idUsuario;
                try {
                    idUsuario = Integer.parseInt(claims.getSubject());
                } catch (NumberFormatException ex) {
                    escribirNoAutenticado(res, req);
                    return;
                }

                var usuario = usuarioRepository.findConRolById(idUsuario);

                // El id del token no está en la base: la identidad que porta el token
                // ya no existe (borrado o de otro entorno). No es una baja, es una
                // petición sin identidad recognizable, así que responde 401 y no 403.
                if (usuario.isEmpty()) {
                    escribirNoAutenticado(res, req);
                    return;
                }

                // R-05 / SEC-05: el estado se comprueba en CADA petición, porque el
                // token sigue siendo criptográficamente válido tras una baja.
                if (!Boolean.TRUE.equals(usuario.get().getEstado())) {
                    RespuestaSeguridad.escribir(res, req, HttpStatus.FORBIDDEN,
                            "USUARIO_INACTIVO", MensajesSeguridad.MSG_USUARIO_INACTIVO);
                    return;
                }

                var u = usuario.get();
                var rol = u.getRol().getNombre();
                var authorities = List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_" + rol));
                var auth = new UsernamePasswordAuthenticationToken(
                        new UsuarioAutenticado(
                                u.getIdUsuario(),
                                u.getCorreo(),
                                u.getNombre(),
                                rol),
                        null, authorities);
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException ex) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }

    private void escribirNoAutenticado(HttpServletResponse res, HttpServletRequest req)
            throws IOException {
        RespuestaSeguridad.escribir(res, req, HttpStatus.UNAUTHORIZED,
                "NO_AUTENTICADO", MensajesSeguridad.MSG_NO_AUTENTICADO);
    }
}
