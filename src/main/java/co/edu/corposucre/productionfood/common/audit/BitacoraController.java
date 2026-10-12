package co.edu.corposucre.productionfood.common.audit;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.corposucre.productionfood.common.audit.dto.BitacoraResponse;
import co.edu.corposucre.productionfood.common.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/bitacora")
@Tag(name = "Bitácora", description = "Consulta de la bitácora de auditoría (HU-04, solo lectura)")
public class BitacoraController {

    private final BitacoraService bitacoraService;

    public BitacoraController(BitacoraService bitacoraService) {
        this.bitacoraService = bitacoraService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Listar la bitácora",
               description = "Listado paginado de auditoría con filtros opcionales y "
                       + "orden por fecha, id, accion o tabla. "
                       + "Ejemplo: /api/v1/bitacora?accion=CREAR&fechaInicio=2026-09-01"
                       + "&fechaFin=2026-09-30&page=0&size=20&sort=fecha,desc. "
                       + "El rango de fechas es inclusivo en ambos extremos.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Rango de fechas invertido o "
                + "parámetros de paginación u orden inválidos"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<BitacoraResponse> listar(
            @RequestParam(required = false) Integer idUsuario,
            @RequestParam(required = false) String tabla,
            @RequestParam(required = false) String accion,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return bitacoraService.listar(
                new BitacoraFiltros(idUsuario, tabla, accion, fechaInicio, fechaFin),
                page, size, sort);
    }

    @GetMapping("/acciones")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Listar las acciones registradas",
               description = "Catálogo de acciones distintas presentes en la bitácora, ordenado "
                       + "alfabéticamente, para poblar el filtro por acción. "
                       + "Ejemplo de respuesta: [\"ACTUALIZAR\",\"CREAR\",\"LOGIN_FALLIDO\"].")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Catálogo de acciones"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public List<String> listarAcciones() {
        return bitacoraService.listarAcciones();
    }
}
