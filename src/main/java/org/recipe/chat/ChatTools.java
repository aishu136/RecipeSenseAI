package org.recipe.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jboss.logging.Logger;
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
import org.recipe.cooking.ShoppingList;
import org.recipe.model.DietaryRestrictions;
import org.recipe.model.RecipeRequest;
import org.recipe.saved.RecipeFilter;
import org.recipe.saved.SavedRecipe;
import org.recipe.saved.SavedRecipeStore;
import org.recipe.saved.SavedRecipes;
import org.recipe.service.FreeRecipeQuota.FreeRecipes;
import org.recipe.service.RecipeAIService;
import org.recipe.service.RecipeAllowance;
import org.recipe.service.UserRestrictionStore;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

/**
 * What the chat assistant can do for the person chatting (ChatContext): make
 * them a recipe on the same terms as the recipe page, change, scale, save and
 * shop for the last one (LastChatRecipe), suggest substitutes, search their
 * saved recipes, and tell them about their plan. Results are text for the
 * model to talk about.
 */
@ApplicationScoped
public class ChatTools {

    private static final Logger LOG = Logger.getLogger(ChatTools.class);

    static final String NO_RECIPE = "There's no recipe in this conversation yet. Ask what they'd like to cook, "
            + "then use generateRecipe.";

    // What a recipe that doesn't say how many it serves is taken to serve
    static final int DEFAULT_SERVINGS = 2;

    @Inject
    ChatContext context;

    @Inject
    RecipeAllowance allowance;

    @Inject
    UserRestrictionStore restrictions;

    @Inject
    RecipeAIService recipes;

    @Inject
    CookingService cooking;

    @Inject
    LastChatRecipe lastRecipe;

    @Inject
    SavedRecipes saving;

    @Inject
    SavedRecipeStore saved;

    @Inject
    Subscriptions subscriptions;

    @Inject
    BillingConfig billing;

    @Inject
    StripePrices prices;

    @Tool("""
            Generates a recipe for the user from their ingredients and shows it to them as a recipe card.
            Use it whenever they ask for a recipe or say what ingredients they have. It applies their saved
            allergies, personalises it when their plan includes that, and counts it against their free recipes.
            """)
    public String generateRecipe(
            @P("Ingredients to cook with, e.g. [\"rice\", \"tomato\"]") List<String> ingredients,
            @P("Diet, e.g. vegetarian, vegan, keto; \"any\" if they didn't say") String diet,
            @P("Number of servings; 2 if they didn't say") int servings,
            @P(value = "Cuisine, e.g. italian; empty if they didn't say", required = false) String cuisine,
            @P(value = "Ingredients they're allergic to, from this conversation", required = false) List<String> allergies,
            @P(value = "Ingredients they don't eat, from this conversation", required = false) List<String> avoid,
            @P(value = "Most minutes it may take in total, if they said", required = false) Integer maxMinutes,
            @P(value = "easy, medium or hard, if they said", required = false) String difficulty) {

        RecipeRequest request = new RecipeRequest();
        request.ingredients = ingredients == null ? List.of() : ingredients;
        request.diet = diet == null || diet.isBlank() ? "any" : diet.trim();
        request.servings = servings > 0 ? servings : DEFAULT_SERVINGS;
        request.cuisine = cuisine == null || cuisine.isBlank() ? null : cuisine.trim();
        request.allergies = allergies;
        request.avoid = avoid;
        request.maxMinutes = maxMinutes != null && maxMinutes > 0 ? maxMinutes : null;
        request.difficulty = difficulty == null || difficulty.isBlank() ? null : difficulty.trim();
        request.language = context.language;
        request.userId = context.userId;

        RecipeAllowance.Access access;
        try {
            // Their saved allergies apply too, and are checked against the ingredients
            request.addRestrictions(restrictions.find(context.userId));
            request.validate();
            access = allowance.open(context.userId, context.guestId);
        } catch (WebApplicationException e) {
            return refused(e);
        }

        request.personalised = access.personalised();
        try {
            String recipe = recipes.generateRecipe(request);
            show(recipe);
            return "The recipe is shown to the user as a card. Introduce it in a sentence or two; don't repeat it "
                    + "in full. The recipe: " + recipe;
        } catch (RuntimeException e) {
            access.giveBack().run();
            LOG.warnf("Chat recipe failed: %s", e.getMessage());
            return "The recipe couldn't be made right now (the recipe model failed). Apologise and suggest "
                    + "trying again in a moment.";
        }
    }

