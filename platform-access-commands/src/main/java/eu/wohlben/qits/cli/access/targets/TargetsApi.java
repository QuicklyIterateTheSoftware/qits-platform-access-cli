package eu.wohlben.qits.cli.access.targets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * qits-system-service's targets and leases doors, {@code /system/api} (epic qits-1481, dossier
 * "Targets in qits-system: data, doors, allocation"). Each method is one door, named by its
 * {@code operationId}, so a command and the door it calls are one call apart. The answers are read
 * as JSON trees; {@code listTargets} and {@code listLeases} answer a bare JSON array, never an
 * {@code {entries: […]}} envelope.
 * <p>
 * {@code createTarget} and {@code rotateTargetRegistrationToken} answer a one-time registration
 * token and the install line that embeds it (qits-1574). Their answer is handed back whole and
 * never quoted in a message: only {@code create} and {@code rotate-token} decide what is printed.
 * Since qits-1579, {@code rotateTargetRegistrationToken} also reinstalls a registered target's
 * runner (the target keeps its id; the old runner's client is decommissioned when the new one
 * registers), and both doors take an optional {@code slots} total for the install line's
 * QITS_SYSTEM_RUNNER_SLOTS.
 */
final class TargetsApi {

    static final String API = "/system/api";

    private final PlatformClient client;
    private final String base;

    TargetsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** {@code listTargets}: every target, by name. */
    JsonNode targets() throws CliFailure, InterruptedException {
        return client.get(uri(API + "/targets"));
    }

    /**
     * {@code createTarget}: {@code {name, description?, minimums?, slots?}}, a 201 {@code {target,
     * registrationToken, installLine}}. {@code slots} is the install line's QITS_SYSTEM_RUNNER_SLOTS;
     * left out, the service picks max(reported total, the minimums' sum).
     */
    JsonNode createTarget(JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(API + "/targets"), body);
    }

    /**
     * {@code rotateTargetRegistrationToken}: a 200 of the same shape as createTarget. Works on a
     * REGISTERED target too (the new install line reinstalls the runner under the same id; the old
     * runner's client is decommissioned when the new one registers). With {@code slots} null, no
     * body is sent and the request is exactly what it always was; given, the body is {@code
     * {"slots": <n>}}, the install line's QITS_SYSTEM_RUNNER_SLOTS.
     */
    JsonNode rotateTargetRegistrationToken(String id, Integer slots) throws CliFailure, InterruptedException {
        URI uri = uri(targetPath(id) + "/registration-token");
        if (slots == null) {
            return client.post(uri);
        }
        return client.post(uri, JsonNodeFactory.instance.objectNode().put("slots", slots));
    }

    /** {@code getTarget}. */
    JsonNode target(String id) throws CliFailure, InterruptedException {
        return client.get(uri(targetPath(id)));
    }

    /** {@code patchTarget}: only the fields {@code change} carries. */
    JsonNode patchTarget(String id, JsonNode change) throws CliFailure, InterruptedException {
        return client.patch(uri(targetPath(id)), change, "application/json");
    }

    /** {@code deleteTarget}: a 204. */
    JsonNode deleteTarget(String id) throws CliFailure, InterruptedException {
        return client.delete(uri(targetPath(id)));
    }

    /** {@code greenlightTarget}: no body. */
    JsonNode greenlightTarget(String id) throws CliFailure, InterruptedException {
        return client.post(uri(targetPath(id) + "/greenlight"));
    }

    /** {@code requestTargetHealthCheck}: no body (202 {@code {requestId}}). */
    JsonNode requestTargetHealthCheck(String id) throws CliFailure, InterruptedException {
        return client.post(uri(targetPath(id) + "/healthcheck"));
    }

    /** {@code requestTargetLoginCheck}: no body (202, no content). */
    JsonNode requestTargetLoginCheck(String id) throws CliFailure, InterruptedException {
        return client.post(uri(targetPath(id) + "/login-check"));
    }

    /**
     * {@code createSignIn}: {@code {"harness": "claude"|"kimi"}}, a 202 {@code SignInView}. 400 on
     * an unknown harness; 409 {@code TARGET_UNAVAILABLE} when the target is not connected; 409
     * {@code SIGN_IN_RUNNING} when a non-terminal sign-in for that target and harness already
     * exists (its {@code signInId} is named in the message).
     */
    JsonNode createSignIn(String targetId, String harness) throws CliFailure, InterruptedException {
        return client.post(uri(signInsPath(targetId)), JsonNodeFactory.instance.objectNode().put("harness", harness));
    }

    /** {@code getSignIn}: a 200 {@code SignInView}; 404 {@code SIGN_IN_NOT_FOUND}. */
    JsonNode getSignIn(String targetId, String signInId) throws CliFailure, InterruptedException {
        return client.get(uri(signInPath(targetId, signInId)));
    }

    /**
     * {@code postSignInCode}: {@code {"code": "..."}}, a 202 {@code SignInView}; 409
     * {@code SIGN_IN_NOT_AWAITING_CODE} unless the sign-in is {@code AWAITING_CODE}. Never logged:
     * the code is spendable once.
     */
    JsonNode postSignInCode(String targetId, String signInId, String code) throws CliFailure, InterruptedException {
        return client.post(uri(signInPath(targetId, signInId) + "/code"),
                JsonNodeFactory.instance.objectNode().put("code", code));
    }

    /**
     * {@code deleteSignIn}: a 202 {@code SignInView}, {@code CANCELLED} once the runner answers (the
     * service may set it at once).
     */
    JsonNode deleteSignIn(String targetId, String signInId) throws CliFailure, InterruptedException {
        return client.delete(uri(signInPath(targetId, signInId)));
    }

    /** {@code listLeases}: oldest first; each filter is left out when it is null or blank. */
    JsonNode leases(String targetId, String slotType, String state) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        add(query, "targetId", targetId);
        add(query, "slotType", slotType);
        add(query, "state", state);
        return client.get(uri(API + "/leases" + (query.isEmpty() ? "" : "?" + String.join("&", query))));
    }

    /** {@code getLease}: one lease, a 200 {@code LeaseDto}; 404 for no such lease, or one not the caller's. */
    JsonNode getLease(String id) throws CliFailure, InterruptedException {
        return client.get(uri(API + "/leases/" + segment(id)));
    }

    /**
     * {@code endLease}: a 200 {@code LeaseDto}, ENDING or ENDED; the reason, when given, as {@code
     * ?reason=} (epic qits-1621, dossier "CI cancel and lease end"). An operator (qits:admin,
     * qits:admin-agent, qits:desk-admin) ends any lease, and the service audits it; an ENDING lease
     * an operator ends goes ENDED at once. qits:system ends only its own (403 NOT_YOUR_LEASE).
     */
    JsonNode endLease(String id, String reason) throws CliFailure, InterruptedException {
        List<String> query = new ArrayList<>();
        add(query, "reason", reason);
        return client.delete(uri(API + "/leases/" + segment(id) + (query.isEmpty() ? "" : "?" + query.getFirst())));
    }

    private static String targetPath(String id) {
        return API + "/targets/" + segment(id);
    }

    private static String signInsPath(String targetId) {
        return targetPath(targetId) + "/sign-ins";
    }

    private static String signInPath(String targetId, String signInId) {
        return signInsPath(targetId) + "/" + segment(signInId);
    }

    private static void add(List<String> query, String name, String value) {
        if (value != null && !value.isBlank()) {
            query.add(name + "=" + segment(value));
        }
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI resolved = URI.create(base + path);
            if (resolved.getScheme() == null || resolved.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return resolved;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable system address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
