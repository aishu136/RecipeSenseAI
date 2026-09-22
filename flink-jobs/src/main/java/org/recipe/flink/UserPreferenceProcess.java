package org.recipe.flink;

import java.util.Map;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import org.recipe.model.RecipeSearchEvent;
import org.recipe.model.UserPreference;

public class UserPreferenceProcess
        extends KeyedProcessFunction<String, RecipeSearchEvent, UserPreference> {

    private transient MapState<String, Integer> searchCounts;

    @Override
    public void open(OpenContext openContext) {

        searchCounts = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("searchCounts", String.class, Integer.class));
    }

    @Override
    public void processElement(
            RecipeSearchEvent event,
            Context context,
            Collector<UserPreference> out)
            throws Exception {

        String query = event.getQuery();

        Integer count = searchCounts.get(query);
        searchCounts.put(query, count == null ? 1 : count + 1);

        UserPreference pref = new UserPreference();
        pref.setUserId(event.getUserId());
        pref.setFavoriteIngredient(getMostFrequentSearch());

        out.collect(pref);
    }

    private String getMostFrequentSearch() throws Exception {

        String favorite = "";
        int max = 0;

        for (Map.Entry<String, Integer> entry : searchCounts.entries()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                favorite = entry.getKey();
            }
        }

        return favorite;
    }
}
