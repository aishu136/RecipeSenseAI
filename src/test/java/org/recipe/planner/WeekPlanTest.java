package org.recipe.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.recipe.TestMongo;
import org.recipe.planner.WeekPlan.Slot;
import org.recipe.planner.WeekPlanResource.WeekRequest;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;

@ExtendWith(MockitoExtension.class)
class WeekPlanTest {

    static final String MONDAY = "2026-10-19";
    static final Slot CURRY = new Slot(0, "dinner", "c1", null, "Chicken Curry", null, null);
    static final Slot PANCAKES = new Slot(2, "breakfast", null, 716429, "Pancakes",
            "https://example.com/pancakes", "https://img.example.com/716429.jpg");

    @Mock
    SecurityIdentity identity;

    WeekPlanStore store;
    WeekPlanResource resource;

    @BeforeEach
    void setUp() {
        store = new WeekPlanStore();
        store.collection = TestMongo.collection(WeekPlanStore.COLLECTION);
        resource = new WeekPlanResource();
        resource.store = store;
        resource.identity = identity;
        lenient().when(identity.getPrincipal()).thenReturn(() -> "alice");
    }

    @Test
    void savesAndLoadsAWeek() {
        assertEquals(new WeekPlan(MONDAY, List.of()), resource.get("alice", MONDAY));

        resource.put("alice", MONDAY, new WeekRequest(List.of(CURRY, PANCAKES)));

        assertEquals(new WeekPlan(MONDAY, List.of(CURRY, PANCAKES)), resource.get("alice", MONDAY));
        assertEquals(List.of(), resource.get("alice", "2026-10-26").slots());
    }

    @Test
    void savingAWeekReplacesIt() {
        resource.put("alice", MONDAY, new WeekRequest(List.of(CURRY, PANCAKES)));
        resource.put("alice", MONDAY, new WeekRequest(List.of(PANCAKES)));

        assertEquals(List.of(PANCAKES), resource.get("alice", MONDAY).slots());
        assertEquals(1, store.collection.countDocuments());
    }

    @Test
    void eachUserHasTheirOwn() {
        resource.put("alice", MONDAY, new WeekRequest(List.of(CURRY)));

        assertEquals(List.of(), store.find("bob", MONDAY).slots());
        assertThrows(ForbiddenException.class, () -> resource.get("bob", MONDAY));
        assertThrows(ForbiddenException.class, () -> resource.put("bob", MONDAY, new WeekRequest(List.of())));
    }

    @Test
    void weeksStartOnMonday() {
        assertThrows(BadRequestException.class, () -> resource.get("alice", "2026-10-20"));
        assertThrows(BadRequestException.class, () -> resource.get("alice", "next week"));
    }

    @Test
    void rejectsBadMeals() {
        List<List<Slot>> bad = List.of(
                List.of(new Slot(7, "dinner", "c1", null, "Curry", null, null)),
                List.of(new Slot(0, "supper", "c1", null, "Curry", null, null)),
                List.of(CURRY, new Slot(0, "dinner", "c2", null, "Dal", null, null)),
                List.of(new Slot(0, "dinner", null, null, "Curry", null, null)),
                List.of(new Slot(0, "dinner", "c1", 5, "Curry", null, null)),
                List.of(new Slot(0, "dinner", "c1", null, " ", null, null)),
                List.of(new Slot(0, "dinner", null, 5, "Curry", "javascript:alert(1)", null)),
                List.of(new Slot(0, "dinner", null, 5, "Curry", null, "data:image/png;base64,AAAA")),
                Collections.nCopies(22, CURRY));
        for (List<Slot> slots : bad) {
            assertThrows(BadRequestException.class, () -> resource.put("alice", MONDAY, new WeekRequest(slots)),
                    slots.toString());
        }
        assertThrows(BadRequestException.class, () -> resource.put("alice", MONDAY, new WeekRequest(null)));
        assertEquals(0, store.collection.countDocuments());
    }

    @Test
    void mongoFailuresAre503() {
        store.collection = TestMongo.unreachable(WeekPlanStore.COLLECTION);

        assertEquals(503, assertThrows(WebApplicationException.class, () -> store.find("alice", MONDAY))
                .getResponse().getStatus());
    }
}
