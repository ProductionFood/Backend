package co.edu.corposucre.productionfood.unidadmedida;

import java.net.URI;
import java.util.List;

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

import co.edu.corposucre.productionfood.unidadmedida.dto.ActualizarUnidadRequest;
import co.edu.corposucre.productionfood.unidadmedida.dto.CrearUnidadRequest;
import co.edu.corposucre.productionfood.unidadmedida.dto.UnidadMedidaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints del catálogo de unidades de medida (HU-07).
 *
 * <p>No existe un DELETE y no debe añadirse: la regla R-03 lo prohíbe porque la tabla
 * {@code unidades_medida} no tiene columna de estado y las claves foráneas que la referencian no
 * admiten el borrado en cascada. Una unidad de medida no se desactiva ni se elimina: solo se crea
 * y se edita, y la edición se limita a nombre y abreviatura.
 *
 * <p>El catálogo se lee con los cinco roles autenticados (ADMIN, PRODUCCION, COMPRAS, VENTAS y
 * CONSULTA) porque los roles operativos necesitan el catálogo completo para poblar los selectores
 * de unidades de sus formularios, aunque no puedan modificarlo. Solo ADMIN escribe: a diferencia de
 * los módulos de clientes y proveedores, el de unidades no delega la escritura en los roles
 * operativos.
 */
@RestController
@RequestMapping("/api/v1/unidades-medida")
@Tag(name = "Unidades de medida", description = "Gestión de unidades de medida (HU-07)")
public class UnidadMedidaController {

    private final UnidadMedidaService unidadMedidaService;

    public UnidadMedidaController(UnidadMedidaService unidadMedidaService) {
        this.unidadMedidaService = unidadMedidaService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','VENTAS','CONSULTA')")
    @Operation(summary = "Listar unidades de medida",
               description = "Devuelve el catálogo completo de unidades de medida, sin paginación "
                        + "ni filtros: el catálogo es pequeño y el cliente lo usa para poblar "
                        + "los selectores de unidades de sus formularios, donde una página "
                        + "incompleta obligaría a pedir más de una vez lo mismo. Las unidades "
                        + "vienen ordenadas por nombre y cada una incluye el conteo de usos (usos) "
                        + "en materias primas, productos y recetas, que indica si el catálogo se "
                        + "puede modificar sin romper las referencias existentes.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Catálogo completo de unidades de medida"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public List<UnidadMedidaResponse> listar() {
        return unidadMedidaService.listar();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','COMPRAS','VENTAS','CONSULTA')")
    @Operation(summary = "Obtener una unidad de medida por id",
               description = "Devuelve la unidad de medida con su nombre, abreviatura y el conteo "
                        + "de usos (usos) en materias primas, productos y recetas. 404 si el id no "
                        + "existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Unidad de medida encontrada"),
        @ApiResponse(responseCode = "400", description = "Id no numérico"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Unidad de medida inexistente")
    })
    public UnidadMedidaResponse obtener(@PathVariable Integer id) {
        return unidadMedidaService.obtener(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Registrar una unidad de medida",
               description = "Crea una unidad de medida con nombre y abreviatura obligatorios. La "
                        + "abreviatura no puede repetirse y la comparación no distingue "
                        + "mayúsculas de minúsculas: 'kg' y 'KG' son la misma abreviatura. "
                        + "Devuelve 201 con la cabecera Location apuntando a la unidad creada.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Unidad de medida creada"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o validación fallida "
                + "(VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "409", description = "Abreviatura ya usada por otra unidad "
                + "(ABREVIATURA_DUPLICADA)")
    })
    public ResponseEntity<UnidadMedidaResponse> crear(@Valid @RequestBody CrearUnidadRequest req) {
        var creado = unidadMedidaService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/unidades-medida/" + creado.idUnidad()))
                .body(creado);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Editar una unidad de medida",
               description = "Reemplaza todos los datos de la unidad: nombre y abreviatura se "
                        + "sobrescriben siempre. La abreviatura no puede repetirse y la "
                        + "comparación no distingue mayúsculas de minúsculas. Si la unidad ya "
                        + "está en uso en materias primas, productos o recetas y se cambia su "
                        + "abreviatura, la petición se rechaza con 409 UNIDAD_EN_USO, porque las "
                        + "cantidades ya registradas se interpretan con la abreviatura vigente; "
                        + "para aplicar el cambio hay que repetir la misma petición con el "
                        + "parámetro de consulta ?confirmar=true.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Unidad de medida actualizada"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o validación fallida "
                + "(VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Unidad de medida inexistente"),
        @ApiResponse(responseCode = "409", description = "Conflicto de negocio: abreviatura "
                + "repetida (ABREVIATURA_DUPLICADA) o abreviatura cambiada en una unidad en uso "
                + "(UNIDAD_EN_USO)")
    })
    public UnidadMedidaResponse actualizar(@PathVariable Integer id,
                                           @Valid @RequestBody ActualizarUnidadRequest req,
                                           @RequestParam(defaultValue = "false")
                                           boolean confirmar) {
        return unidadMedidaService.actualizar(id, req, confirmar);
    }
}
