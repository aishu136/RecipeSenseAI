package org.recipe.flink;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.datastream.DataStream;

import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.sink.KafkaSink;

import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;

import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;

//public class RecipeFlinkJob {
//
//    public static void main(String[] args) throws Exception {
//
//        StreamExecutionEnvironment env =
//                StreamExecutionEnvironment.getExecutionEnvironment();
//
//        // 🔥 SOURCE (Kafka)
//        KafkaSource<String> source = KafkaSource.<String>builder()
//                .setBootstrapServers("localhost:9092")
//                .setTopics("recipe-requests")
//                .setGroupId("flink-group")
//                .setStartingOffsets(OffsetsInitializer.latest())
//                .setValueOnlyDeserializer(new SimpleStringSchema())
//                .build();
//
//        DataStream<String> stream = env.fromSource(
//                source,
//                org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(),
//                "Kafka Source"
//        );
//
//        // 🔥 PROCESSING
//        DataStream<String> processed = stream.map(recipeJson -> {
//
//            // Simulate enrichment
//            String enriched = recipeJson.replace(
//                    "}",
//                    ", \"processedByFlink\": true}"
//            );
//
//            return enriched;
//        });
//
//        // 🔥 SINK (Kafka)
//        KafkaSink<String> sink = KafkaSink.<String>builder()
//                .setBootstrapServers("localhost:9092")
//                .setRecordSerializer(
//                        KafkaRecordSerializationSchema.builder()
//                                .setTopic("recipe-responses")
//                                .setValueSerializationSchema(new SimpleStringSchema())
//                                .build()
//                )
//                .build();
//
//        processed.sinkTo(sink);
//
//        env.execute("Recipe Flink Pipeline");
//    }
public class RecipeFlinkJob {

    public static void main(String[] args) throws Exception {

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment();
        
        KafkaSource<String> source = KafkaSource.<String>builder()
              .setBootstrapServers("localhost:9092")
              .setTopics("recipe-requests")
              .setGroupId("flink-group")
              .setStartingOffsets(OffsetsInitializer.latest())
              .setValueOnlyDeserializer(new SimpleStringSchema())
              .build();


        // Kafka source
        DataStream<String> stream = env.fromSource(
            source,
            WatermarkStrategy.noWatermarks(),
            "Kafka Source"
        );

        // =========================
        // 🔥 PLACE YOUR CODE HERE
        // =========================
        DataStream<String> processed = stream.map(recipe -> {

            int score = 70;

            if (recipe.contains("fried")) score -= 20;
            if (recipe.contains("vegetable")) score += 10;

            String result = recipe.replace("}",
                    ", \"healthScore\": " + score + "}");

            if (score < 60) {
                result = result.replace("}",
                        ", \"needsImprovement\": true}");
            }

            return result;
        });

        // Kafka sink (IMPORTANT)
        processed.sinkTo(
            KafkaSink.<String>builder()
                .setBootstrapServers("localhost:9092")
                .setRecordSerializer(
                    KafkaRecordSerializationSchema.builder()
                        .setTopic("recipe-feedback") // 🔥 IMPORTANT
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build()
                )
                .build()
        );

        env.execute("Recipe Flink Job");
    }
}
