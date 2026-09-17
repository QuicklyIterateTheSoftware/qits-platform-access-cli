package eu.wohlben.qits.cli.access.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * What an access token says about itself, read without verifying it.
 * <p>
 * The idp signed it and the services check that signature; nothing here decides access. This is for
 * the two things a person is told — who they are signed in as, and which roles their credential
 * holds — so the screen can say it instead of making them guess.
 * <p>
 * Nothing here ever returns the token, or any part of it that is a secret: the claims read are
 * names, groups and audiences. A body that will not parse is simply empty.
 */
public final class TokenClaims {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JsonNode payload;

    private TokenClaims(JsonNode payload) {
        this.payload = payload;
    }

    /** The claims of a JWT, or empty claims when it is not one. */
    public static TokenClaims of(String jwt) {
        if (jwt == null) {
            return new TokenClaims(JSON.createObjectNode());
        }
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return new TokenClaims(JSON.createObjectNode());
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode read = JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
            return new TokenClaims(read == null || !read.isObject() ? JSON.createObjectNode() : read);
        } catch (IllegalArgumentException | java.io.IOException unreadable) {
            // A message here could quote the token's body, so there is none.
            return new TokenClaims(JSON.createObjectNode());
        }
    }

    /** The name to show a person: their username, else their display name, else their subject. */
    public Optional<String> who() {
        for (String claim : List.of("preferred_username", "name", "sub")) {
            JsonNode value = payload.get(claim);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return Optional.of(value.asText());
            }
        }
        return Optional.empty();
    }

    /** The {@code groups} claim, which is where the platform's roles are. */
    public List<String> groups() {
        return strings("groups");
    }

    /** The {@code aud} claim, which may be one value or many. */
    public List<String> audiences() {
        return strings("aud");
    }

    /** The role a person or an agent is best described by: the first {@code qits:} group. */
    public Optional<String> role() {
        return groups().stream().filter(group -> group.startsWith("qits:")).findFirst();
    }

    private List<String> strings(String claim) {
        JsonNode value = payload.get(claim);
        List<String> all = new ArrayList<>();
        if (value == null) {
            return all;
        }
        if (value.isTextual()) {
            all.add(value.asText());
        } else if (value.isArray()) {
            value.forEach(element -> {
                if (element.isTextual()) {
                    all.add(element.asText());
                }
            });
        }
        return all;
    }

    @Override
    public String toString() {
        return "TokenClaims[" + who().orElse("?") + ", groups=" + groups() + "]";
    }
}
