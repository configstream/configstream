package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.configstream.api.ConfigChange;
import io.github.configstream.api.ConfigChangeListener;
import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.PropertyDeclaration;
import io.github.configstream.api.PropertyType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

class ConfigStreamAutoConfigurationTest {

    static final ConfigValue ONE = new ConfigValue(PropertyType.INT, 1);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigStreamAutoConfiguration.class));

    /** An application with one @LiveConfig class, and a fake store whose data the fake change source reports. */
    private final ApplicationContextRunner app = runner
            .withUserConfiguration(SharedStoreConfig.class, FundsConfig.class)
            .withPropertyValues("spring.application.name=orders");

    @Test
    void failsFastWhenUriIsMissing() {
        runner.run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("configstream.mongo.uri is not set"));
    }

    @Test
    void failsFastWhenDatabaseIsMissing() {
        runner.withPropertyValues("configstream.mongo.uri=mongodb://localhost:27017/?replicaSet=rs0")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("No config database"));
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("configstream.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ConfigService.class));
    }

    @Test
    void customSourceReplacesMongo() {
        runner.withUserConfiguration(FakeSourceConfig.class).run(context -> {
            assertThat(context).hasNotFailed()
                    .doesNotHaveBean(ConfigStreamAutoConfiguration.ConfigStreamMongoClient.class);
            assertThat(context.getBean(ConfigService.class).values()).isEqualTo(Map.of("a.key", ONE));
        });
    }

    @Test
    void createsTheLiveClassesPropertiesWithTheirBoundValues() {
        app.run(context -> {
            assertThat(context).hasNotFailed();
            FakeConfigStore store = context.getBean(FakeConfigStore.class);
            assertThat(store.values).isEqualTo(Map.of(
                    "feature.funds.enabled", new ConfigValue(PropertyType.BOOLEAN, false),
                    "feature.funds.limit", new ConfigValue(PropertyType.INT, 3),
                    "feature.funds.discount-rate", new ConfigValue(PropertyType.DECIMAL, new BigDecimal("0.05"))));
            assertThat(store.history("feature.funds.limit", 10)).singleElement()
                    .extracting(ConfigHistoryEntry::version, ConfigHistoryEntry::newValue,
                            ConfigHistoryEntry::changedBy, ConfigHistoryEntry::comment)
                    .containsExactly(1L, "3", "orders (startup)", "Created from FundsProperties");
        });
    }

    @Test
    void theStartingValueIsWhatSpringBoundLikeApplicationProdYml() {
        // As application-prod.yml would set it when the prod profile is active
        app.withPropertyValues("feature.funds.limit=10").run(context -> {
            assertThat(context.getBean(FakeConfigStore.class).values.get("feature.funds.limit"))
                    .isEqualTo(new ConfigValue(PropertyType.INT, 10));
            assertThat(context.getBean(FundsProperties.class).getLimit()).isEqualTo(10);
        });
    }

    @Test
    void theStoredValueWinsOverTheBoundOne() {
        // An earlier deployment created the property, and it was changed to 50 in the admin since
        ConfigValue stored = new ConfigValue(PropertyType.INT, 50);
        runner.withUserConfiguration(FakeStoreConfig.class, FundsConfig.class)
                .withBean(FakeSource.class, () -> new FakeSource(Map.of("feature.funds.limit", stored)))
                .withBean(EventCollector.class)
                .withPropertyValues("feature.funds.limit=10")
                .withInitializer(context -> context.getBeanFactory().addBeanPostProcessor(new PrefillStore(
                        Map.of("feature.funds.limit", stored))))
                .run(context -> {
                    FundsProperties funds = context.getBean(FundsProperties.class);
                    assertThat(funds.getLimit()).isEqualTo(50);
                    assertThat(funds.limitField()).as("the setter was called too").isEqualTo(50);
                    assertThat(context.getBean(FakeConfigStore.class).history("feature.funds.limit", 10)).isEmpty();
                });
    }

    @Test
    void gettersReturnLiveValuesAndSettersAreUpdated() {
        app.run(context -> {
            FundsProperties funds = context.getBean(FundsProperties.class);
            FakeSource source = context.getBean(FakeSource.class);

            source.listener.onChange(ConfigChange.upsert("feature.funds.limit", new ConfigValue(PropertyType.INT, 25)));
            assertThat(funds.getLimit()).isEqualTo(25);
            assertThat(funds.limitField()).isEqualTo(25);

            // Deleted (e.g. by hand in the database): back to the value it was created with
            source.listener.onChange(ConfigChange.delete("feature.funds.limit"));
            assertThat(funds.getLimit()).isEqualTo(3);
            assertThat(funds.limitField()).isEqualTo(3);

            // A value of another type is not used
            source.listener.onChange(ConfigChange.upsert("feature.funds.limit", new ConfigValue(PropertyType.STRING, "x")));
            assertThat(funds.getLimit()).isEqualTo(3);
        });
    }

    @Test
    void creatingPropertiesOnStartupIsNotAChange() {
        app.run(context -> assertThat(context.getBean(EventCollector.class).events).isEmpty());
    }

    @Test
    void publishesEventsForLaterChangesOnly() {
        runner.withUserConfiguration(FakeSourceConfig.class).run(context -> {
            FakeSource source = context.getBean(FakeSource.class);
            List<ConfigChangedEvent> events = context.getBean(EventCollector.class).events;
            assertThat(events).as("startup load publishes nothing").isEmpty();

            source.listener.onChange(ConfigChange.upsert("a.key", new ConfigValue(PropertyType.INT, 2)));
            source.listener.onChange(ConfigChange.upsert("a.key", new ConfigValue(PropertyType.INT, 2))); // duplicate
            source.listener.onChange(ConfigChange.upsert("b.key", new ConfigValue(PropertyType.STRING, "x")));
            source.listener.onChange(ConfigChange.delete("b.key"));

            assertThat(events).containsExactly(
                    new ConfigChangedEvent("a.key", 1, 2),
                    new ConfigChangedEvent("b.key", null, "x"),
                    new ConfigChangedEvent("b.key", "x", null));
        });
    }

    @Test
    void resyncSnapshotPublishesOnlyTheDifferences() {
        runner.withUserConfiguration(FakeSourceConfig.class).run(context -> {
            FakeSource source = context.getBean(FakeSource.class);
            List<ConfigChangedEvent> events = context.getBean(EventCollector.class).events;
            ConfigValue same = new ConfigValue(PropertyType.STRING, "same");
            source.listener.onChange(ConfigChange.upsert("keep.key", same));
            events.clear();

            source.listener.onSnapshot(Map.of("a.key", new ConfigValue(PropertyType.INT, 5), "keep.key", same,
                    "new.key", new ConfigValue(PropertyType.BOOLEAN, true)));

            assertThat(events).containsExactlyInAnyOrder(
                    new ConfigChangedEvent("a.key", 1, 5),
                    new ConfigChangedEvent("new.key", null, true));
        });
    }

    @Test
    void aTypeChangeStopsStartupWithAnExplanation() {
        app.withInitializer(context -> context.getBeanFactory().addBeanPostProcessor(new PrefillStore(
                        Map.of("feature.funds.limit", new ConfigValue(PropertyType.STRING, "3")))))
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("'feature.funds.limit' is stored as string but declared as int "
                                + "(FundsProperties.getLimit)")
                        .hasMessageContaining("rename the field"));
    }

    @Test
    void registersDeclarationsWithDescriptionsFromTheJavadoc() {
        app.run(context -> assertThat(context.getBean(LiveConfigRegistry.class).declarations()).containsExactlyInAnyOrder(
                new PropertyDeclaration("feature.funds.enabled", PropertyType.BOOLEAN, false, "Show the funds page."),
                new PropertyDeclaration("feature.funds.limit", PropertyType.INT, 3, "Maximum funds shown per page."),
                new PropertyDeclaration("feature.funds.discount-rate", PropertyType.DECIMAL, new BigDecimal("0.05"), null)));
    }

    @Test
    void onlyClassesMarkedLiveAreStored() {
        app.withUserConfiguration(NotLiveConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FakeConfigStore.class).values).doesNotContainKey("app.datasource.url");
            assertThat(context.getBean(NotLive.class).getUrl()).isEqualTo("jdbc:postgresql://db/orders");
        });
    }

    @Test
    void worksWithoutAWriterUsingTheBoundValues() {
        runner.withUserConfiguration(FakeSourceConfig.class, FundsConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FundsProperties.class).getLimit()).isEqualTo(3);
        });
    }

    @Test
    void rejectsClassesThatCantBeLive() {
        app.withUserConfiguration(UnsupportedTypeConfig.class)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("property 'timeout' has type Duration, which can't be live"));
        app.withUserConfiguration(RecordConfig.class)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("can't be final or a record"));
        app.withUserConfiguration(NoValueConfig.class)
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("property 'banner' (checkout.banner) has no value"));
    }

    @Test
    void stopsSourceOnShutdown() {
        FakeSource[] captured = new FakeSource[1];
        runner.withUserConfiguration(FakeSourceConfig.class)
                .run(context -> captured[0] = context.getBean(FakeSource.class));

        assertThat(captured[0].stopped).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class FakeSourceConfig {
        @Bean
        FakeSource fakeSource() {
            return new FakeSource(Map.of("a.key", ONE));
        }

        @Bean
        EventCollector eventCollector() {
            return new EventCollector();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FakeStoreConfig {
        @Bean
        FakeConfigStore fakeConfigStore() {
            return new FakeConfigStore();
        }
    }

    /** Like MongoDB: what the store writes is what the change source reports. */
    @Configuration(proxyBeanMethods = false)
    static class SharedStoreConfig {
        @Bean
        FakeConfigStore fakeConfigStore() {
            return new FakeConfigStore();
        }

        @Bean
        FakeSource fakeSource(FakeConfigStore store) {
            return new FakeSource(store.values);
        }

        @Bean
        EventCollector eventCollector() {
            return new EventCollector();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FundsProperties.class)
    static class FundsConfig {
    }

    /** Ordinary configuration, not marked live: must never reach the store. */
    @ConfigurationProperties("app.datasource")
    public static class NotLive {
        private String url = "jdbc:postgresql://db/orders";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NotLive.class)
    static class NotLiveConfig {
    }

    @ConfigurationProperties("orders.http")
    @LiveConfig
    public static class UnsupportedType {
        private Duration timeout = Duration.ofSeconds(2);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UnsupportedType.class)
    static class UnsupportedTypeConfig {
    }

    @ConfigurationProperties("orders.limits")
    @LiveConfig
    public record LimitsRecord(int max) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LimitsRecord.class)
    static class RecordConfig {
    }

    @ConfigurationProperties("checkout")
    @LiveConfig
    public static class NoValue {
        private String banner;

        public String getBanner() {
            return banner;
        }

        public void setBanner(String banner) {
            this.banner = banner;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(NoValue.class)
    static class NoValueConfig {
    }

    /** Fills the fake store before the live classes are registered, as if earlier deployments had created values. */
    record PrefillStore(Map<String, ConfigValue> values) implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof FakeConfigStore store) {
                store.values.putAll(values);
            }
            return bean;
        }
    }

    static class EventCollector {
        final List<ConfigChangedEvent> events = new CopyOnWriteArrayList<>(); // written by the change-stream thread

        @EventListener
        void on(ConfigChangedEvent event) {
            events.add(event);
        }
    }

    /** Delivers a fixed snapshot; tests then drive changes through {@link #listener}. */
    static class FakeSource implements ConfigChangeSource {
        private final Map<String, ConfigValue> initial;
        ConfigChangeListener listener;
        boolean stopped;

        /** {@code initial} is read on every load, so a live map (such as a fake store's) is followed. */
        FakeSource(Map<String, ConfigValue> initial) {
            this.initial = initial;
        }

        @Override
        public Map<String, ConfigValue> loadInitial() {
            return new HashMap<>(initial);
        }

        @Override
        public void start(ConfigChangeListener listener) {
            this.listener = listener;
            listener.onSnapshot(loadInitial());
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}
