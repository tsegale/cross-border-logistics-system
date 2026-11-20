package com.freightflow.notification.support;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The committed event contracts, docs/events/*.schema.json (ADR 0023), loaded from the repository like the OpenAPI
 * contract (ADR 0014). {@code $ref}s to {@code https://freightflow.dev/events/...} resolve to the files next to them,
 * and formats (uuid, date-time) are asserted, not just annotated.
 */
public final class EventSchemas {

    public static final Path DIRECTORY = Path.of("..", "docs", "events").toAbsolutePath().normalize();
    private static final String BASE = "https://freightflow.dev/events/";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            builder -> builder
                    .schemaLoader(loader -> loader.resourceLoaders(loaders -> loaders.resources(EventSchemas::read)))
                    .schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
    private static final Map<String, Schema> BY_TYPE = new ConcurrentHashMap<>();

    private EventSchemas() {
    }

    /** Content of a schema by its id: https://freightflow.dev/events/x.schema.json is docs/events/x.schema.json. */
    private static String read(String iri) {
        if (!iri.startsWith(BASE)) {
            return null;
        }
        try {
            return Files.readString(DIRECTORY.resolve(iri.substring(BASE.length())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The schema of one event type, e.g. {@code hold.captured}. */
    public static Schema of(String type) {
        return BY_TYPE.computeIfAbsent(type, name -> {
            Schema schema = REGISTRY.getSchema(SchemaLocation.of(BASE + name + ".schema.json"));
            schema.initializeValidators();
            return schema;
        });
    }

    /** What is wrong with this event, as JSON text; empty if it matches its type's schema. */
    public static List<Error> validate(String type, String event) {
        return of(type).validate(JSON.readTree(event));
    }

    /** Every schema file, event schemas and the shared ones, as JSON trees by file name. */
    public static Map<String, JsonNode> files() {
        try (Stream<Path> paths = Files.list(DIRECTORY)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .collect(java.util.stream.Collectors.toMap(path -> path.getFileName().toString(),
                            path -> JSON.readTree(path.toFile())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The schemas of the events this service produces ({@code x-producer}), by type. */
    public static Map<String, JsonNode> producedBy(String service) {
        return files().values().stream()
                .filter(schema -> service.equals(schema.path("x-producer").asString(null)))
                .collect(java.util.stream.Collectors.toMap(schema -> schema.path("title").asString(),
                        schema -> schema));
    }
}
