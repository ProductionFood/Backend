package co.edu.corposucre.productionfood.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import co.edu.corposucre.productionfood.common.error.ParametroInvalidoException;

class PageRequestTest {

    private static final Map<String, String> ORDEN = Map.of(
        "nombre", "nombre", "correo", "correo", "id", "idUsuario");

    private PageRequest of(int page, int size, String sort) {
        return PageRequest.of(page, size, sort, ORDEN, "nombre,asc");
    }

    @Test
    @DisplayName("Sin sort usa el orden por defecto del recurso")
    void sinSortUsaElPorDefecto() {
        var req = of(0, 20, null);

        assertThat(req.propiedad()).isEqualTo("nombre");
        assertThat(req.ascendente()).isTrue();
        assertThat(req.page()).isZero();
        assertThat(req.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("sort en blanco también cae en el orden por defecto")
    void sortEnBlancoUsaElPorDefecto() {
        assertThat(of(1, 10, "   ").propiedad()).isEqualTo("nombre");
    }

    @Test
    @DisplayName("sort sin dirección ordena ascendente")
    void sortSinDireccionOrdenaAscendente() {
        var req = of(0, 20, "correo");

        assertThat(req.propiedad()).isEqualTo("correo");
        assertThat(req.ascendente()).isTrue();
    }

    @Test
    @DisplayName("sort con desc ordena descendente")
    void sortDescendente() {
        var req = of(0, 20, "nombre,desc");

        assertThat(req.propiedad()).isEqualTo("nombre");
        assertThat(req.ascendente()).isFalse();
    }

    @Test
    @DisplayName("El campo id se traduce a la propiedad JPA idUsuario")
    void mapeaIdALaPropiedadJpa() {
        assertThat(of(0, 20, "id,asc").propiedad()).isEqualTo("idUsuario");
    }

    @Test
    @DisplayName("Campo fuera de la lista blanca responde 400 CAMPO_ORDEN_INVALIDO")
    void campoFueraDeListaBlancaResponde400() {
        var ex = assertThrows(ParametroInvalidoException.class,
            () -> of(0, 20, "password,asc"));

        assertThat(ex.getCodigo()).isEqualTo("CAMPO_ORDEN_INVALIDO");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ex.getMessage()).contains("password");
    }

    @Test
    @DisplayName("size=0 responde 400 PARAMETRO_INVALIDO")
    void sizeCeroResponde400() {
        var ex = assertThrows(ParametroInvalidoException.class,
            () -> of(0, 0, null));

        assertThat(ex.getCodigo()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("size=101 responde 400 PARAMETRO_INVALIDO en vez de recortar")
    void sizeMayorACienResponde400() {
        var ex = assertThrows(ParametroInvalidoException.class,
            () -> of(0, 101, null));

        assertThat(ex.getCodigo()).isEqualTo("PARAMETRO_INVALIDO");
        assertThat(ex.getMessage()).contains("100");
    }

    @Test
    @DisplayName("page=-1 responde 400 PARAMETRO_INVALIDO en vez de recortar")
    void pageNegativoResponde400() {
        var ex = assertThrows(ParametroInvalidoException.class,
            () -> of(-1, 20, null));

        assertThat(ex.getCodigo()).isEqualTo("PARAMETRO_INVALIDO");
    }

    @Test
    @DisplayName("toPageable lleva la propiedad y la dirección a Spring Data")
    void toPageableTraducePropiedadYDireccion() {
        var pageable = of(2, 10, "id,desc").toPageable();

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().getOrderFor("idUsuario")).isNotNull();
        assertThat(pageable.getSort().getOrderFor("idUsuario").isDescending()).isTrue();
    }
}
