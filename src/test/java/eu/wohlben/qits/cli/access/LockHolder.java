package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.session.ExclusiveLock;

import java.nio.file.Path;
import java.util.Optional;

/**
 * A second process that takes a lock and holds it until its stdin closes. The lock is an fcntl
 * lock, which works between processes; a test in one JVM would prove only the in-process gate.
 */
public final class LockHolder {

    public static void main(String[] args) throws Exception {
        Optional<ExclusiveLock> lock = ExclusiveLock.tryAcquire(Path.of(args[0]));
        System.out.println(lock.isPresent() ? "locked" : "busy");
        System.out.flush();
        while (System.in.read() >= 0) {
            // Hold until the parent closes the pipe.
        }
        lock.ifPresent(ExclusiveLock::close);
    }

    public static Process start(Path lockFile) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), lockFile.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String first = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream())).readLine();
        if (!"locked".equals(first)) {
            process.destroyForcibly();
            throw new IllegalStateException("the lock holder did not take the lock: " + first);
        }
        return process;
    }
}
