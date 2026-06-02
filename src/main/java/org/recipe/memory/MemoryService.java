package org.recipe.memory;


import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
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