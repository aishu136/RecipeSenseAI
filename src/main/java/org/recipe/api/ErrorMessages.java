package org.recipe.api;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Errors the app throws carry a message for people, e.g. "ingredient peanut
 * butter conflicts with the restriction peanut", but Quarkus sends them with an
 * empty body. This sends the message as the body, so clients can show it.
 */
public class ErrorMessages {

    @ServerExceptionMapper
    public Response withMessage(WebApplicationException e) {
        Response response = e.getResponse();
        String message = e.getMessage();
        // Responses that already have a body, and JAX-RS's default "HTTP 404 Not Found" style messages, stay as they are
        if (response.hasEntity() || message == null || message.isBlank() || message.startsWith("HTTP ")) {
            return response;
        }
        return Response.fromResponse(response).entity(message).type(MediaType.TEXT_PLAIN).build();
    }
}
