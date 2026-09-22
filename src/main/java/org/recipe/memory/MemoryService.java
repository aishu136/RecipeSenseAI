package org.recipe.memory;


import jakarta.enterprise.context.RequestScoped;
import java.util.ArrayList;
import java.util.List;

// Request-scoped: each autonomous run gets its own memory instead of
// sharing (and leaking) one list across all users.
@RequestScoped
public class MemoryService {

    private final List<String> memory = new ArrayList<>();

    public void save(String data) {
        memory.add(data);
    }

    public String getContext() {
        return String.join("\n", memory);
    }

    public void clear() {
        memory.clear();
    }
}
