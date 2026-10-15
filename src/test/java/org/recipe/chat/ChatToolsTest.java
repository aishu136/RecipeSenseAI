package org.recipe.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.billing.BillingConfig;
import org.recipe.billing.Plan;
import org.recipe.billing.StripePrices;
import org.recipe.billing.StripePrices.PlanPrice;
import org.recipe.billing.Subscriptions;
import org.recipe.billing.UserSubscription;
import org.recipe.cooking.CookingService;
import org.recipe.cooking.CookingService.Substitute;
import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;
import org.recipe.cooking.RecipeJson;
import org.recipe.saved.SavedRecipe;
import org.recipe.saved.SavedRecipeStore;
import org.recipe.saved.SavedRecipes;
import org.recipe.model.DietaryRestrictions;
import org.recipe.model.RecipeRequest;
import org.recipe.service.FreeRecipeQuota.FreeRecipes;
import org.recipe.service.RecipeAIService;
import org.recipe.service.RecipeAllowance;
import org.recipe.service.UserRestrictionStore;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

@ExtendWith(MockitoExtension.class)
class ChatToolsTest {

    static final String GUEST = "11111111-2222-3333-4444-555555555555";
    static final String RECIPE = "{\"recipeName\":\"Tomato Rice\"}";
    static final String MEMORY = "user:alice:c1";
    static final RecipeData RICE = new RecipeData("Tomato Rice",
            List.of(new Ingredient(1.0, "cup", "rice"), new Ingredient(2.0, null, "tomatoes")),
            List.of("Cook the rice"), 420, 2, 10, 20, "easy", List.of("quick"));

    @Mock
    RecipeAllowance allowance;

    @Mock
    UserRestrictionStore restrictions;

    @Mock
    RecipeAIService recipes;

    @Mock
    Subscriptions subscriptions;

    @Mock
    BillingConfig billing;

    @Mock
    StripePrices prices;

    @Mock
    Runnable giveBack;

    @Mock
    CookingService cooking;

    @Mock
    LastChatRecipe lastRecipe;

    @Mock
    SavedRecipes saving;

    @Mock
    SavedRecipeStore saved;

    ChatContext context;
    ChatTools tools;

    @BeforeEach
    void setUp() {
        context = new ChatContext();
        context.userId = "alice";
        context.guestId = GUEST;
        context.memoryId = MEMORY;
        context.language = "en";
        tools = new ChatTools();
        tools.context = context;
        tools.allowance = allowance;
        tools.restrictions = restrictions;
        tools.recipes = recipes;
        tools.subscriptions = subscriptions;
        tools.billing = billing;
        tools.prices = prices;
        tools.cooking = cooking;
        tools.lastRecipe = lastRecipe;
        tools.saving = saving;
        tools.saved = saved;
        lenient().when(restrictions.find(any())).thenReturn(DietaryRestrictions.NONE);
        lenient().when(billing.trialDays()).thenReturn(7);
    }

    static WebApplicationException refusal(int status, String message) {
        return new WebApplicationException(Response.status(status).entity(message).build());
    }

    @Test
    void makesARecipeOnTheUsersPlanAndShowsItAsACard() {
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(true, giveBack));
        when(recipes.generateRecipe(any())).thenReturn(RECIPE);

        String result = tools.generateRecipe(List.of("rice", "tomato"), "vegetarian", 2, "indian", null, null, null, null);

