package org.recipe.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.recipe.model.UserPreference;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Keeps each user's latest preferences from the user-preferences topic.
 * Every instance reads the topic from the start, so the map is rebuilt
 * after a restart.
 */
@ApplicationScoped
public class UserPreferenceStore {

    private static final Logger LOG = Logger.getLogger(UserPreferenceStore.class);

    private final Map<String, UserPreference> preferences = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper mapper;

    @Incoming("user-preferences")
    public void receive(String message) {
        try {
            UserPreference preference = mapper.readValue(message, UserPreference.class);
            if (preference.userId() != null) {
                preferences.put(preference.userId(), preference);
            }
        } catch (Exception e) {
            LOG.warnf("Ignoring unreadable user preference: %s", e.getMessage());
        }
    }

    public Optional<UserPreference> find(String userId) {
        return userId == null ? Optional.empty() : Optional.ofNullable(preferences.get(userId));
    }
}
