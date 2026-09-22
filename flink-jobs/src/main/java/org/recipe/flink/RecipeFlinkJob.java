package org.recipe.flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Scores generated recipes.
 *
 * Reads {"requestId", "recipe"} messages from recipe-requests and writes
 * {"requestId", "recipe", "healthScore", "needsImprovement"} to recipe-responses,
 * where the Quarkus app matches the result back to the waiting request.
 */
public class RecipeFlinkJob {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {

        String bootstrapServers =
                System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setTopics("recipe-requests")
                .setGroupId("flink-group")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> stream = env.fromSource(
                source,
                WatermarkStrategy.noWatermarks(),
                "Kafka Source");

        DataStream<String> processed = stream.map(RecipeFlinkJob::score);

        processed.sinkTo(
                KafkaSink.<String>builder()
                        .setBootstrapServers(bootstrapServers)
                        .setRecordSerializer(
                                KafkaRecordSerializationSchema.builder()
                                        .setTopic("recipe-responses")
                                        .setValueSerializationSchema(new SimpleStringSchema())
                                        .build())
                        .build());

        env.execute("Recipe Flink Job");
    }

    static String score(String message) throws Exception {

        JsonNode in = MAPPER.readTree(message);
        String recipe = in.path("recipe").asText("");
        String text = recipe.toLowerCase();

        int score = 70;
        if (text.contains("fried")) score -= 20;
        if (text.contains("vegetable")) score += 10;

        ObjectNode out = MAPPER.createObjectNode();
        out.put("requestId", in.path("requestId").asText(null));
        out.put("recipe", recipe);
        out.put("healthScore", score);
        out.put("needsImprovement", score < 60);

        return MAPPER.writeValueAsString(out);
    }
}
