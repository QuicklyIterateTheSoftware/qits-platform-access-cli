package eu.wohlben.qits.cli.access.idp;

/** What can go wrong at {@code /token}. The three kinds need three different answers. */
public sealed class IdpException extends Exception {

    private IdpException(String message) {
        super(message);
    }

    /** A connection error or a 5xx. Try again later. */
    public static final class Unreachable extends IdpException {
        public Unreachable(String message) {
            super(message);
        }
    }

    /**
     * {@code invalid_grant}: the code is expired, used or mistyped, or the refresh token is revoked
     * or expired. Trying the same value again cannot help.
     */
    public static final class InvalidGrant extends IdpException {
        public InvalidGrant(String message) {
            super(message);
        }
    }

    /** Any other refusal, or an answer this client cannot use. */
    public static final class Refused extends IdpException {
        public Refused(String message) {
            super(message);
        }
    }
}
