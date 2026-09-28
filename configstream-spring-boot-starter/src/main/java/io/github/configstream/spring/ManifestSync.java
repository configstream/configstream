package io.github.configstream.spring;

import io.github.configstream.api.ConfigWriter;
import io.github.configstream.api.Manifest;
import io.github.configstream.api.PropertyDeclaration;
import io.github.configstream.api.PropertyType;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the manifest's properties that are missing from the store, so an application deployed to a new
 * environment finds all of its properties there. Existing values are never changed.
 */
final class ManifestSync {

    private ManifestSync() {
    }

    /**
     * @param serviceName recorded in the history as who created each property, e.g. {@code orders (manifest)}
     * @throws IllegalStateException if a property exists with a different type than the manifest declares
     */
    static void createMissing(Manifest manifest, ConfigWriter writer, String serviceName) {
        List<String> typeChanges = new ArrayList<>();
        for (PropertyDeclaration declaration : manifest.properties()) {
            PropertyType stored = writer.createIfAbsent(declaration.key(), declaration.initial(),
                    serviceName + " (manifest)", "Created from " + manifest.initialValueSource(declaration.key()));
            if (stored != declaration.type()) {
                typeChanges.add("'" + declaration.key() + "' is stored as " + stored.typeName()
                        + " but declared as " + declaration.type().typeName());
            }
        }
        if (!typeChanges.isEmpty()) {
            throw new IllegalStateException("A property's type can't change: " + String.join("; ", typeChanges)
                    + ". To change a type, declare the property under a new key and update the code that reads it; "
                    + "the old key is shown as orphaned once no running instance declares it.");
        }
    }
}
