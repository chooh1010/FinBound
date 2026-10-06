package io.finguard.core.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/** 기록한 이벤트가 계약(contracts/events/finguard-event-v2.schema.json)을 지키는지 본다. */
final class EventContract {

    private static final Schema SCHEMA = load();

    private EventContract() {
    }

    static void assertSatisfiesContract(String eventJson) {
        assertThat(SCHEMA.validate(eventJson, InputFormat.JSON,
                        context -> context.executionConfig(config -> config.formatAssertionsEnabled(true))))
                .as(eventJson)
                .isEmpty();
    }

    private static Schema load() {
        Path schemaFile = Path.of(System.getProperty("finguard.repository.root"),
                "contracts", "events", "finguard-event-v2.schema.json");
        try {
            Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(Files.readString(schemaFile));
            schema.initializeValidators();
            return schema;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
