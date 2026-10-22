package co.edu.corposucre.productionfood.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FiltroTextoTest {

    @Test
    @DisplayName("Texto nulo o en blanco devuelve null para omitir el filtro")
    void textoNuloOEnBlancoDevuelveNull() {
        assertThat(FiltroTexto.patronPrefijo(null)).isNull();
        assertThat(FiltroTexto.patronPrefijo("")).isNull();
        assertThat(FiltroTexto.patronPrefijo("   ")).isNull();
    }

    @Test
    @DisplayName("El texto se recorta, pasa a minúsculas y agrega el sufijo de prefijo")
    void textoSeRecortaYPasaAMinusculasConSufijo() {
        assertThat(FiltroTexto.patronPrefijo("Harina")).isEqualTo("harina%");
        assertThat(FiltroTexto.patronPrefijo(" Hari ")).isEqualTo("hari%");
    }

    @Test
    @DisplayName("Los comodines de SQL se escapan antes del sufijo")
    void comodinesDeSqlSeEscapan() {
        assertThat(FiltroTexto.patronPrefijo("100%")).isEqualTo("100\\%%");
        assertThat(FiltroTexto.patronPrefijo("a_b")).isEqualTo("a\\_b%");
        assertThat(FiltroTexto.patronPrefijo("a\\b")).isEqualTo("a\\\\b%");
    }

    @Test
    @DisplayName("La minúscula usa Locale.ROOT y no depende del locale del sistema")
    void minusculaUsaLocaleRoot() {
        assertThat(FiltroTexto.patronPrefijo("KG")).isEqualTo("kg%");
    }
}
