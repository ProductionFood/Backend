package co.edu.corposucre.productionfood.receta;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import co.edu.corposucre.productionfood.receta.dto.ActualizarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.ActualizarRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.AgregarDetalleRequest;
import co.edu.corposucre.productionfood.receta.dto.CrearRecetaRequest;
import co.edu.corposucre.productionfood.receta.dto.RecetaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Endpoints de las recetas y de sus ingredientes (HU-12).
 *
 * <p>La historia expone dos familias de rutas bajo {@code /api/v1}: el recurso {@code recetas} con
 * su subrecurso de ingredientes ({@code /recetas/{id}/detalles}) y la lectura de la receta de un
 * producto ({@code /productos/{idProducto}/receta}), que es la forma natural de consultarla desde
 * el catálogo de HU-11. El prefijo común de clase evita repetir {@code /api/v1} en las nueve
 * rutas.
 *
 * <p>{@code cantidadProducir} es el rendimiento de <strong>una tanda completa</strong>, no de una
 * unidad (R-02): es el divisor con el que HU-22 calcula el consumo teórico. Interpretarlo como
 * "por unidad" no produce ningún error visible, solo inventarios y eficiencias absurdas tres
 * historias después; por eso se repite en el Swagger del alta y de la edición.
 *
 * <p>Una receta sin ingredientes se puede crear (R-05) y queda marcada con {@code completa=false};
 * HU-22 la rechazará al planificar. La cabecera nunca se elimina —para eso está el PATCH de
 * estado— y el único DELETE de la historia es el de una línea de detalle (R-07), porque un
 * ingrediente quitado no tiene valor histórico y {@code detalle_receta} no está referenciada por
 * ninguna otra tabla. Esa decisión vive en {@link RecetaService}, no aquí: el controlador se limita
 * a exponerla.
 *
 * <p>La matriz de roles da lectura a ADMIN, PRODUCCION y CONSULTA —VENTAS y COMPRAS no participan en
 * el módulo— y escritura a ADMIN y PRODUCCION.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Recetas", description = "Gestión de recetas y sus ingredientes (HU-12)")
public class RecetaController {

    private final RecetaService recetaService;

    public RecetaController(RecetaService recetaService) {
        this.recetaService = recetaService;
    }

