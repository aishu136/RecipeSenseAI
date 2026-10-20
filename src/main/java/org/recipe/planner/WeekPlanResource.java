package org.recipe.planner;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.recipe.api.OwnUser;
import org.recipe.planner.WeekPlan.Slot;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** The signed-in user's weekly planner, a week at a time (see WeekPlan). */
@Authenticated
@Path("/users/{userId}/week/{week}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class WeekPlanResource {

    static final List<String> MEALS = List.of("breakfast", "lunch", "dinner");
    static final int MAX_TITLE = 200;
    static final int MAX_URL = 2000;

    public record WeekRequest(List<Slot> slots) { }

    @Inject
    WeekPlanStore store;

    @Inject
    SecurityIdentity identity;

    @GET
    public WeekPlan get(@PathParam("userId") String userId, @PathParam("week") String week) {
        return store.find(OwnUser.require(identity, userId), monday(week));
    }

    // Replaces the week's plan; no slots clears it
    @PUT
    public WeekPlan put(@PathParam("userId") String userId, @PathParam("week") String week, WeekRequest request) {
        String owner = OwnUser.require(identity, userId);
        WeekPlan plan = new WeekPlan(monday(week), validate(request == null ? null : request.slots()));
        store.save(owner, plan);
        return plan;
    }

    static String monday(String week) {
        try {
            LocalDate date = LocalDate.parse(week);
            if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
                return date.toString();
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        throw new BadRequestException("week must be a Monday, as YYYY-MM-DD");
    }

    static List<Slot> validate(List<Slot> slots) {
        if (slots == null) {
            throw new BadRequestException("slots is required");
        }
        if (slots.size() > 7 * MEALS.size()) {
            throw new BadRequestException("A week has at most " + 7 * MEALS.size() + " meals");
        }
        Set<String> taken = new HashSet<>();
        for (Slot slot : slots) {
            if (slot == null) {
                throw new BadRequestException("slots must not contain null");
            }
            if (slot.day() < 0 || slot.day() > 6) {
                throw new BadRequestException("day must be 0 (Monday) to 6 (Sunday)");
            }
            if (!MEALS.contains(slot.meal())) {
                throw new BadRequestException("meal must be one of " + String.join(", ", MEALS));
            }
            if (!taken.add(slot.day() + "/" + slot.meal())) {
                throw new BadRequestException("Only one " + slot.meal() + " a day");
            }
            boolean saved = slot.recipeId() != null && !slot.recipeId().isBlank();
            boolean fromApi = slot.spoonacularId() != null && slot.spoonacularId() > 0;
            if (saved == fromApi) {
                throw new BadRequestException("Each meal needs a recipeId or a spoonacularId");
            }
            if (slot.title() == null || slot.title().isBlank() || slot.title().length() > MAX_TITLE) {
                throw new BadRequestException("Each meal needs a title of at most " + MAX_TITLE + " characters");
            }
            requireWebLink(slot.url(), "url");
            requireWebLink(slot.image(), "image");
        }
        return slots;
    }

    // The page links to these, so only web links (no javascript: URLs)
    private static void requireWebLink(String url, String field) {
        if (url != null && (url.length() > MAX_URL || !url.matches("(?i)^https?://\\S+$"))) {
            throw new BadRequestException(field + " must be an http(s) link");
        }
    }
}
