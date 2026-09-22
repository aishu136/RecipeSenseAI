package org.recipe.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

@ExtendWith(MockitoExtension.class)
class BedrockRagServiceTest {

    @Mock
    BedrockAgentRuntimeClient client;

    BedrockRagService service;

    @BeforeEach
    void setUp() {
        service = new BedrockRagService();
        service.client = client;
        service.kbId = "KB123";
    }

    private static KnowledgeBaseRetrievalResult result(Double score, String text) {
        return KnowledgeBaseRetrievalResult.builder()
                .score(score)
                .content(c -> c.text(text))
                .build();
    }

    @Test
    void returnsRelevantPassagesAndSkipsLowScores() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(
                RetrieveResponse.builder()
                        .retrievalResults(
                                result(0.9, "Chickpea curry"),
                                result(0.2, "Unrelated text"),
                                result(null, "Unscored passage"))
                        .build());

        assertEquals("Chickpea curry\n\nUnscored passage\n\n", service.retrieve("vegan curry"));
    }

    @Test
    void queriesTheConfiguredKnowledgeBase() {
        when(client.retrieve(any(RetrieveRequest.class))).thenReturn(RetrieveResponse.builder().build());

        service.retrieve("vegan curry");

        ArgumentCaptor<RetrieveRequest> request = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(client).retrieve(request.capture());
        assertEquals("KB123", request.getValue().knowledgeBaseId());
        assertEquals("vegan curry", request.getValue().retrievalQuery().text());
    }

    @Test
    void returnsEmptyContextWhenBedrockIsUnavailable() {
        when(client.retrieve(any(RetrieveRequest.class)))
                .thenThrow(SdkClientException.create("Unable to load credentials"));

        assertEquals("", service.retrieve("vegan curry"));
    }
}
