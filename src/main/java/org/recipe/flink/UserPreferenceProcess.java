package org.recipe.flink;


import java.util.HashMap;
import java.util.Map;

import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import org.recipe.model.RecipeSearchEvent;
import org.recipe.model.UserPreference;

public class UserPreferenceProcess
extends KeyedProcessFunction<
        String,
        RecipeSearchEvent,
        UserPreference> {

    private transient MapState<String,Integer>
            searchCounts;



    @Override
    public void processElement(
            RecipeSearchEvent event,
            Context context,
            Collector<UserPreference> out)
            throws Exception {

        if (searchCounts == null) {

            MapStateDescriptor<String,Integer>
                    descriptor =
                    new MapStateDescriptor<>(
                            "searchCounts",
                            String.class,
                            Integer.class);

            searchCounts =
                    getRuntimeContext()
                            .getMapState(descriptor);
        }

        String query =
                event.getQuery();

        Integer count =
                searchCounts.get(query);

        if (count == null) {
            count = 0;
        }

        searchCounts.put(
                query,
                count + 1);

        UserPreference pref =
                new UserPreference();

        pref.setUserId(
                event.getUserId());

        pref.setFavoriteIngredient(
                query);

        out.collect(pref);
    }

    private String getMostFrequentSearch()
            throws Exception {

        String favorite = "";
        int max = 0;

        for (Map.Entry<String,Integer> entry :
                searchCounts.entries()) {

            if (entry.getValue() > max) {

                max = entry.getValue();

                favorite =
                        entry.getKey();
            }
        }

        return favorite;
    }
}
