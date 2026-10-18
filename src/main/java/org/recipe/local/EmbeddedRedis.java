package org.recipe.local;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.github.fppt.jedismock.RedisServer;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Local mode without Docker (the "local" profile): a Redis server inside the
 * app, on localhost only, so everything that keeps data in Redis works with
 * nothing else running. Its data lasts until the app stops. Off unless
 * recipe.local.embedded-redis is true; quarkus.redis.hosts must point at it.
 */
@ApplicationScoped
public class EmbeddedRedis {

    private static final Logger LOG = Logger.getLogger(EmbeddedRedis.class);

    @ConfigProperty(name = "recipe.local.embedded-redis", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "recipe.local.redis-port", defaultValue = "6380")
    int port;

    RedisServer server;

    // Before anything else starts: the Redis client connects on its first command
    void start(@Observes @Priority(1) StartupEvent event) {
        if (!enabled) {
            return;
        }
        try {
            server = RedisServer.newRedisServer(port, InetAddress.getLoopbackAddress());
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the in-app Redis on port " + port
                    + " (is something else using it? set recipe.local.redis-port)", e);
        }
        LOG.infof("Local mode: in-app Redis on localhost:%d (data is lost when the app stops)", port);
    }

    void stop(@Observes ShutdownEvent event) throws IOException {
        if (server != null) {
            server.stop();
            server = null;
        }
    }
}
