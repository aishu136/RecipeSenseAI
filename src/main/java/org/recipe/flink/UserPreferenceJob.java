package org.recipe.flink;


import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.recipe.model.RecipeSearchEvent;

public class UserPreferenceJob {

    public static void main(String[] args) throws Exception {

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment
                        .getExecutionEnvironment();

        KafkaSource<String> source =
                KafkaSource.<String>builder()
                        .setBootstrapServers(
                                "localhost:9092")
                        .setTopics(
                                "recipe-search-events")
                        .setGroupId(
                                "user-preference-group")
                        .build();

        DataStream<String> kafkaStream =
                env.fromSource(
                        source,
                        WatermarkStrategy
                                .noWatermarks(),
                        "RecipeSearchEvents");

        ObjectMapper mapper =
                new ObjectMapper();

        DataStream<RecipeSearchEvent> events =
                kafkaStream.map(json ->
                        mapper.readValue(
                                json,
                                RecipeSearchEvent.class));

        events
                .keyBy(
                        RecipeSearchEvent::getUserId)
                .process(
                        new UserPreferenceProcess());

        env.execute(
                "User Preference Job");
    }
}