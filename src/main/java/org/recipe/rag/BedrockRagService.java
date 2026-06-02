//package org.recipe.rag;
//
//import jakarta.enterprise.context.ApplicationScoped;
//import jakarta.inject.Inject;
//
//import org.eclipse.microprofile.config.inject.ConfigProperty;
//
//import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
//import software.amazon.awssdk.services.bedrockagentruntime.model.*;
//
//@ApplicationScoped
//public class BedrockRagService {
//
//    @Inject
//    BedrockAgentRuntimeClient client;
//
//    @ConfigProperty(name = "bedrock.kb.id")
//    String kbId;
//
//    public String retrieve(String query) {
//
//        RetrieveRequest request = RetrieveRequest.builder()
//                .knowledgeBaseId(kbId)
//                .retrievalQuery(r -> r.text(query))
//                .retrievalConfiguration(c -> c.vectorSearchConfiguration(v ->
//                        v.numberOfResults(5)))
//                .build();
//
//        RetrieveResponse response = client.retrieve(request);
//
//        StringBuilder context = new StringBuilder();
//
//        for (KnowledgeBaseRetrievalResult result : response.retrievalResults()) {
//
//            if (result.score() != null && result.score() < 0.5) {
//                continue;
//            }
//
//            if (result.content() != null && result.content().text() != null) {
//                context.append(result.content().text()).append("\n");
//            }
//        }
//
//        return context.toString();
//    }
//}
package org.recipe.rag;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import software.amazon.awssdk.services.bedrockagentruntime.BedrockAgentRuntimeClient;
import software.amazon.awssdk.services.bedrockagentruntime.model.KnowledgeBaseRetrievalResult;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveRequest;
import software.amazon.awssdk.services.bedrockagentruntime.model.RetrieveResponse;

@ApplicationScoped
public class BedrockRagService {

    @Inject
    BedrockAgentRuntimeClient client;

    @ConfigProperty(name = "bedrock.kb.id")
    String kbId;

    public String retrieve(String query) {

        RetrieveRequest request =
                RetrieveRequest.builder()
                        .knowledgeBaseId(kbId)
                        .retrievalQuery(r -> r.text(query))
                        .retrievalConfiguration(c ->
                                c.vectorSearchConfiguration(v ->
                                        v.numberOfResults(10)))
                        .build();

        RetrieveResponse response =
                client.retrieve(request);

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