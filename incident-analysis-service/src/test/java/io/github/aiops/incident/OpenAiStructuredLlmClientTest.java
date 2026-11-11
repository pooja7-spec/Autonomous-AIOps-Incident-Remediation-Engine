package io.github.aiops.incident;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aiops.incident.llm.FunctionTool;
import io.github.aiops.incident.llm.LlmIntegrationException;
import io.github.aiops.incident.llm.OpenAiStructuredLlmClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiStructuredLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesHostToolThenReturnsStructuredJson() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiStructuredLlmClient client = new OpenAiStructuredLlmClient(
                builder,
                objectMapper,
                "http://localhost/v1",
                "test-key",
                "test-model"
        );
        AtomicBoolean toolExecuted = new AtomicBoolean();
        FunctionTool tool = new FunctionTool(
                "query_system_logs",
                "Query logs",
                objectMapper.createObjectNode(),
                arguments -> {
                    toolExecuted.set(arguments.path("service").asText().equals("payment-service"));
                    return objectMapper.createObjectNode().put("count", 1);
                }
        );

        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": null,
                              "tool_calls": [{
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                  "name": "query_system_logs",
                                  "arguments": "{\\"service\\":\\"payment-service\\"}"
                                }
                              }]
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": "{\\"isHealthy\\":false,\\"summary\\":\\"Errors found.\\"}"
                            }
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        JsonNode result = client.completeJson(
                "system",
                "user",
                "detection_result",
                objectMapper.createObjectNode(),
                List.of(tool),
                true
        );

        assertTrue(toolExecuted.get());
        assertEquals(false, result.path("isHealthy").asBoolean());
        assertEquals("Errors found.", result.path("summary").asText());
        server.verify();
    }

    @Test
    void failsClearlyWhenApiKeyIsMissing() {
        OpenAiStructuredLlmClient client = new OpenAiStructuredLlmClient(
                RestClient.builder(),
                objectMapper,
                "http://localhost/v1",
                "",
                "test-model"
        );

        assertThrows(
                LlmIntegrationException.class,
                () -> client.completeJson(
                        "system",
                        "user",
                        "result",
                        objectMapper.createObjectNode(),
                        List.of(),
                        false
                )
        );
    }
}