    @GetMapping("/recetas")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','CONSULTA')")
    @Operation(summary = "Listar recetas",
               description = "Listado paginado de recetas con su producto, su unidad de "
                        + "producción, el detalle completo y los costos estimados. Los filtros "
                        + "son opcionales y se pueden combinar: nombre busca por prefijo sin "
                        + "distinguir mayúsculas y activo separa las recetas vigentes de las "
                        + "desactivadas. Se puede ordenar por nombre, cantidadProducir o id, por "
                        + "defecto nombre ascendente. La validación de page, size y sort la hace "
                        + "el servicio.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Listado paginado"),
        @ApiResponse(responseCode = "400", description = "Parámetros de paginación u orden "
                + "inválidos (PARAMETRO_INVALIDO, CAMPO_ORDEN_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos")
    })
    public PageResponse<RecetaResponse> listar(
            @RequestParam(required = false) String nombre,
            @RequestParam(required = false) Boolean activo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return recetaService.listar(nombre, activo, page, size, sort);
    }

    @GetMapping("/recetas/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','CONSULTA')")
    @Operation(summary = "Obtener una receta por id",
               description = "Devuelve la receta con su producto, el rendimiento de una tanda, la "
                        + "unidad de producción, el detalle con la cantidad y el costo estimado de "
                        + "cada ingrediente, el costo total y el costo por unidad. Responde 404 si "
                        + "el id no existe.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Receta encontrada"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Receta inexistente")
    })
    public RecetaResponse obtener(@PathVariable Integer id) {
        return recetaService.obtener(id);
    }

    @GetMapping("/productos/{idProducto}/receta")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION','CONSULTA')")
    @Operation(summary = "Obtener la receta de un producto",
               description = "Devuelve la receta asociada al producto. Según CA-01 cada producto "
                        + "tiene como máximo una receta, así que la ruta no necesita id de receta. "
                        + "Responde 404 si el producto no tiene receta: un producto sin receta no "
                        + "es un error de la petición, simplemente no se puede planificar.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Receta del producto"),
        @ApiResponse(responseCode = "400", description = "Id de producto no numérico "
                + "(PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "El producto no tiene receta")
    })
    public RecetaResponse porProducto(@PathVariable Integer idProducto) {
        return recetaService.porProducto(idProducto);
    }

    @PostMapping("/recetas")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Crear una receta",
               description = "Crea la cabecera y su detalle inicial en una sola transacción: si "
                        + "un ingrediente falla, no queda una receta a medias. cantidadProducir es "
                        + "el rendimiento de UNA TANDA COMPLETA, no de una unidad (R-02), y debe "
                        + "ser mayor que cero porque es el divisor del consumo teórico. El detalle "
                        + "puede venir vacío: una receta sin ingredientes se permite crear y queda "
                        + "marcada con completa=false (R-05). El producto debe existir y estar "
                        + "activo, no puede tener ya otra receta (CA-01) y no se admite la misma "
                        + "materia prima dos veces, ni repetida en la lista ni en la base (R-01). "
                        + "Devuelve 201 con la cabecera Location.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Receta creada"),
        @ApiResponse(responseCode = "400", description = "Datos inválidos o rendimiento no "
                + "positivo (VALIDACION_FALLIDA, CANTIDAD_PRODUCIR_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "La receta no pudo releerse tras el alta"),
        @ApiResponse(responseCode = "409", description = "Producto o materia prima inexistente, "
                + "producto con receta o ingrediente duplicado (PRODUCTO_INEXISTENTE, "
                + "MATERIA_PRIMA_INEXISTENTE, PRODUCTO_YA_TIENE_RECETA, MATERIA_PRIMA_DUPLICADA, "
                + "UNIDAD_INEXISTENTE)")
    })
    public ResponseEntity<RecetaResponse> crear(@Valid @RequestBody CrearRecetaRequest req) {
        var creada = recetaService.crear(req);
        return ResponseEntity
                .created(URI.create("/api/v1/recetas/" + creada.idReceta()))
                .body(creada);
    }

    @PutMapping("/recetas/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Editar la cabecera de una receta",
               description = "Reemplaza los datos editables de la cabecera: nombre, rendimiento y "
                        + "unidad de producción se sobrescriben siempre. cantidadProducir sigue "
                        + "siendo el rendimiento de UNA TANDA COMPLETA (R-02) y debe ser mayor que "
                        + "cero. No cambia el producto —CA-01 ata la receta a su producto— ni el "
                        + "estado, que tiene su propio endpoint. Los ingredientes se editan por el "
                        + "subrecurso de detalles. Los costos se recalculan en la respuesta.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Receta actualizada"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido o rendimiento no "
                + "positivo (VALIDACION_FALLIDA, CANTIDAD_PRODUCIR_INVALIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Receta inexistente"),
        @ApiResponse(responseCode = "409", description = "La unidad de medida indicada no existe "
                + "(UNIDAD_INEXISTENTE)")
    })
    public RecetaResponse actualizar(@PathVariable Integer id,
                                     @Valid @RequestBody ActualizarRecetaRequest req) {
        return recetaService.actualizar(id, req);
    }

    @PatchMapping("/recetas/{id}/estado")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Activar o desactivar una receta",
               description = "Cambio de estado de la receta: la cabecera nunca se elimina (R-07), "
                        + "la baja es la desactivación. Desactivar no borra el detalle ni impide "
                        + "consultarlo; solo saca la receta de la planificación. El cambio es "
                        + "idempotente y devuelve la receta completa con el estado aplicado.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Estado actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido (VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Receta inexistente")
    })
    public RecetaResponse cambiarEstado(@PathVariable Integer id,
                                        @Valid @RequestBody CambiarEstadoRequest req) {
        return recetaService.cambiarEstado(id, req);
    }

    @PostMapping("/recetas/{id}/detalles")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Agregar un ingrediente a una receta",
               description = "Agrega una línea de detalle y devuelve la receta completa con los "
                        + "costos recalculados. La cantidad es el consumo de UNA TANDA COMPLETA "
                        + "(R-02) y se guarda con escala 3 (R-06). La materia prima debe existir y "
                        + "estar activa, y no puede estar ya en la receta: la garantía es el UNIQUE "
                        + "uk_detalle_receta, que el servicio anticipa con un 409 legible (R-01). "
                        + "Devuelve 201 con Location a la receta modificada.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Ingrediente agregado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido (VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Receta inexistente"),
        @ApiResponse(responseCode = "409", description = "Materia prima inexistente o ya presente "
                + "en la receta (MATERIA_PRIMA_INEXISTENTE, MATERIA_PRIMA_DUPLICADA)")
    })
    public ResponseEntity<RecetaResponse> agregarDetalle(
            @PathVariable Integer id,
            @Valid @RequestBody AgregarDetalleRequest req) {
        var receta = recetaService.agregarDetalle(id, req);
        return ResponseEntity
                .created(URI.create("/api/v1/recetas/" + id))
                .body(receta);
    }

    @PutMapping("/recetas/{id}/detalles/{idDetalle}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Editar la cantidad de un ingrediente",
               description = "Cambia la cantidad de una línea de detalle y devuelve la receta con "
                        + "los costos recalculados. La cantidad es el consumo de UNA TANDA "
                        + "COMPLETA (R-02) y se guarda con escala 3 (R-06). La materia prima de la "
                        + "línea no se reemplaza: para cambiar de ingrediente se elimina la línea "
                        + "y se agrega otra, porque el UNIQUE de la receta prohíbe repetirlo "
                        + "(CA-05). La línea debe pertenecer a la receta de la ruta; si no, se "
                        + "responde 404 aunque exista en otra receta.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Ingrediente actualizado"),
        @ApiResponse(responseCode = "400", description = "Cuerpo inválido (VALIDACION_FALLIDA)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Receta inexistente, o línea inexistente "
                + "o de otra receta")
    })
    public RecetaResponse actualizarDetalle(
            @PathVariable Integer id,
            @PathVariable Integer idDetalle,
            @Valid @RequestBody ActualizarDetalleRequest req) {
        return recetaService.actualizarDetalle(id, idDetalle, req);
    }

    @DeleteMapping("/recetas/{id}/detalles/{idDetalle}")
    @PreAuthorize("hasAnyRole('ADMIN','PRODUCCION')")
    @Operation(summary = "Quitar un ingrediente de una receta",
               description = "Elimina físicamente la línea de detalle (R-07). Es la única "
                        + "eliminación del proyecto: un ingrediente quitado no tiene valor "
                        + "histórico y detalle_receta no está referenciada por ninguna otra tabla. "
                        + "La cabecera de la receta no se elimina nunca, solo se desactiva. La "
                        + "línea debe pertenecer a la receta de la ruta; si no, se responde 404. "
                        + "Devuelve 204 sin cuerpo.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Ingrediente eliminado"),
        @ApiResponse(responseCode = "400", description = "Id no numérico (PARAMETRO_INVALIDO)"),
        @ApiResponse(responseCode = "401", description = "Sin token"),
        @ApiResponse(responseCode = "403", description = "Sin permisos"),
        @ApiResponse(responseCode = "404", description = "Receta inexistente, o línea inexistente "
                + "o de otra receta")
    })
    public ResponseEntity<Void> eliminarDetalle(@PathVariable Integer id,
                                               @PathVariable Integer idDetalle) {
        recetaService.eliminarDetalle(id, idDetalle);
        return ResponseEntity.noContent().build();
    }
}
