package org.recipe.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

// State flowing through the autonomous recipe graph. Each run starts from a
// fresh state, so memory is per run instead of shared across users.
public class AutonomousRecipeState extends AgentState {

    public static final String GOAL = "goal";
    public static final String STEPS = "steps";
    public static final String STEP_INDEX = "stepIndex";
    public static final String RESULT = "result";
    public static final String NEEDS_IMPROVEMENT = "needsImprovement";
    public static final String MEMORY = "memory";

    // Every result the executor produces is appended to MEMORY; the other
    // keys are overwritten by each node update.
    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            MEMORY, Channels.appenderWithDuplicate(ArrayList::new));

    public AutonomousRecipeState(Map<String, Object> initData) {
        super(initData);
    }

    public String goal() {
        return this.<String>value(GOAL).orElseThrow();
    }

    public List<String> steps() {
        return this.<List<String>>value(STEPS).orElse(List.of());
    }

    public int stepIndex() {
        return this.<Integer>value(STEP_INDEX).orElse(0);
    }

    public boolean hasMoreSteps() {
        return stepIndex() < steps().size();
    }

    public String result() {
        return this.<String>value(RESULT).orElse("");
    }

    public boolean needsImprovement() {
        return this.<Boolean>value(NEEDS_IMPROVEMENT).orElse(false);
    }

    public String context() {
        return String.join("\n", this.<List<String>>value(MEMORY).orElse(List.of()));
    }
}
