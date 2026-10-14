package org.recipe.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.recipe.memory.UserProfileIndex.SimilarUser;

/**
 * What GET /users/{userId}/similar returns: how many other users have
 * restrictions like the caller's, and the restrictions they share. It never
 * names those users or shows one user's restrictions: a restriction is only
 * listed when at least MIN_USERS of them have it.
 */
public record SimilarRestrictions(int users, List<Shared> shared) {

    // Fewer users than this per restriction could single someone out
    static final int MIN_USERS = 2;

    // type is "allergy" or "avoid"
    public record Shared(String restriction, String type, int users) { }

    static SimilarRestrictions of(List<SimilarUser> similar) {
        Map<String, Integer> allergies = new TreeMap<>();
        Map<String, Integer> avoid = new TreeMap<>();
        for (SimilarUser user : similar) {
            user.allergies().stream().distinct().forEach(a -> allergies.merge(a, 1, Integer::sum));
            user.avoid().stream().distinct().forEach(a -> avoid.merge(a, 1, Integer::sum));
        }

        List<Shared> shared = new ArrayList<>();
        allergies.forEach((restriction, users) -> shared.add(new Shared(restriction, "allergy", users)));
        avoid.forEach((restriction, users) -> shared.add(new Shared(restriction, "avoid", users)));
        return new SimilarRestrictions(similar.size(), shared.stream()
                .filter(s -> s.users() >= MIN_USERS)
                .sorted(Comparator.comparingInt(Shared::users).reversed())
                .toList());
    }
}
