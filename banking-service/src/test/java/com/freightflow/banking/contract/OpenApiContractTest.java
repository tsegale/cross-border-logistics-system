package com.freightflow.banking.contract;

import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * banking-service's API contract is the committed docs/api/banking-openapi.json (ADR 0014). This fails when the
 * served document drifts from it. After a deliberate API change, regenerate it and review the diff:
 * <pre>./mvnw test -Dtest=OpenApiContractTest -Dcontract.update=true</pre>
 */
class OpenApiContractTest extends IntegrationTest {

    static final Path CONTRACT = Path.of("..", "docs", "api", "banking-openapi.json");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servedOpenApiDocumentMatchesTheCommittedContract() throws Exception {
        String served = mockMvc.perform(get("/docs/banking/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String normalised = normalise(served);

        if (Boolean.getBoolean("contract.update")) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, normalised, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(CONTRACT))
                .as("%s is missing; generate it with -Dcontract.update=true", CONTRACT.toAbsolutePath())
                .isTrue();
        assertThat(normalised)
                .as("The served OpenAPI document differs from %s. If the change is intended, regenerate the file "
                        + "with -Dcontract.update=true and update logistics' stubs (ADR 0014).", CONTRACT)
                .isEqualTo(Files.readString(CONTRACT, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    /** Sorted keys, no server URL (it depends on how the test reached the app), LF line endings. */
    static String normalise(String document) {
        JsonNode tree = JSON.readTree(document);
        if (tree instanceof ObjectNode root) {
            root.remove("servers");
        }
        Object sorted = JSON.treeToValue(tree, Object.class);
        return JSON.writeValueAsString(sorted).replace("\r\n", "\n") + "\n";
    }
}
