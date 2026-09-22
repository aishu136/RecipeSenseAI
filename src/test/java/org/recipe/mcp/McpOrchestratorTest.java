package org.recipe.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.BadRequestException;

class McpOrchestratorTest {

    McpTool search;
    McpTool allergy;
    McpOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        search = mock(McpTool.class);
        when(search.name()).thenReturn("recipe-search");
        allergy = mock(McpTool.class);
        when(allergy.name()).thenReturn("allergy-check");

        orchestrator = new McpOrchestrator();
        orchestrator.registry = new McpToolRegistry(List.of(search, allergy));
    }

    @Test
    void registryFindsToolsByName() {
        assertSame(search, orchestrator.registry.get("recipe-search"));
        assertSame(allergy, orchestrator.registry.get("allergy-check"));
        assertNull(orchestrator.registry.get("unknown"));
    }

    @Test
    void executesTheNamedTool() {
        when(allergy.execute("peanut toast")).thenReturn("WARNING : Peanut detected");

        assertEquals("WARNING : Peanut detected", orchestrator.execute("allergy-check", "peanut toast"));
    }

    @Test
    void unknownToolReturnsError() {
        assertEquals("{\"error\":\"Tool not found\"}", orchestrator.execute("does-not-exist", "x"));
    }

    @Test
    void resourceRejectsRequestWithoutTool() {
        McpResource resource = new McpResource();
        resource.orchestrator = orchestrator;

        assertThrows(BadRequestException.class, () -> resource.execute(null));
        assertThrows(BadRequestException.class, () -> resource.execute(new McpRequest()));
    }
}
