package org.recipe.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.billing.SubscriptionStore;
import org.recipe.billing.UserSubscription;
import org.recipe.cooking.Ingredient;
import org.recipe.cooking.RecipeData;
import org.recipe.model.DietaryRestrictions;
import org.recipe.saved.SavedRecipe;
import org.recipe.saved.SavedRecipeStore;
import org.recipe.service.UserRestrictionStore;

import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.keys.KeyScanCursor;
import io.quarkus.redis.datasource.value.ValueCommands;

@ExtendWith(MockitoExtension.class)
class RedisToMongoMigrationTest {

    static final RecipeData RICE = new RecipeData("Tomato Rice", List.of(new Ingredient(1.0, "cup", "rice")),
            List.of("Cook"), 420, 2, 10, 20, "easy", List.of("quick"));
    static final SavedRecipe SAVED = new SavedRecipe("r1", RICE, "vegan", "indian", "en", 100, "share1");
    static final UserSubscription ACTIVE = new UserSubscription("alice", "cus_1", "sub_1", "active", "monthly",
            1_800_000_000L, 1_900_000_000L, false, 100);

    @Mock
    KeyCommands<String> keys;
    @Mock
    ValueCommands<String, DietaryRestrictions> restrictionValues;
    @Mock
    ValueCommands<String, UserSubscription> subscriptionValues;
    @Mock
    ValueCommands<String, String> strings;
    @Mock
    HashCommands<String, String, String> hashes;
    @Mock
    UserRestrictionStore restrictions;
    @Mock
    SubscriptionStore subscriptions;
    @Mock
    SavedRecipeStore savedRecipes;

    RedisToMongoMigration migration;

    @BeforeEach
    void setUp() {
        migration = new RedisToMongoMigration();
        migration.keys = keys;
        migration.restrictionValues = restrictionValues;
        migration.subscriptionValues = subscriptionValues;
        migration.strings = strings;
        migration.hashes = hashes;
        migration.restrictions = restrictions;
        migration.subscriptions = subscriptions;
        migration.savedRecipes = savedRecipes;
        redisKeys("dietary-restrictions:");
        redisKeys("subscription:");
        redisKeys("stripe-customer:");
        redisKeys("trial-used:");
        redisKeys("saved-recipes:");
    }

    // The keys a scan for the prefix finds
    @SuppressWarnings("unchecked")
    void redisKeys(String prefix, String... found) {
        KeyScanCursor<String> cursor = mock(KeyScanCursor.class);
        lenient().when(cursor.toIterable()).thenReturn(List.of(found));
        lenient().when(keys.scan(argThat((KeyScanArgs args) -> args != null && args.toArgs().contains(prefix + "*"))))
                .thenReturn(cursor);
    }

    @Test
    void copiesEverythingMongoDoesntHaveYet() throws Exception {
        DietaryRestrictions peanut = DietaryRestrictions.of(List.of("peanut"), List.of());
        redisKeys("dietary-restrictions:", "dietary-restrictions:alice");
        when(restrictionValues.get("dietary-restrictions:alice")).thenReturn(peanut);
        when(restrictions.find("alice")).thenReturn(DietaryRestrictions.NONE);
        redisKeys("subscription:", "subscription:alice");
        when(subscriptionValues.get("subscription:alice")).thenReturn(ACTIVE);
        when(subscriptions.save("alice", ACTIVE)).thenReturn(true);
        redisKeys("stripe-customer:", "stripe-customer:alice");
        when(strings.get("stripe-customer:alice")).thenReturn("cus_1");
        when(subscriptions.customerOf("alice")).thenReturn(Optional.empty());
        redisKeys("trial-used:", "trial-used:alice");
        when(subscriptions.trialUsed("alice")).thenReturn(false);
        redisKeys("saved-recipes:", "saved-recipes:alice");
        when(hashes.hgetall("saved-recipes:alice")).thenReturn(Map.of("r1", RedisToMongoMigration.JSON.writeValueAsString(SAVED)));
        when(savedRecipes.find("alice", "r1")).thenReturn(Optional.empty());

        assertEquals(new RedisToMongoMigration.Result(1, 1, 1, 1, 1, 0), migration.run());

        verify(restrictions).save("alice", peanut);
        verify(subscriptions).link("alice", "cus_1");
        verify(subscriptions).markTrialUsed("alice");
        verify(savedRecipes).save("alice", SAVED);
    }

    @Test
    void neverOverwritesWhatMongoAlreadyHas() throws Exception {
        redisKeys("dietary-restrictions:", "dietary-restrictions:alice");
        when(restrictionValues.get("dietary-restrictions:alice"))
                .thenReturn(DietaryRestrictions.of(List.of("peanut"), List.of()));
        when(restrictions.find("alice")).thenReturn(DietaryRestrictions.of(List.of(), List.of("pork")));
        redisKeys("subscription:", "subscription:alice");
        when(subscriptionValues.get("subscription:alice")).thenReturn(ACTIVE);
        when(subscriptions.save("alice", ACTIVE)).thenReturn(false); // MongoDB's is newer
        redisKeys("stripe-customer:", "stripe-customer:alice");
        when(strings.get("stripe-customer:alice")).thenReturn("cus_old");
        when(subscriptions.customerOf("alice")).thenReturn(Optional.of("cus_1"));
        redisKeys("trial-used:", "trial-used:alice");
        when(subscriptions.trialUsed("alice")).thenReturn(true);
        redisKeys("saved-recipes:", "saved-recipes:alice");
        when(hashes.hgetall("saved-recipes:alice")).thenReturn(Map.of("r1", RedisToMongoMigration.JSON.writeValueAsString(SAVED)));
        when(savedRecipes.find("alice", "r1")).thenReturn(Optional.of(SAVED.withShareId(null)));

        assertEquals(new RedisToMongoMigration.Result(0, 0, 0, 0, 0, 0), migration.run());

        verify(restrictions, never()).save(anyString(), any());
        verify(subscriptions, never()).link(anyString(), anyString());
        verify(subscriptions, never()).markTrialUsed(anyString());
        verify(savedRecipes, never()).save(anyString(), any());
    }

    @Test
    void oneBadRecordDoesntStopTheRest() {
        redisKeys("dietary-restrictions:", "dietary-restrictions:alice", "dietary-restrictions:bob");
        DietaryRestrictions pork = DietaryRestrictions.of(List.of(), List.of("pork"));
        when(restrictionValues.get("dietary-restrictions:alice")).thenThrow(new RuntimeException("not JSON"));
        when(restrictionValues.get("dietary-restrictions:bob")).thenReturn(pork);
        when(restrictions.find("bob")).thenReturn(DietaryRestrictions.NONE);
        redisKeys("saved-recipes:", "saved-recipes:bob");
        when(hashes.hgetall("saved-recipes:bob")).thenReturn(Map.of("bad", "{not json"));

        assertEquals(new RedisToMongoMigration.Result(1, 0, 0, 0, 0, 2), migration.run());

        verify(restrictions).save(eq("bob"), eq(pork));
    }
}
