package io.github.configstream.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ManifestParserTest {

    private static final String BASE = """
            properties:
              - key: feature.funds.enabled
                type: boolean
                initialValue: false
                description: Show the funds page
              - key: feature.funds.limit
                type: int
                initialValue: 3
              - key: fees.rate
                type: decimal
                initialValue: 0.25
              - key: banner.text
                type: string
                initialValue: "007"
            """;

    @Test
    void readsDeclarationsInOrder() {
        Manifest manifest = parse(BASE);

        assertThat(manifest.properties()).containsExactly(
                new PropertyDeclaration("feature.funds.enabled", PropertyType.BOOLEAN, false, "Show the funds page"),
                new PropertyDeclaration("feature.funds.limit", PropertyType.INT, 3, null),
                new PropertyDeclaration("fees.rate", PropertyType.DECIMAL, new BigDecimal("0.25"), null),
                new PropertyDeclaration("banner.text", PropertyType.STRING, "007", null));
        assertThat(manifest.declares("fees.rate")).isTrue();
        assertThat(manifest.declares("fees.other")).isFalse();
        assertThat(manifest.initialValueSource("fees.rate")).isEqualTo("configstream.yml");
    }

    @Test
    void anEmptyFileDeclaresNothing() {
        assertThat(parse("").properties()).isEmpty();
        assertThat(parse("properties:\n").properties()).isEmpty();
    }

    @Test
    void environmentFilesChangeInitialValuesOnly() {
        Manifest prod = ManifestParser.applyEnvironment(parse(BASE), stream("""
                properties:
                  feature.funds.limit: 10
                  fees.rate: "0.30"
                """), "configstream-prod.yml");

        assertThat(prod.find("feature.funds.limit")).get()
                .extracting(PropertyDeclaration::type, PropertyDeclaration::initialValue)
                .containsExactly(PropertyType.INT, 10);
        assertThat(prod.find("fees.rate").orElseThrow().initialValue()).isEqualTo(new BigDecimal("0.30"));
        assertThat(prod.find("feature.funds.enabled").orElseThrow().initialValue()).isEqualTo(false);
        assertThat(prod.initialValueSource("feature.funds.limit")).isEqualTo("configstream-prod.yml");
        assertThat(prod.initialValueSource("feature.funds.enabled")).isEqualTo("configstream.yml");
    }

    @Test
    void environmentFilesCannotDeclareNewProperties() {
        assertThatThrownBy(() -> ManifestParser.applyEnvironment(parse(BASE), stream("""
                properties:
                  feature.new.thing: 1
                """), "configstream-prod.yml"))
                .isInstanceOf(ManifestException.class)
                .hasMessage("configstream-prod.yml: property 'feature.new.thing' is not declared in the base manifest. "
                        + "Environment files can only change initial values; declare new properties in configstream.yml.");
    }

    @Test
    void environmentValuesMustFitTheDeclaredType() {
        assertThatThrownBy(() -> ManifestParser.applyEnvironment(parse(BASE), stream("""
                properties:
                  feature.funds.limit: 3.5
                """), "configstream-prod.yml"))
                .hasMessage("configstream-prod.yml: property 'feature.funds.limit' is declared as int, but its value 3.5 "
                        + "is not a valid int.");
    }

    @Test
    void rejectsMistakesWithTheFileAndPropertyInTheMessage() {
        assertError("""
                properties:
                  - key: a.b
                    type: int
                    initialValue: 1
                  - key: a.b
                    type: int
                    initialValue: 2
                """, "property 'a.b' is declared more than once.");
        assertError("""
                properties:
                  - key: a.b
                    type: int
                    initalValue: 1
                """, "property #1 has unknown field 'initalValue'; allowed fields are key, type, initialValue and description.");
        assertError("""
                properties:
                  - key: limit
                    type: int
                    initialValue: 1
                """, "property #1 has an invalid key 'limit'.");
        assertError("""
                properties:
                  - key: a.b
                    type: long
                    initialValue: 1
                """, "property 'a.b': Unknown property type 'long'.");
        assertError("""
                properties:
                  - key: a.b
                    type: int
                """, "property 'a.b' has no initialValue.");
        assertError("""
                properties:
                  - key: a.b
                    type: string
                    initialValue: 007
                """, "property 'a.b' is declared as string, but its value 7 is not a valid string. Put it in quotes");
        assertError("""
                properties:
                  - key: a.b
                    type: boolean
                    initialValue: "true"
                """, "property 'a.b' is declared as boolean, but its value \"true\" is not a valid boolean.");
        assertError("""
                properties:
                  - key: a.b
                    type: int
                    initialValue: 99999999999
                """, "It is too large for an int.");
        assertError("props: []", "unknown top-level field 'props'");
        assertError("properties: [", "not valid YAML");
    }

    private static void assertError(String yaml, String message) {
        assertThatThrownBy(() -> parse(yaml))
                .isInstanceOf(ManifestException.class)
                .hasMessageStartingWith("configstream.yml: ")
                .hasMessageContaining(message);
    }

    private static Manifest parse(String yaml) {
        return ManifestParser.parse(stream(yaml), "configstream.yml");
    }

    private static ByteArrayInputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }
}
