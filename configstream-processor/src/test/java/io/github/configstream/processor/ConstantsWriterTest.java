package io.github.configstream.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.configstream.api.Manifest;
import io.github.configstream.api.PropertyDeclaration;
import io.github.configstream.api.PropertyType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConstantsWriterTest {

    @Test
    void namesClassesAfterTheFirstPartAndConstantsAfterTheRest() {
        assertThat(ConstantsWriter.className("feature")).isEqualTo("Feature");
        assertThat(ConstantsWriter.className("checkout-page")).isEqualTo("CheckoutPage");
        assertThat(ConstantsWriter.className("checkout_page")).isEqualTo("CheckoutPage");
        assertThat(ConstantsWriter.className("featureFlags")).isEqualTo("FeatureFlags");

        assertThat(ConstantsWriter.constantName("feature.funds.limit")).isEqualTo("FUNDS_LIMIT");
        assertThat(ConstantsWriter.constantName("feature.x.enabled")).isEqualTo("X_ENABLED");
        assertThat(ConstantsWriter.constantName("checkout.maxItems")).isEqualTo("MAX_ITEMS");
        assertThat(ConstantsWriter.constantName("checkout.max-items.v2")).isEqualTo("MAX_ITEMS_V2");
    }

    @Test
    void writesOneClassPerFirstPartWithTypedConstants() {
        Manifest manifest = Manifest.of("configstream.yml", List.of(
                new PropertyDeclaration("feature.funds.enabled", PropertyType.BOOLEAN, false, "Show the <b>funds</b> page"),
                new PropertyDeclaration("feature.funds.limit", PropertyType.INT, 3, null),
                new PropertyDeclaration("fees.rate", PropertyType.DECIMAL, new BigDecimal("0.25"), null),
                new PropertyDeclaration("checkout.banner", PropertyType.STRING, "Say \"hi\"\né", null)));

        List<ConstantsWriter.GeneratedClass> classes = ConstantsWriter.write(manifest, "com.example.orders", "configstream.yml");

        assertThat(classes).extracting(ConstantsWriter.GeneratedClass::simpleName).containsExactly("Feature", "Fees", "Checkout");
        assertThat(classes.get(0).source())
                .startsWith("package com.example.orders;\n")
                .contains("public final class Feature {")
                .contains("public static final Property<Boolean> FUNDS_ENABLED =\n"
                        + "            Property.of(\"feature.funds.enabled\", Boolean.class, false);")
                .contains("public static final Property<Integer> FUNDS_LIMIT =\n"
                        + "            Property.of(\"feature.funds.limit\", Integer.class, 3);")
                .contains("Show the &lt;b&gt;funds&lt;/b&gt; page")
                .contains("@Generated(\"io.github.configstream.processor.ConfigStreamManifestProcessor\")");
        assertThat(classes.get(1).source())
                .contains("import java.math.BigDecimal;")
                .contains("Property.of(\"fees.rate\", BigDecimal.class, new BigDecimal(\"0.25\"))");
        assertThat(classes.get(2).source())
                .contains("Property.of(\"checkout.banner\", String.class, \"Say \\\"hi\\\"\\n\\u00e9\")");
    }

    @Test
    void rejectsKeysThatWouldGenerateTheSameName() {
        assertThatThrownBy(() -> ConstantsWriter.write(Manifest.of("configstream.yml", List.of(
                new PropertyDeclaration("feature.max-items", PropertyType.INT, 1, null),
                new PropertyDeclaration("feature.max_items", PropertyType.INT, 1, null))), "p", "configstream.yml"))
                .hasMessage("'feature.max-items' and 'feature.max_items' would both generate Feature.MAX_ITEMS. Rename one of them.");
        assertThatThrownBy(() -> ConstantsWriter.write(Manifest.of("configstream.yml", List.of(
                new PropertyDeclaration("checkout-page.a", PropertyType.INT, 1, null),
                new PropertyDeclaration("checkoutPage.b", PropertyType.INT, 1, null))), "p", "configstream.yml"))
                .hasMessage("keys starting with 'checkout-page.' and 'checkoutPage.' would both generate class CheckoutPage. "
                        + "Rename one of them.");
    }
}