    @Tool("""
            Changes the number of servings of the last recipe in this conversation, scaling its ingredients,
            and shows the scaled recipe as a card. Doesn't use up a free recipe.
            """)
    public String changeServings(@P("The number of servings they want") int servings) {
        Optional<RecipeData> recipe = lastRecipe.find(context.memoryId);
        if (recipe.isEmpty()) {
            return NO_RECIPE;
        }
        if (servings <= 0 || servings > 100) {
            return "Servings must be between 1 and 100. Ask them how many people they're cooking for.";
        }
        RecipeData scaled = recipe.get().withServings(servings, DEFAULT_SERVINGS);
        show(RecipeJson.write(scaled));
        return "The recipe for " + servings + " servings is shown as a card. Mention a few of the new amounts: "
                + String.join("; ", scaled.ingredients().stream().limit(4).map(Ingredient::text).toList()) + ".";
    }

    @Tool("""
            Changes the last recipe in this conversation as the user asks, e.g. "make it vegan", "spicier",
            "swap chicken for tofu", "without an oven", and shows the changed recipe as a card. Keeps to their
            allergies. Counts against their free recipes, like a new recipe.
            """)
    public String customizeRecipe(@P("The change they asked for, in their words") String change) {
        Optional<RecipeData> recipe = lastRecipe.find(context.memoryId);
        if (recipe.isEmpty()) {
            return NO_RECIPE;
        }
        RecipeAllowance.Access access;
        DietaryRestrictions saved;
        try {
            saved = restrictions.find(context.userId);
            access = allowance.open(context.userId, context.guestId);
        } catch (WebApplicationException e) {
            return refused(e);
        }
        try {
            RecipeData changed = cooking.customize(RecipeJson.write(recipe.get()), change, saved, context.language);
            String json = RecipeJson.write(changed);
            show(json);
            return "The changed recipe is shown as a card. Say briefly what changed. The recipe: " + json;
        } catch (WebApplicationException e) {
            access.giveBack().run();
            return refused(e);
        } catch (RuntimeException e) {
            access.giveBack().run();
            LOG.warnf("Chat customisation failed: %s", e.getMessage());
            return "The recipe couldn't be changed right now. Apologise and suggest trying again in a moment.";
        }
    }

    @Tool("""
            Suggests substitutes for an ingredient (one they don't have, or don't eat), keeping to their
            allergies, with how much to use. Uses the last recipe in this conversation for context, if any.
            """)
    public String suggestSubstitutes(@P("The ingredient to replace, e.g. \"egg\"") String ingredient) {
        try {
            String recipeName = lastRecipe.find(context.memoryId).map(RecipeData::recipeName).orElse(null);
            List<Substitute> found = cooking.substitutes(ingredient, recipeName, null,
                    restrictions.find(context.userId), context.language);
            if (found.isEmpty()) {
                return "No substitutes for " + ingredient + " fit their restrictions. Say so, and suggest leaving it "
                        + "out or a different recipe.";
            }
            List<String> lines = found.stream()
                    .map(s -> "- " + s.name() + (s.amount().isEmpty() ? "" : ": " + s.amount())
                            + (s.note().isEmpty() ? "" : " (" + s.note() + ")"))
                    .toList();
            return "Substitutes for " + ingredient + ":\n" + String.join("\n", lines)
                    + "\nShare them as a short list. If they want one used, call customizeRecipe.";
        } catch (WebApplicationException e) {
            return refused(e);
        }
    }

