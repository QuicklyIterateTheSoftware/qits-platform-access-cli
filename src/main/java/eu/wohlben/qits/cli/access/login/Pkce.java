package eu.wohlben.qits.cli.access.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A PKCE verifier and its S256 challenge, made per login and never stored. Copied from
 * qits-bootstrap-cli rather than shared through a jar.
 * <p>
 * The static SecureRandom is why the native build initialises this class at run time: seeded at
 * build time, every binary would carry the same seed.
 * <p>
 * {@link #toString()} leaves the verifier out. The verifier is what makes a leaked code worthless.
 */
public record Pkce(String verifier, String challenge) {
    private static final SecureRandom RANDOM = new SecureRandom();

    public static Pkce create() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return new Pkce(verifier, Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    /** A one-time {@code state} for a redirect sign-in: the answer must carry it back. */
    public static String state() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String toString() {
        return "Pkce[challenge=" + challenge + "]";
    }
}
