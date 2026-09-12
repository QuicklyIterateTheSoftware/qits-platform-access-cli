package eu.wohlben.qits.cli.access.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.PrivateFiles;
import eu.wohlben.qits.cli.access.session.SessionFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * {@code $XDG_CONFIG_HOME/qits/git.json}: the Git sign-ins, keyed by git origin, and {@code
 * git.json.lock} beside it.
 * <p>
 * <b>Every read-refresh-write happens under the lock</b>, and the file is read again under it: the
 * refresh tokens rotate, and Git may run several helpers at once (a fetch and a push, two
 * terminals). The file is 0600 in the 0700 directory and is replaced atomically, like {@code
 * t.json}.
 * <p>
 * The outer object is read as a tree and each entry as a {@link GitCredential}: a record inside a
 * generic map would need more reflection in the native binary than a tree does.
 */
public final class GitCredentialFile {

    public static final String FILE_NAME = "git.json";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private final Path directory;

    public GitCredentialFile(Path directory) {
        this.directory = directory;
    }

    /** Beside {@code t.json}. */
    public static GitCredentialFile fromEnvironment(Map<String, String> env) {
        return new GitCredentialFile(SessionFile.fromEnvironment(env).directory());
    }

    public Path path() {
        return directory.resolve(FILE_NAME);
    }

    public Path lockPath() {
        return directory.resolve(FILE_NAME + ".lock");
    }

    /** The write lock. Waits while another process holds it. */
    public ExclusiveLock lock() throws IOException, InterruptedException {
        PrivateFiles.ensureDirectory(directory);
        return ExclusiveLock.acquire(lockPath());
    }

    /** Every sign-in, by origin; empty when there is no file. A file that does not parse throws. */
    public Map<String, GitCredential> read() throws IOException {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path());
        } catch (NoSuchFileException absent) {
            return new TreeMap<>();
        }
        Map<String, GitCredential> result = new TreeMap<>();
        try {
            JsonNode all = JSON.readTree(bytes).path("credentials");
            for (Map.Entry<String, JsonNode> entry : all.properties()) {
                result.put(entry.getKey(), JSON.treeToValue(entry.getValue(), GitCredential.class));
            }
        } catch (IOException | RuntimeException e) {
            // Jackson's message may quote the content, and the content holds tokens.
            throw new IOException(path() + " is not a valid git sign-in file");
        }
        for (GitCredential c : result.values()) {
            if (c == null || blank(c.idpUrl()) || blank(c.refreshToken()) || blank(c.gitOrigin())
                    || c.refreshExpiresAt() == null) {
                throw new IOException(path() + " is missing a field");
            }
        }
        return result;
    }

    public Optional<GitCredential> find(String gitOrigin) throws IOException {
        return Optional.ofNullable(read().get(gitOrigin));
    }

    /** Reads, replaces the one origin's entry, writes. The caller holds the lock. */
    public void put(GitCredential credential) throws IOException {
        Map<String, GitCredential> all = read();
        all.put(credential.gitOrigin(), credential);
        ObjectNode root = JSON.createObjectNode();
        ObjectNode credentials = root.putObject("credentials");
        all.forEach((origin, c) -> credentials.set(origin, JSON.valueToTree(c)));
        PrivateFiles.writeAtomically(directory, FILE_NAME, JSON.writeValueAsBytes(root));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
