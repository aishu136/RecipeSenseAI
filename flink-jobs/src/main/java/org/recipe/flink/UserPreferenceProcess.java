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

    private transient MapState<String, Integer> cuisineCounts;

    @Override
    public void open(OpenContext openContext) {

        searchCounts = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("searchCounts", String.class, Integer.class));

        dietCounts = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("dietCounts", String.class, Integer.class));

        cuisineCounts = getRuntimeContext().getMapState(
                new MapStateDescriptor<>("cuisineCounts", String.class, Integer.class));
    }

    @Override
    public void processElement(
            RecipeSearchEvent event,
            Context context,
            Collector<UserPreference> out)
            throws Exception {

        increment(searchCounts, event.getQuery());

        // Older events have no diet or cuisine; "any" means none was chosen
        String diet = normalize(event.getDiet());
        if (diet != null) {
            increment(dietCounts, diet);
        }

        String cuisine = normalize(event.getCuisine());
        if (cuisine != null) {
            increment(cuisineCounts, cuisine);
        }

        UserPreference pref = new UserPreference();
        pref.setUserId(event.getUserId());
        pref.setFavoriteIngredient(mostFrequent(searchCounts, ""));
        pref.setDietType(mostFrequent(dietCounts, null));
        pref.setFavoriteCuisine(mostFrequent(cuisineCounts, null));

        out.collect(pref);
    }

    // Lower-cased so "Vegan" and "vegan" count as one diet (or cuisine)
    static String normalize(String value) {
        if (value == null || value.isBlank() || value.trim().equalsIgnoreCase("any")) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
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
