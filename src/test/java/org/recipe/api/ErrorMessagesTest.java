package org.recipe.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

class ErrorMessagesTest {

    final ErrorMessages mapper = new ErrorMessages();

    @Test
    void sendsTheMessageAsTheBody() {
        Response response = mapper.withMessage(
                new BadRequestException("ingredient peanut butter conflicts with the restriction peanut"));

        assertEquals(400, response.getStatus());
        assertEquals("ingredient peanut butter conflicts with the restriction peanut", response.getEntity());
        assertEquals("text/plain", response.getMediaType().toString());
    }

    @Test
    void keepsTheStatusAndHeaders() {
        WebApplicationException e = new WebApplicationException("Could not load the saved dietary restrictions",
                Response.status(503).header("Retry-After", "5").build());

        Response response = mapper.withMessage(e);

        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeaderString("Retry-After"));
        assertEquals("Could not load the saved dietary restrictions", response.getEntity());
    }

    @Test
    void leavesResponsesWithABodyAndDefaultMessagesAlone() {
        Response withBody = Response.status(401).entity("Sign in to generate recipes.").build();
        assertSame(withBody, mapper.withMessage(new WebApplicationException(withBody)));

        Response notFound = mapper.withMessage(new NotFoundException());
        assertEquals(404, notFound.getStatus());
        assertFalse(notFound.hasEntity());
    }
}