    @Tool("""
            Saves the last recipe in this conversation to the user's recipes (the My recipes page).
            Saving comes with a subscription.
            """)
    public String saveRecipe() {
        Optional<RecipeData> recipe = lastRecipe.find(context.memoryId);
        if (recipe.isEmpty()) {
            return NO_RECIPE;
        }
        if (context.userId == null) {
            context.needs = "signIn";
            context.needsMessage = "Sign in to save recipes.";
            return "Not saved: guests can't save recipes. Tell the user kindly; a sign-in button is shown below your reply.";
        }
        try {
            saving.save(context.userId, recipe.get(), null, null, context.language);
            return "Saved \"" + recipe.get().recipeName() + "\" to their recipes; they're on the [My recipes](/recipes) page.";
        } catch (WebApplicationException e) {
            return refused(e);
        }
    }

    @Tool("""
            Makes a shopping list and shows it to the user as a checklist: for the last recipe in this
            conversation, or for some of their saved recipes when they name them.
            """)
    public String shoppingList(
            @P(value = "Names of their saved recipes to shop for; empty for the last recipe here", required = false)
            List<String> savedRecipeNames) {
        List<ShoppingList.Entry> entries = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (savedRecipeNames != null && !savedRecipeNames.isEmpty()) {
            if (context.userId == null) {
                return "Guests don't have saved recipes. Offer a shopping list for the recipe in this conversation.";
            }
            List<SavedRecipe> all;
            try {
                all = saved.list(context.userId);
            } catch (WebApplicationException e) {
                return refused(e);
            }
            for (String name : savedRecipeNames) {
                Optional<SavedRecipe> match = all.stream()
                        .filter(s -> s.recipe().recipeName().toLowerCase().contains(name.toLowerCase().trim()))
                        .findFirst();
                match.ifPresentOrElse(s -> entries.add(new ShoppingList.Entry(s.recipe(), null)),
                        () -> missing.add(name));
            }
        } else {
            lastRecipe.find(context.memoryId).ifPresent(r -> entries.add(new ShoppingList.Entry(r, null)));
            if (entries.isEmpty()) {
                return NO_RECIPE;
            }
        }
        if (entries.isEmpty()) {
            return "None of those are in their saved recipes (" + String.join(", ", missing) + "). Ask which they meant.";
        }
        List<ShoppingList.Item> items = ShoppingList.of(entries, DEFAULT_SERVINGS);
        context.shoppingList = items;
        return "The shopping list (" + items.size() + " items) is shown to the user as a checklist; don't repeat "
                + "it. They can also open the [Shopping list](/shopping-list) page."
                + (missing.isEmpty() ? "" : " These weren't found in their saved recipes: " + String.join(", ", missing) + ".");
    }

    @Tool("""
            Searches the user's saved recipes, e.g. for something quick, low in calories or with chicken.
            """)
    public String findSavedRecipes(
            @P(value = "Words to look for in the name, ingredients or tags", required = false) String query,
            @P(value = "An ingredient the recipe must have", required = false) String includes,
            @P(value = "Most minutes it may take", required = false) Integer maxMinutes,
            @P(value = "Most calories per serving", required = false) Integer maxCalories,
            @P(value = "easy, medium or hard", required = false) String difficulty) {
        if (context.userId == null) {
            return "Guests don't have saved recipes; signed-in subscribers can save them.";
        }
        List<SavedRecipe> found;
        try {
            found = new RecipeFilter(query, null, null, difficulty, null, includes, null, maxMinutes, maxCalories, null)
                    .apply(saved.list(context.userId));
        } catch (WebApplicationException e) {
            return refused(e);
        }
        if (found.isEmpty()) {
            return "None of their saved recipes match. They can browse them all on [My recipes](/recipes).";
        }
        List<String> lines = found.stream().limit(10).map(s -> "- " + describe(s.recipe())).toList();
        return found.size() + " saved recipes match" + (found.size() > 10 ? " (the first 10)" : "") + ":\n"
                + String.join("\n", lines) + "\nThey're on the [My recipes](/recipes) page.";
    }

