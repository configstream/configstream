package io.github.configstream.spring;

import com.mongodb.ConnectionString;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

/**
 * The application's own MongoDB settings, as Spring Boot's MongoDB auto-configuration reads them:
 * {@code spring.data.mongodb.*} on Spring Boot 3, {@code spring.mongodb.*} on Spring Boot 4. Read from the environment
 * rather than through Boot's {@code MongoProperties}, which Boot 4 moved to another package, so one configstream jar
 * runs on both.
 *
 * @param prefix   the property prefix in use, or {@code null} when Spring Boot's MongoDB auto-configuration isn't
 *                 active (the application then defines its own {@code MongoClient}, and configstream needs
 *                 {@code configstream.mongo.database})
 * @param uri      the connection string, if set
 * @param database the database, if set separately from the connection string
 */
record SpringMongoSettings(String prefix, String uri, String database) {

    private static final String BOOT_3_MONGO_PROPERTIES = "org.springframework.boot.autoconfigure.mongo.MongoProperties";
    private static final String BOOT_4_MONGO_PROPERTIES = "org.springframework.boot.mongodb.autoconfigure.MongoProperties";

    static SpringMongoSettings from(Environment environment, ListableBeanFactory beans, ClassLoader classLoader) {
        String prefix = hasBean(beans, BOOT_4_MONGO_PROPERTIES, classLoader) ? "spring.mongodb"
                : hasBean(beans, BOOT_3_MONGO_PROPERTIES, classLoader) ? "spring.data.mongodb"
                : null;
        if (prefix == null) {
            return new SpringMongoSettings(null, null, null);
        }
        return new SpringMongoSettings(prefix, environment.getProperty(prefix + ".uri"),
                environment.getProperty(prefix + ".database"));
    }

    /** Whether Boot's MongoDB auto-configuration ran: it registers its {@code MongoProperties} bean. */
    private static boolean hasBean(ListableBeanFactory beans, String className, ClassLoader classLoader) {
        return ClassUtils.isPresent(className, classLoader)
                && beans.getBeanNamesForType(ClassUtils.resolveClassName(className, classLoader), true, false).length > 0;
    }

    /**
     * The database the application's {@code MongoClient} uses, decided the way Spring Boot does: the database
     * property, else the one in the connection string, else {@code test} when no connection string is set (Boot's
     * default is {@code mongodb://localhost/test}). {@code null} without Boot's MongoDB auto-configuration, or when
     * the connection string names no database.
     */
    String clientDatabase() {
        if (prefix == null) {
            return null;
        }
        if (database != null && !database.isBlank()) {
            return database;
        }
        return uri == null ? "test" : new ConnectionString(uri).getDatabase();
    }

    /** The connection-string property, for messages. */
    String uriProperty() {
        return prefix == null ? "spring.mongodb.uri (Spring Boot 4) or spring.data.mongodb.uri (Spring Boot 3)"
                : prefix + ".uri";
    }

    /** The database property, for messages. */
    String databaseProperty() {
        return prefix == null ? "spring.mongodb.database (Spring Boot 4) or spring.data.mongodb.database (Spring Boot 3)"
                : prefix + ".database";
    }
}