        assertTrue(result.contains(RECIPE));
        assertEquals(RECIPE, context.recipe);
        verify(recipes).generateRecipe(argThat(r -> r.personalised && "alice".equals(r.userId)
                && r.ingredients.equals(List.of("rice", "tomato")) && "vegetarian".equals(r.diet)
                && "indian".equals(r.cuisine) && r.servings == 2));
        verify(giveBack, never()).run();
    }

    @Test
    void fillsInDefaultsTheModelLeftOut() {
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(false, giveBack));
        when(recipes.generateRecipe(any())).thenReturn(RECIPE);

        tools.generateRecipe(List.of("rice"), " ", 0, "", null, null, null, null);

        verify(recipes).generateRecipe(argThat(r -> "any".equals(r.diet) && r.servings == 2 && r.cuisine == null
                && !r.personalised));
    }

    @Test
    void savedAllergiesApplyAndBlockConflictingIngredients() {
        when(restrictions.find("alice")).thenReturn(DietaryRestrictions.of(List.of("peanut"), List.of()));

        String result = tools.generateRecipe(List.of("peanut butter"), "any", 2, null, null, null, null, null);

        assertTrue(result.contains("conflicts with the restriction peanut"), result);
        verifyNoInteractions(allowance, recipes);
        assertNull(context.recipe);
    }

    @Test
    void allergiesFromTheConversationApply() {
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(true, giveBack));
        when(recipes.generateRecipe(any())).thenReturn(RECIPE);

        tools.generateRecipe(List.of("rice"), "any", 2, null, List.of("sesame"), List.of("mushroom"), null, null);

        verify(recipes).generateRecipe(argThat((RecipeRequest r) -> r.restrictions().allergies().equals(List.of("sesame"))
                && r.restrictions().avoid().equals(List.of("mushroom"))));
    }

    @Test
    void whenTheFreeRecipesAreUsedUpItOffersThePlans() {
        String why = "You've used your 4 free recipes. Start your 7-day free trial.";
        when(allowance.open("alice", GUEST)).thenThrow(refusal(402, why));

        String result = tools.generateRecipe(List.of("rice"), "any", 2, null, null, null, null, null);

        assertEquals("subscribe", context.needs);
        assertEquals(why, context.needsMessage);
        assertTrue(result.startsWith("Refused: " + why));
        verifyNoInteractions(recipes);
    }

    @Test
    void guestsOutOfFreeRecipesAreAskedToSignIn() {
        context.userId = null;
        when(allowance.open(null, GUEST)).thenThrow(refusal(401, "Sign in to keep generating recipes."));

        tools.generateRecipe(List.of("rice"), "any", 2, null, null, null, null, null);

        assertEquals("signIn", context.needs);
    }

    @Test
    void aFailedRecipeIsGivenBack() {
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(false, giveBack));
        when(recipes.generateRecipe(any())).thenThrow(new IllegalStateException("model down"));

        String result = tools.generateRecipe(List.of("rice"), "any", 2, null, null, null, null, null);

        verify(giveBack).run();
        assertTrue(result.contains("couldn't be made"));
        assertNull(context.recipe);
    }

    @Test
    void describesTheUsersPlan() {
        when(billing.enabled()).thenReturn(true);
        when(allowance.freeRecipes("alice", GUEST)).thenReturn(new FreeRecipes(4, 1));
        when(subscriptions.find("alice")).thenReturn(Optional.empty());
        when(subscriptions.trialAvailable("alice")).thenReturn(true);
        when(prices.price(Plan.MONTHLY)).thenReturn(new PlanPrice("monthly", 499, "usd", "month"));
        when(prices.price(Plan.YEARLY)).thenReturn(new PlanPrice("yearly", 4999, "usd", "year"));

        String plan = tools.myPlan();

        assertTrue(plan.contains("They have 1 of 4 free recipes left."), plan);
        assertTrue(plan.contains("They don't have a subscription."), plan);
        assertTrue(plan.contains("Their 7-day free trial is available."), plan);
        assertTrue(plan.contains("monthly 4.99 USD, yearly 49.99 USD"), plan);
    }

    @Test
    void describesASubscribersTrial() {
        when(billing.enabled()).thenReturn(true);
        when(allowance.freeRecipes("alice", GUEST)).thenReturn(FreeRecipes.UNLIMITED);
        when(subscriptions.find("alice")).thenReturn(Optional.of(
                new UserSubscription("alice", "cus_1", "sub_1", "trialing", "yearly", 1L, 2L, false, 1)));
        when(subscriptions.trialAvailable("alice")).thenReturn(false);
        when(prices.price(any())).thenReturn(new PlanPrice("monthly", 499, "usd", "month"));

        String plan = tools.myPlan();

        assertTrue(plan.contains("unlimited recipes"), plan);
        assertTrue(plan.contains("free trial of the yearly plan"), plan);
        assertFalse(plan.contains("free trial is available"), plan);
    }

    @Test
    void saysSoWhenSubscriptionsArentAvailable() {
        when(billing.enabled()).thenReturn(false);
        context.userId = null;
        when(allowance.freeRecipes(null, GUEST)).thenReturn(new FreeRecipes(4, 4));

        String plan = tools.myPlan();

        assertTrue(plan.contains("isn't signed in"), plan);
        assertTrue(plan.contains("Subscriptions aren't available"), plan);
        verifyNoInteractions(prices, subscriptions);
    }

    @Test
    void keepsTheRecipeForFollowUpsAndWritesItInTheChatsLanguage() {
        context.language = "es";
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(true, giveBack));
        when(recipes.generateRecipe(any())).thenReturn(RECIPE);

        tools.generateRecipe(List.of("rice"), "any", 2, null, null, null, 30, "easy");

        verify(recipes).generateRecipe(argThat(r -> "es".equals(r.language) && r.maxMinutes == 30
                && "easy".equals(r.difficulty)));
        verify(lastRecipe).set(eq(MEMORY), argThat(r -> "Tomato Rice".equals(r.recipeName())));
    }

    @Test
    void changesTheServingsOfTheLastRecipe() {
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.of(RICE));

        String result = tools.changeServings(6);

        RecipeData shown = RecipeJson.parse(context.recipe).orElseThrow();
        assertEquals(6, shown.servings());
        assertEquals(3.0, shown.ingredients().get(0).quantity());
        assertTrue(result.contains("3 cup rice"), result);
        verify(lastRecipe).set(eq(MEMORY), argThat(r -> r.servings() == 6));
        verifyNoInteractions(allowance);
    }

    @Test
    void toolsForTheLastRecipeSaySoWhenThereIsNone() {
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.empty());

        assertEquals(ChatTools.NO_RECIPE, tools.changeServings(4));
        assertEquals(ChatTools.NO_RECIPE, tools.customizeRecipe("spicier"));
        assertEquals(ChatTools.NO_RECIPE, tools.saveRecipe());
        assertEquals(ChatTools.NO_RECIPE, tools.shoppingList(null));
        assertNull(context.recipe);
    }

    @Test
    void customisesTheLastRecipeOnTheUsersPlan() {
        RecipeData spicy = new RecipeData("Spicy Tomato Rice", RICE.ingredients(), RICE.instructions(), 430, 2,
                10, 20, "easy", List.of("spicy"));
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.of(RICE));
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(false, giveBack));
        when(cooking.customize(anyString(), eq("make it spicier"), eq(DietaryRestrictions.NONE), eq("en")))
                .thenReturn(spicy);

        tools.customizeRecipe("make it spicier");

        assertEquals("Spicy Tomato Rice", RecipeJson.parse(context.recipe).orElseThrow().recipeName());
        verify(lastRecipe).set(MEMORY, spicy);
        verify(giveBack, never()).run();
    }

    @Test
    void aRefusedCustomisationIsGivenBack() {
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.of(RICE));
        when(allowance.open("alice", GUEST)).thenReturn(new RecipeAllowance.Access(false, giveBack));
        when(cooking.customize(anyString(), anyString(), any(), any()))
                .thenThrow(new BadRequestException("That change conflicts with the restriction peanut"));

        String result = tools.customizeRecipe("add peanuts");

        verify(giveBack).run();
        assertTrue(result.contains("conflicts with the restriction peanut"), result);
        assertNull(context.recipe);
    }

    @Test
    void suggestsSubstitutesWithTheRecipeForContext() {
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.of(RICE));
        when(cooking.substitutes("egg", "Tomato Rice", null, DietaryRestrictions.NONE, "en"))
                .thenReturn(List.of(new Substitute("flaxseed", "1 tbsp + 3 tbsp water", "nutty")));

        String result = tools.suggestSubstitutes("egg");

        assertTrue(result.contains("- flaxseed: 1 tbsp + 3 tbsp water (nutty)"), result);
    }

    @Test
    void savesTheLastRecipeForSubscribers() {
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.of(RICE));

        String result = tools.saveRecipe();

        verify(saving).save("alice", RICE, null, null, "en");
        assertTrue(result.contains("/recipes"), result);
    }

    @Test
    void savingWithoutASubscriptionOffersThePlans() {
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.of(RICE));
        when(saving.save(any(), any(), any(), any(), any()))
                .thenThrow(refusal(402, "Saving recipes comes with a subscription."));

        tools.saveRecipe();

        assertEquals("subscribe", context.needs);
        assertEquals("Saving recipes comes with a subscription.", context.needsMessage);
    }

    @Test
    void guestsAreAskedToSignInToSave() {
        context.userId = null;
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.of(RICE));

        tools.saveRecipe();

        assertEquals("signIn", context.needs);
        verifyNoInteractions(saving);
    }

    @Test
    void makesAShoppingListForTheLastRecipe() {
        when(lastRecipe.find(MEMORY)).thenReturn(Optional.of(RICE));

        tools.shoppingList(null);

        assertEquals(List.of("1 cup rice", "2 tomatoes"),
                context.shoppingList.stream().map(item -> item.text()).toList());
    }

    @Test
    void makesAShoppingListForSavedRecipesByName() {
        RecipeData soup = new RecipeData("Tomato Soup", List.of(new Ingredient(3.0, null, "tomatoes")),
                List.of(), null, 2, null, null, null, null);
        when(saved.list("alice")).thenReturn(List.of(
                new SavedRecipe("1", RICE, null, null, "en", 2, null),
                new SavedRecipe("2", soup, null, null, "en", 1, null)));

        String result = tools.shoppingList(List.of("rice", "soup", "curry"));

        assertTrue(context.shoppingList.stream().anyMatch(item -> item.text().equals("5 tomatoes")),
                context.shoppingList.toString());
        assertTrue(result.contains("weren't found in their saved recipes: curry"), result);
    }

    @Test
    void findsSavedRecipesWithFilters() {
        RecipeData slow = new RecipeData("Slow Stew", List.of(), List.of(), 600, 4, 30, 120, "hard", null);
        when(saved.list("alice")).thenReturn(List.of(
                new SavedRecipe("1", RICE, null, null, "en", 2, null),
                new SavedRecipe("2", slow, null, null, "en", 1, null)));

        String result = tools.findSavedRecipes(null, null, 45, null, null);

        assertTrue(result.contains("Tomato Rice (30 min, 420 kcal, easy)"), result);
        assertFalse(result.contains("Slow Stew"), result);
    }

    @Test
    void guestsHaveNoSavedRecipes() {
        context.userId = null;

        assertTrue(tools.findSavedRecipes("rice", null, null, null, null).startsWith("Guests"));
        verifyNoInteractions(saved);
    }
}
