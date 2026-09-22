package org.recipe.rag;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

@ApplicationScoped
public class BedrockRagService {

    private static final Logger LOG = Logger.getLogger(BedrockRagService.class);

    @Inject
    BedrockAgentRuntimeClient client;

    @ConfigProperty(name = "bedrock.kb.id")
    String kbId;

    /**
     * Returns matching knowledge-base passages, or an empty string if the
     * knowledge base can't be reached. RAG only enriches the prompt, so an
     * outage shouldn't fail recipe generation.
     */
    public String retrieve(String query) {

        RetrieveRequest request =
                RetrieveRequest.builder()
                        .knowledgeBaseId(kbId)
                        .retrievalQuery(r -> r.text(query))
                        .retrievalConfiguration(c ->
                                c.vectorSearchConfiguration(v ->
                                        v.numberOfResults(10)))
                        .build();

        RetrieveResponse response;
        try {
            response = client.retrieve(request);
        } catch (SdkException e) {
            LOG.warnf("Bedrock knowledge base retrieval failed: %s", e.getMessage());
            return "";
        }

        StringBuilder context =
                new StringBuilder();

        for (KnowledgeBaseRetrievalResult result :
                response.retrievalResults()) {

            if (result.score() != null &&
                result.score() < 0.50) {
                continue;
            }

            if (result.content() != null &&
                result.content().text() != null) {

                context.append(result.content().text())
                        .append("\n\n");
            }
        }

        return context.toString();
    }
}
