package org.recipe.local;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.embed.process.io.ProcessOutput;
import de.flapdoodle.reverse.TransitionWalker;
import de.flapdoodle.reverse.transitions.Start;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Local mode without Docker (the "local" profile): a MongoDB server started
 * by the app, on localhost only, so saved restrictions, subscriptions and
 * recipes work with nothing else running. The first start downloads MongoDB
 * (into ~/.embedmongo); its data lasts until the app stops. Off unless
 * recipe.local.embedded-mongo is true; quarkus.mongodb.connection-string must
 * point at it.
 */
@ApplicationScoped
public class EmbeddedMongo {

    private static final Logger LOG = Logger.getLogger(EmbeddedMongo.class);

    @ConfigProperty(name = "recipe.local.embedded-mongo", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "recipe.local.mongo-port", defaultValue = "27018")
    int port;

    TransitionWalker.ReachedState<RunningMongodProcess> server;

    // Before anything else starts; the MongoDB client waits for the server on its first command
    void start(@Observes @Priority(1) StartupEvent event) {
        if (!enabled) {
            return;
        }
        LOG.infof("Local mode: starting MongoDB on localhost:%d (the first time, it downloads it)", port);
        try {
            server = Mongod.instance()
                    .withNet(Start.to(Net.class).initializedWith(Net.of("localhost", port, false)))
                    // Its own log would drown out the app's
                    .withProcessOutput(Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent()))
                    .start(Version.Main.V7_0);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Could not start the local MongoDB on port " + port
                    + " (is something else using it? set recipe.local.mongo-port)", e);
        }
        LOG.infof("Local mode: MongoDB on localhost:%d (data is lost when the app stops)", port);
    }

    void stop(@Observes ShutdownEvent event) {
        if (server != null) {
            server.close();
            server = null;
        }
    }
}
