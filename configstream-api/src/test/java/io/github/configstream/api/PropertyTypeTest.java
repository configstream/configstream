package io.github.configstream.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PropertyTypeTest {

    @Test
    void parsesTypedText() {
        assertThat(PropertyType.BOOLEAN.parse(" TRUE ")).isEqualTo(true);
        assertThat(PropertyType.BOOLEAN.parse("false")).isEqualTo(false);
        assertThat(PropertyType.INT.parse(" 42 ")).isEqualTo(42);
        assertThat(PropertyType.INT.parse("-7")).isEqualTo(-7);
        assertThat(PropertyType.DECIMAL.parse("0.10")).isEqualTo(new BigDecimal("0.10"));
        assertThat(PropertyType.STRING.parse(" kept as typed ")).isEqualTo(" kept as typed ");
    }

    @ParameterizedTest
    @CsvSource({"BOOLEAN, yes", "BOOLEAN, 1", "INT, 3.5", "INT, abc", "INT, 99999999999", "INT, ''", "DECIMAL, '1,5'", "DECIMAL, abc"})
    void rejectsTextThatIsNotAValueOfTheType(PropertyType type, String text) {
        assertThatThrownBy(() -> type.parse(text))
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessage("'" + text + "' is not a valid " + type.typeName() + ".");
    }

    @Test
    void coercesValuesEnteredByHandWithoutLosingInformation() {
        assertThat(PropertyType.INT.coerce(5.0)).isEqualTo(5);
        assertThat(PropertyType.INT.coerce(5L)).isEqualTo(5);
        assertThat(PropertyType.INT.coerce("7")).isEqualTo(7);
        assertThat(PropertyType.DECIMAL.coerce(1.5)).isEqualTo(new BigDecimal("1.5"));
        assertThat(PropertyType.DECIMAL.coerce(2)).isEqualTo(new BigDecimal("2"));
        assertThat(PropertyType.STRING.coerce(12)).isEqualTo("12");
        assertThat(PropertyType.BOOLEAN.coerce("true")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(doubles = {5.5, 3e10})
    void refusesToCoerceANumberThatIsNotAnInt(double raw) {
        assertThatThrownBy(() -> PropertyType.INT.coerce(raw)).isInstanceOf(InvalidConfigValueException.class);
    }

    @Test
    void refusesToCoerceANumberToABoolean() {
        assertThatThrownBy(() -> PropertyType.BOOLEAN.coerce(1)).isInstanceOf(InvalidConfigValueException.class);
    }

    @Test
    void formatsDecimalsWithoutExponents() {
        assertThat(PropertyType.DECIMAL.format(new BigDecimal("1E+3"))).isEqualTo("1000");
        assertThat(PropertyType.INT.format(3)).isEqualTo("3");
    }

    @Test
    void comparesDecimalsNumerically() {
        assertThat(PropertyType.DECIMAL.sameValue(new BigDecimal("1.0"), new BigDecimal("1.00"))).isTrue();
        assertThat(new ConfigValue(PropertyType.DECIMAL, new BigDecimal("1.0"))
                .sameAs(new ConfigValue(PropertyType.DECIMAL, new BigDecimal("1.00")))).isTrue();
        assertThat(new ConfigValue(PropertyType.INT, 1).sameAs(new ConfigValue(PropertyType.DECIMAL, BigDecimal.ONE))).isFalse();
    }

    @Test
    void namesAndJavaTypes() {
        assertThat(PropertyType.fromName("decimal")).isEqualTo(PropertyType.DECIMAL);
        assertThat(PropertyType.forJavaType(Integer.class)).isEqualTo(PropertyType.INT);
        assertThatThrownBy(() -> PropertyType.fromName("long")).hasMessageContaining("boolean, int, decimal, string");
        assertThatThrownBy(() -> PropertyType.forJavaType(Long.class)).hasMessageContaining("Boolean, Integer, BigDecimal or String");
    }

    @Test
    void declarationsValidateTheirKeyAndInitialValue() {
        PropertyDeclaration limit = new PropertyDeclaration("feature.funds.limit", PropertyType.INT, 3, null);
        assertThat(limit.initial()).isEqualTo(new ConfigValue(PropertyType.INT, 3));

        assertThatThrownBy(() -> new PropertyDeclaration("limit", PropertyType.INT, 3, null))
                .hasMessageContaining("at least two dot-separated parts");
        assertThatThrownBy(() -> new PropertyDeclaration("feature.2x", PropertyType.INT, 3, null))
                .hasMessageContaining("Invalid property key");
        assertThatThrownBy(() -> new PropertyDeclaration("feature.x", PropertyType.INT, "3", null))
                .hasMessageContaining("must be a Integer");
    }
}
