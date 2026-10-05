package io.github.configstream.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import io.github.configstream.api.ConfigChange;
import io.github.configstream.api.ConfigChangeListener;
import io.github.configstream.api.ConfigChangeSource;
import io.github.configstream.api.ConfigHistoryEntry;
import io.github.configstream.api.ConfigValue;
import io.github.configstream.api.Manifest;
import io.github.configstream.api.Property;
import io.github.configstream.api.PropertyType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

class ConfigStreamAutoConfigurationTest {

    static final ConfigValue ONE = new ConfigValue(PropertyType.INT, 1);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigStreamAutoConfiguration.class));

    @Test
    void failsFastWithoutTheApplicationsMongoClient() {
        runner.run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("configstream uses your application's MongoClient")
                .hasMessageContaining("spring.data.mongodb.uri"));
    }

    @Test
    void failsFastWhenDatabaseIsMissing() {
        // Creating a client doesn't connect, and the database is checked before anything is read
        runner.withBean(MongoClient.class, () -> MongoClients.create("mongodb://localhost:1"))
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
                    .doesNotHaveBean(ConfigStreamAutoConfiguration.ConfigStreamDatabase.class);
            assertThat(context.getBean(ConfigService.class).values()).isEqualTo(Map.of("a.key", ONE));
        });
    }

    @Test
    void withoutAManifestNothingIsDeclared() {
        runner.withUserConfiguration(FakeSourceConfig.class)
                .run(context -> assertThat(context.getBean(Manifest.class).properties()).isEmpty());
    }

    @Test
    void createsTheManifestsMissingPropertiesOnStartup() {
        runner.withUserConfiguration(FakeSourceConfig.class, FakeStoreConfig.class)
                .withPropertyValues("configstream.manifest=classpath:manifests/configstream.yml",
                        "spring.application.name=orders")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FakeConfigStore store = context.getBean(FakeConfigStore.class);
                    assertThat(store.values).isEqualTo(Map.of(
                            "feature.funds.enabled", new ConfigValue(PropertyType.BOOLEAN, false),
                            "feature.funds.limit", new ConfigValue(PropertyType.INT, 3)));
                    assertThat(store.history("feature.funds.limit", 10)).singleElement()
                            .extracting(ConfigHistoryEntry::version, ConfigHistoryEntry::newValue,
                                    ConfigHistoryEntry::changedBy, ConfigHistoryEntry::comment)
                            .containsExactly(1L, "3", "orders (manifest)", "Created from configstream.yml");
                });
    }

    @Test
    void anEnvironmentFileSetsTheInitialValuesOfPropertiesCreatedThere() {
        runner.withUserConfiguration(FakeSourceConfig.class, FakeStoreConfig.class)
                .withPropertyValues("configstream.manifest=classpath:manifests/configstream.yml",
                        "configstream.environment=prod")
                .run(context -> {
                    FakeConfigStore store = context.getBean(FakeConfigStore.class);
                    assertThat(store.values.get("feature.funds.limit")).isEqualTo(new ConfigValue(PropertyType.INT, 10));
                    assertThat(store.values.get("feature.funds.enabled")).isEqualTo(new ConfigValue(PropertyType.BOOLEAN, false));
                    assertThat(store.history("feature.funds.limit", 1).get(0).comment())
                            .isEqualTo("Created from configstream-prod.yml");
                });
    }

    @Test
    void anEnvironmentWithoutItsOwnFileUsesTheBaseValues() {
        runner.withUserConfiguration(FakeSourceConfig.class, FakeStoreConfig.class)
                .withPropertyValues("configstream.manifest=classpath:manifests/configstream.yml",
                        "configstream.environment=qa")
                .run(context -> assertThat(context.getBean(FakeConfigStore.class).values.get("feature.funds.limit"))
                        .isEqualTo(new ConfigValue(PropertyType.INT, 3)));
    }

    @Test
    void existingValuesAreNeverChanged() {
        runner.withUserConfiguration(FakeSourceConfig.class, FakeStoreConfig.class)
                .withPropertyValues("configstream.manifest=classpath:manifests/configstream.yml")
                .withInitializer(context -> context.getBeanFactory().addBeanPostProcessor(new PrefillStore(
                        Map.of("feature.funds.limit", new ConfigValue(PropertyType.INT, 50)))))
                .run(context -> {
                    FakeConfigStore store = context.getBean(FakeConfigStore.class);
                    assertThat(store.values.get("feature.funds.limit")).isEqualTo(new ConfigValue(PropertyType.INT, 50));
                    assertThat(store.history("feature.funds.limit", 10)).isEmpty();
                });
    }

    @Test
    void aTypeChangeStopsStartupWithAnExplanation() {
        runner.withUserConfiguration(FakeSourceConfig.class, FakeStoreConfig.class)
                .withPropertyValues("configstream.manifest=classpath:manifests/configstream.yml")
                .withInitializer(context -> context.getBeanFactory().addBeanPostProcessor(new PrefillStore(
                        Map.of("feature.funds.limit", new ConfigValue(PropertyType.STRING, "3")))))
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("'feature.funds.limit' is stored as string but declared as int")
                        .hasMessageContaining("declare the property under a new key"));
    }

    @Test
    void anInvalidManifestStopsStartup() {
        runner.withUserConfiguration(FakeSourceConfig.class)
                .withPropertyValues("configstream.manifest=classpath:manifests/broken.yml")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("broken.yml: property 'feature.funds.limit' is declared as int"));
    }

    @Test
    void rejectsAnEnvironmentNameThatIsNotAPlainWord() {
        runner.withUserConfiguration(FakeSourceConfig.class)
                .withPropertyValues("configstream.environment=../prod")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("may only contain letters, digits"));
    }

    @Test
    void publishesTypedEventsForLaterChangesOnly() {
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
            assertThat(events.get(0).isFor(Property.of("a.key", Integer.class, 0))).isTrue();
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

    /** Fills the fake store before the manifest is synced, as if earlier deployments had created or changed values. */
    record PrefillStore(Map<String, ConfigValue> values)
            implements BeanPostProcessor {
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

    /** Delivers a fixed snapshot on start; tests then drive changes through {@link #listener}. */
    static class FakeSource implements ConfigChangeSource {
        private final Map<String, ConfigValue> initial;
        ConfigChangeListener listener;
        boolean stopped;

        FakeSource(Map<String, ConfigValue> initial) {
            this.initial = initial;
        }

        @Override
        public Map<String, ConfigValue> loadInitial() {
            return initial;
        }

        @Override
        public void start(ConfigChangeListener listener) {
            this.listener = listener;
            listener.onSnapshot(initial);
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }
}
