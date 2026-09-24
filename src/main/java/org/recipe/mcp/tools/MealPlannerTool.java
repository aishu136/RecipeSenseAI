package org.recipe.mcp.tools;



import org.recipe.mcp.McpTool;
import org.recipe.model.MealPlanRequest;
import org.recipe.service.MealPlanService;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;


@ApplicationScoped
public class MealPlannerTool implements McpTool {

    @Inject
    MealPlanService mealPlans;

    @Inject
    ObjectMapper mapper;

    @Override
    public String name() {
        return "meal-planner";
    }

    // Input is the diet, e.g. "vegetarian" (blank for any); returns a
    // DEFAULT_DAYS plan of real recipes as JSON
    @Override
    public String execute(String input) {
        MealPlanRequest request = new MealPlanRequest();
        request.diet = input;

        try {
            return mapper.writeValueAsString(mealPlans.plan(request));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise meal plan", e);
        }
    }
}
