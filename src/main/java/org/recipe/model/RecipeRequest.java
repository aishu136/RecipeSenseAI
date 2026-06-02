package org.recipe.model;


import java.util.List;


public class RecipeRequest {

    public String diet;
    public List<String> ingredients;
    public int servings;
    public String userId;

    public String getDiet() {
        return diet;
    }

    public List<String> getIngredients() {
        return ingredients;
    }

    public int getServings() {
        return servings;
    }

	public String getUserId() {
		return userId;
	}

	
}