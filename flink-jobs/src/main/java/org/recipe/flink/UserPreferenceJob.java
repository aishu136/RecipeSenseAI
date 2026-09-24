package org.recipe.flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.recipe.model.RecipeSearchEvent;

/**
 * Tracks each user's most frequent search, diet and cuisine from recipe-search-events
 * and publishes the result to user-preferences.
 */
public class UserPreferenceJob {

    // Ignore fields this job doesn't know, so the app can add fields to
    // search events without breaking a running job
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static void main(String[] args) throws Exception {

        String bootstrapServers =
                System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<String> source =
                KafkaSource.<String>builder()
                        .setBootstrapServers(bootstrapServers)
                        .setTopics("recipe-search-events")
                        .setGroupId("user-preference-group")
                        .setStartingOffsets(OffsetsInitializer.earliest())
                        .setValueOnlyDeserializer(new SimpleStringSchema())
                        .build();

        DataStream<String> kafkaStream =
                env.fromSource(
                        source,
                        WatermarkStrategy.noWatermarks(),
                        "RecipeSearchEvents");

        DataStream<RecipeSearchEvent> events =
                kafkaStream
                        .map(json -> MAPPER.readValue(json, RecipeSearchEvent.class))
                        .filter(event -> event.getUserId() != null && event.getQuery() != null);

        events
                .keyBy(RecipeSearchEvent::getUserId)
                .process(new UserPreferenceProcess())
                .map(MAPPER::writeValueAsString)
                .sinkTo(
                        KafkaSink.<String>builder()
                                .setBootstrapServers(bootstrapServers)
                                .setRecordSerializer(
                                        KafkaRecordSerializationSchema.builder()
                                                .setTopic("user-preferences")
                                                .setValueSerializationSchema(new SimpleStringSchema())
                                                .build())
                                .build());

        env.execute("User Preference Job");
    }
}
