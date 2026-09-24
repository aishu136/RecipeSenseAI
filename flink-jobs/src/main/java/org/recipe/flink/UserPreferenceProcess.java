package org.recipe.flink;

import java.util.Locale;
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

    private transient MapState<String, Integer> dietCounts;

    @Override
    public void open(OpenContext openContext) {

        searchCounts = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("searchCounts", String.class, Integer.class));

        dietCounts = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("dietCounts", String.class, Integer.class));
    }

    @Override
    public void processElement(
            RecipeSearchEvent event,
            Context context,
            Collector<UserPreference> out)
            throws Exception {

        increment(searchCounts, event.getQuery());

        // Events from before diets were tracked have none; "any" is no diet
        String diet = normalizeDiet(event.getDiet());
        if (diet != null) {
            increment(dietCounts, diet);
        }

        UserPreference pref = new UserPreference();
        pref.setUserId(event.getUserId());
        pref.setFavoriteIngredient(mostFrequent(searchCounts, ""));
        pref.setDietType(mostFrequent(dietCounts, null));

        out.collect(pref);
    }

    // Lower-cased so "Vegan" and "vegan" count as one diet
    static String normalizeDiet(String diet) {
        if (diet == null || diet.isBlank() || diet.trim().equalsIgnoreCase("any")) {
            return null;
        }
        return diet.trim().toLowerCase(Locale.ROOT);
    }

    private static void increment(MapState<String, Integer> counts, String key) throws Exception {
        Integer count = counts.get(key);
        counts.put(key, count == null ? 1 : count + 1);
    }

    private static String mostFrequent(MapState<String, Integer> counts, String none) throws Exception {

        String favorite = none;
        int max = 0;

        for (Map.Entry<String, Integer> entry : counts.entries()) {
            if (entry.getValue() > max) {
                max = entry.getValue();
                favorite = entry.getKey();
            }
        }

        return favorite;
    }
}
