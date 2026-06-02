package org.recipe.model;


public class UserPreference {

    private String userId;

    private String favoriteIngredient;

    private String favoriteCuisine;

    private String dietType;

    public String getUserId() {
        return userId;
    }

    public void setUserId(
            String userId) {
        this.userId = userId;
    }

    public String getFavoriteIngredient() {
        return favoriteIngredient;
    }

    public void setFavoriteIngredient(
            String favoriteIngredient) {
        this.favoriteIngredient =
                favoriteIngredient;
    }

    public String getFavoriteCuisine() {
        return favoriteCuisine;
    }

    public void setFavoriteCuisine(
            String favoriteCuisine) {
        this.favoriteCuisine =
                favoriteCuisine;
    }

    public String getDietType() {
        return dietType;
    }

    public void setDietType(
            String dietType) {
        this.dietType = dietType;
    }
}