package org.recipe.api;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.inject.Inject;

import org.recipe.agent.AutonomousRecipeState;
import org.recipe.service.AutonomousRecipeService;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@Path("/autonomous")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AutonomousRecipeResource {

    @Inject
    AutonomousRecipeService service;

    @POST
    public String run(String goal) {
        validate(goal);
        return service.runAutonomous(goal);
    }

    /**
     * Server-sent events: one per graph node as it finishes, then the final
     * result as the last event.
     */
    @POST
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public Multi<String> stream(String goal) {
        validate(goal);

        return Multi.createFrom().<String>emitter(emitter -> {
                    try {
                        String result = service.runAutonomous(goal, (node, state) ->
                                emitter.emit(describe(node, state)));
                        emitter.emit(result);
                        emitter.complete();
                    } catch (Exception e) {
                        emitter.fail(e);
                    }
                })
                // The agents and the wait for Flink block, so stay off the event loop.
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    static String describe(String node, AutonomousRecipeState state) {
        int total = state.steps().size();
        return switch (node) {
            case "plan" -> "📋 Planned %d step%s\n".formatted(total, total == 1 ? "" : "s");
            case "execute" -> "🍳 Step %d/%d: %s\n".formatted(
                    state.stepIndex(), total, state.steps().get(state.stepIndex() - 1));
            case "score" -> state.healthScore()
                    .map(score -> "📊 Health score %d%s\n".formatted(
                            score, state.needsImprovement() ? ", improving it" : ""))
                    .orElse("📊 No health score from Flink\n");
            case "improve" -> "🥗 Made it healthier\n";
            default -> node + "\n";
        };
    }

    private static void validate(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new BadRequestException("goal is required");
        }
    }
}