    @Tool("""
            Tells what the user's plan gives them: whether they're signed in, their free recipes left, their
            subscription, whether the free trial is available, and the plans' prices.
            """)
    public String myPlan() {
        List<String> facts = new ArrayList<>();
        try {
            FreeRecipes free = allowance.freeRecipes(context.userId, context.guestId);
            if (context.userId == null) {
                facts.add("The user isn't signed in (a guest).");
            } else {
                facts.add("The user is signed in.");
            }
            facts.add(free.limit() == null
                    ? "They have unlimited recipes."
                    : "They have " + free.remaining() + " of " + free.limit() + " free recipes left.");
            if (context.userId != null && billing.enabled()) {
                facts.add(subscriptions.find(context.userId)
                        .map(ChatTools::describe)
                        .orElse("They don't have a subscription."));
                facts.add(subscriptions.trialAvailable(context.userId)
                        ? "Their " + billing.trialDays() + "-day free trial is available."
                        : "They've already had their free trial.");
            }
        } catch (WebApplicationException e) {
            facts.add("Their plan couldn't be loaded right now.");
        }
        if (billing.enabled()) {
            try {
                PlanPrice monthly = prices.price(Plan.MONTHLY);
                PlanPrice yearly = prices.price(Plan.YEARLY);
                facts.add("Plans: monthly " + money(monthly) + ", yearly " + money(yearly) + ", each with a "
                        + billing.trialDays() + "-day free trial for new subscribers.");
            } catch (WebApplicationException e) {
                facts.add("The plans' prices couldn't be loaded; they're on the Plan page.");
            }
        } else {
            facts.add("Subscriptions aren't available on this site yet.");
        }
        return String.join(" ", facts);
    }

    // Shows the recipe as a card, and keeps it as the one the other tools work on
    private void show(String recipe) {
        context.recipe = recipe;
        RecipeJson.parse(recipe).ifPresent(r -> {
            try {
                lastRecipe.set(context.memoryId, r);
            } catch (RuntimeException e) {
                // The card still shows; only follow-ups ("make it spicier") won't find it
                LOG.warnf("Could not keep the chat's last recipe: %s", e.getMessage());
            }
        });
    }

    // Tells the model why it couldn't, and the page what to offer
    private String refused(WebApplicationException e) {
        int status = e.getResponse().getStatus();
        String reason = e.getResponse().hasEntity() ? String.valueOf(e.getResponse().getEntity()) : e.getMessage();
        if (status == 401) {
            context.needs = "signIn";
            context.needsMessage = reason;
            return "Refused: " + reason + " Tell the user kindly; a sign-in button is shown below your reply.";
        }
        if (status == 402) {
            context.needs = "subscribe";
            context.needsMessage = reason;
            return "Refused: " + reason + " Tell the user kindly; a button to see the plans is shown below your reply.";
        }
        if (status == 400) {
            return "Refused: " + reason + ". Explain this to the user and ask them to change their request.";
        }
        LOG.warnf("Chat tool refused with %d: %s", status, reason);
        return "Refused: something went wrong on our side (" + reason + "). Suggest trying again shortly.";
    }

    private static String describe(RecipeData recipe) {
        List<String> facts = new ArrayList<>();
        if (recipe.totalMinutes() != null) {
            facts.add(recipe.totalMinutes() + " min");
        }
        if (recipe.calories() != null) {
            facts.add(recipe.calories() + " kcal");
        }
        if (recipe.difficulty() != null) {
            facts.add(recipe.difficulty());
        }
        return recipe.recipeName() + (facts.isEmpty() ? "" : " (" + String.join(", ", facts) + ")");
    }

    private static String describe(UserSubscription s) {
        String plan = s.plan() == null ? "a" : "the " + s.plan();
        return switch (s.status()) {
            case "trialing" -> "They're on a free trial of " + plan + " plan.";
            case "active" -> "They're subscribed to " + plan + " plan" + (s.cancelAtPeriodEnd() ? ", ending at the end of the period." : ".");
            case "past_due" -> "Their last payment failed; they should update their card on the Plan page.";
            default -> "Their subscription has ended (" + s.status() + ").";
        };
    }

    private static String money(PlanPrice price) {
        return String.format("%.2f %s", price.amount() / 100.0, price.currency().toUpperCase());
    }
}
