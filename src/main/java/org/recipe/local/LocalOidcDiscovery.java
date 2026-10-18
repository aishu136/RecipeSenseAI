package org.recipe.local;

import java.net.URI;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Local mode without Docker (the "local" profile): signing in uses Quarkus's
 * in-app OIDC server instead of Keycloak. That server takes a new port each
 * time the app starts, so this gives the frontend a fixed issuer to discover
 * it from: OIDC_ISSUER=http://localhost:8080/local/oidc. Only there when
 * quarkus.oidc.devservices.enabled is true.
 */
@Path("/local/oidc/.well-known/openid-configuration")
@IfBuildProperty(name = "quarkus.oidc.devservices.enabled", stringValue = "true")
public class LocalOidcDiscovery {

    // Set by Dev Services for OIDC to the server it started
    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    String authServerUrl;

    @GET
    public Response discovery() {
        return Response.temporaryRedirect(
                URI.create(authServerUrl.replaceAll("/$", "") + "/.well-known/openid-configuration")).build();
    }
}
