package org.worldspeaker.compat;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Bounded startup-only bridge to the packaged Node descriptor reader. */
final class PrivateFileReader {
    static final String HELPER_SHA256 = "d7f34ba48fca521fc79fcce880d7c828efb2ac37c680dbfda892e53664818405";
    private static final String HELPER_RESOURCE = "/org/worldspeaker/compat/private-file-reader.mjs";
    private static final int MAX_HELPER_BYTES = 16_384;
    private static final int MAX_IPC_BYTES = 4_096;
    private static final long PROCESS_TIMEOUT_NANOS = Duration.ofSeconds(2).toNanos();
    private static final byte[] OPENED_MARKER = "WSR1".getBytes(StandardCharsets.US_ASCII);
    private static final String RESULT_NAME = "private-file-reader.result";
    private static final Set<ProcessHandle> OWNED_CHILDREN = ConcurrentHashMap.newKeySet();
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final Gson GSON = new Gson();

    private PrivateFileReader() {}

    static byte[] read(Path path, int maximum, boolean credential) throws IOException {
        return readWithProcess(path, maximum, credential, PROCESS_TIMEOUT_NANOS, -1, null, null);
    }

    /** Deterministic descriptor-race and child-lifecycle seam; never accepts an executable or helper path. */
    static byte[] readForTest(Path path, int maximum, boolean credential, long timeoutMillis,
                              Runnable afterDescriptorOpened, Consumer<ProcessHandle> childObserver) throws IOException {
        if (timeoutMillis < 0 || timeoutMillis > 10_000) throw new IllegalArgumentException("test timeout outside bound");
        return readWithProcess(path, maximum, credential, PROCESS_TIMEOUT_NANOS,
                TimeUnit.MILLISECONDS.toNanos(timeoutMillis), afterDescriptorOpened, childObserver);
    }

    static int activeChildrenForTest() {
        return OWNED_CHILDREN.size();
    }

    private static byte[] readWithProcess(Path path, int maximum, boolean credential, long timeoutNanos,
                                          long afterOpenTimeoutNanos,
                                          Runnable afterDescriptorOpened,
                                          Consumer<ProcessHandle> childObserver) throws IOException {
        if (path == null || !path.isAbsolute() || path.toString().length() > StartupConfig.MAX_REFERENCE_UNITS ||
                !validBounds(maximum, credential)) {
            throw new IOException("private local reference is invalid");
        }

        Path stagingDirectory = null;
        Process child = null;
        InputStream childOutput = null;
        try {
            byte[] helperBytes = packagedHelper();
            Path node = resolvePinnedNodeCommand();
            stagingDirectory = createPrivateStagingDirectory();
            Path helper = stagingDirectory.resolve("private-file-reader.mjs");
            Path result = stagingDirectory.resolve(RESULT_NAME);
            writePrivateHelper(helper, helperBytes);
            createPrivateResult(result);

            ProcessBuilder builder = new ProcessBuilder(node.toString(), helper.toString());
            builder.environment().clear();
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            try {
                child = builder.start();
            } catch (IOException | RuntimeException unavailable) {
                throw invalidReference();
            }
            OWNED_CHILDREN.add(child.toHandle());
            childOutput = child.getInputStream();
            if (childObserver != null) childObserver.accept(child.toHandle());

            long deadline = System.nanoTime() + timeoutNanos;
            try (OutputStream childInput = child.getOutputStream()) {
                byte[] request = GSON.toJson(Map.of(
                        "path", path.toString(),
                        "maximum", maximum,
                        "credential", credential)).getBytes(StandardCharsets.UTF_8);
                if (request.length + 1 + 3 > MAX_IPC_BYTES) throw invalidReference();
                childInput.write(request);
                childInput.write('\n');
                childInput.flush();

                awaitOpenedMarker(child, childOutput, deadline);
                long completionDeadline = afterOpenTimeoutNanos < 0 ? deadline : System.nanoTime() + afterOpenTimeoutNanos;
                if (afterDescriptorOpened != null) afterDescriptorOpened.run();
                if (remaining(completionDeadline) <= 0) throw invalidReference();
                childInput.write("GO\n".getBytes(StandardCharsets.US_ASCII));
                childInput.flush();
                deadline = completionDeadline;
            } catch (IOException | RuntimeException failure) {
                throw invalidReference();
            }

            try {
                if (!child.waitFor(Math.max(0, remaining(deadline)), TimeUnit.NANOSECONDS) || child.exitValue() != 0) {
                    throw invalidReference();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw invalidReference();
            }
            if (childOutput.available() != 0) throw invalidReference();
            byte[] bytes = readPrivateResult(stagingDirectory, result, maximum);
            return bytes;
        } catch (IOException | RuntimeException failure) {
            throw invalidReference();
        } finally {
            if (childOutput != null) {
                try {
                    childOutput.close();
                } catch (IOException ignored) {
                    // Closing the owned child pipe is best effort after reap.
                }
            }
            boolean reaped = child == null || terminateAndReap(child);
            if (child != null && reaped) OWNED_CHILDREN.remove(child.toHandle());
            boolean cleaned = stagingDirectory == null || removePrivateStagingDirectory(stagingDirectory);
            if (!reaped || !cleaned) throw invalidReference();
        }
    }

    private static boolean validBounds(int maximum, boolean credential) {
        return (maximum == StartupConfig.MAX_BOOTSTRAP_BYTES && !credential) ||
                (maximum == StartupConfig.MAX_BUNDLE_BYTES && !credential) ||
                (maximum == StartupConfig.MAX_CREDENTIAL_BYTES && credential);
    }

    private static void awaitOpenedMarker(Process child, InputStream output, long deadline) throws IOException {
        for (;;) {
            if (output.available() >= OPENED_MARKER.length) {
                byte[] observed = output.readNBytes(OPENED_MARKER.length);
                if (!MessageDigest.isEqual(OPENED_MARKER, observed)) throw invalidReference();
                return;
            }
            if (!child.isAlive() || remaining(deadline) <= 0) throw invalidReference();
            LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(1), remaining(deadline)));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw invalidReference();
            }
        }
    }

    private static long remaining(long deadline) {
        return deadline - System.nanoTime();
    }

    private static Path resolvePinnedNodeCommand() throws IOException {
        String environmentPath = System.getenv("PATH");
        if (environmentPath == null || environmentPath.isBlank()) throw invalidReference();
        for (String entry : environmentPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1)) {
            if (entry.isEmpty()) continue;
            try {
                Path directory = Path.of(entry);
                if (!directory.isAbsolute()) continue;
                Path candidate = directory.resolve("node");
                if (!Files.isExecutable(candidate)) continue;
                Path executable = candidate.toRealPath();
                if (Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(executable)) {
                    return executable;
                }
            } catch (IOException | RuntimeException ignored) {
                // Try the next absolute PATH entry; never invoke a shell or a relative command.
            }
        }
        throw invalidReference();
    }

    private static byte[] packagedHelper() throws IOException {
        try (InputStream resource = PrivateFileReader.class.getResourceAsStream(HELPER_RESOURCE)) {
            if (resource == null) throw invalidReference();
            byte[] bytes = resource.readNBytes(MAX_HELPER_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_HELPER_BYTES || !HELPER_SHA256.equals(sha256(bytes))) {
                throw invalidReference();
            }
            return bytes;
        }
    }

    private static Path createPrivateStagingDirectory() throws IOException {
        Path staging = null;
        try {
            Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
            if (!Files.getFileStore(temporaryRoot).supportsFileAttributeView("posix")) throw invalidReference();
            staging = Files.createTempDirectory(temporaryRoot, "worldspeaker-private-reader-",
                    PosixFilePermissions.asFileAttribute(DIRECTORY_MODE));
            PosixFileAttributes attributes = Files.readAttributes(staging, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || !attributes.permissions().equals(DIRECTORY_MODE)) {
                throw invalidReference();
            }
            return staging;
        } catch (RuntimeException | IOException failure) {
            if (staging != null) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException | RuntimeException ignored) {
                    // The owner-only path is still never returned or used.
                }
            }
            throw invalidReference();
        }
    }

    private static void writePrivateHelper(Path helper, byte[] bytes) throws IOException {
        try {
            Files.createFile(helper, PosixFilePermissions.asFileAttribute(FILE_MODE));
            Files.write(helper, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            PosixFileAttributes attributes = Files.readAttributes(helper, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributes directory = Files.readAttributes(helper.getParent(), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String, Object> unix = Files.readAttributes(helper, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || !attributes.permissions().equals(FILE_MODE) ||
                    attributes.size() != bytes.length || ((Number) unix.get("nlink")).longValue() != 1 ||
                    !attributes.owner().equals(directory.owner()) || !directory.isDirectory() || directory.isSymbolicLink() ||
                    !directory.permissions().equals(DIRECTORY_MODE)) {
                throw invalidReference();
            }
            byte[] observed;
            try (InputStream stream = Files.newInputStream(helper, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                observed = stream.readNBytes(MAX_HELPER_BYTES + 1);
            }
            if (observed.length != bytes.length || !HELPER_SHA256.equals(sha256(observed))) throw invalidReference();
        } catch (RuntimeException | IOException failure) {
            throw invalidReference();
        }
    }

    private static void createPrivateResult(Path result) throws IOException {
        try {
            Files.createFile(result, PosixFilePermissions.asFileAttribute(FILE_MODE));
            PosixFileAttributes attributes = Files.readAttributes(result, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributes directory = Files.readAttributes(result.getParent(), PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || !attributes.permissions().equals(FILE_MODE) ||
                    attributes.size() != 0 || !attributes.owner().equals(directory.owner()) ||
                    !directory.isDirectory() || directory.isSymbolicLink() ||
                    !directory.permissions().equals(DIRECTORY_MODE)) throw invalidReference();
        } catch (RuntimeException | IOException failure) {
            throw invalidReference();
        }
    }

    private static byte[] readPrivateResult(Path stagingDirectory, Path result, int maximum) throws IOException {
        try {
            PosixFileAttributes before = Files.readAttributes(result, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String, Object> beforeUnix = Files.readAttributes(result,
                    "unix:nlink,dev,ino,ctime,lastModifiedTime", LinkOption.NOFOLLOW_LINKS);
            UserFileIdentity identity = validateResult(before, beforeUnix, stagingDirectory, maximum);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) before.size());
            Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = Files.newByteChannel(result, options)) {
                ByteBuffer buffer = ByteBuffer.allocate(Math.min(1024, maximum + 1));
                int total = 0;
                while (total <= maximum) {
                    buffer.clear();
                    buffer.limit(Math.min(buffer.capacity(), maximum + 1 - total));
                    int read = channel.read(buffer);
                    if (read < 0) break;
                    if (read == 0) throw invalidReference();
                    total += read;
                    if (total > maximum) throw invalidReference();
                    output.write(buffer.array(), 0, read);
                }
                if (total != before.size()) throw invalidReference();
            }
            PosixFileAttributes after = Files.readAttributes(result, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Map<String, Object> afterUnix = Files.readAttributes(result,
                    "unix:nlink,dev,ino,ctime,lastModifiedTime", LinkOption.NOFOLLOW_LINKS);
            if (!identity.equals(validateResult(after, afterUnix, stagingDirectory, maximum)) || output.size() != after.size()) {
                throw invalidReference();
            }
            return output.toByteArray();
        } catch (RuntimeException | IOException failure) {
            throw invalidReference();
        }
    }

    private static UserFileIdentity validateResult(PosixFileAttributes attributes, Map<String, Object> unix,
                                                   Path stagingDirectory, int maximum) throws IOException {
        PosixFileAttributes directory = Files.readAttributes(stagingDirectory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || !attributes.permissions().equals(FILE_MODE) ||
                !attributes.owner().equals(directory.owner()) || attributes.size() < 1 || attributes.size() > maximum ||
                ((Number) unix.get("nlink")).longValue() != 1) throw invalidReference();
        return new UserFileIdentity(attributes.fileKey(), attributes.size(), String.valueOf(unix.get("dev")),
                String.valueOf(unix.get("ino")), String.valueOf(unix.get("ctime")),
                String.valueOf(unix.get("lastModifiedTime")), attributes.owner(), Set.copyOf(attributes.permissions()));
    }

    private static boolean terminateAndReap(Process child) {
        try {
            child.getOutputStream().close();
        } catch (IOException ignored) {
            // Closing a finished or failing child stream is best effort.
        }
        if (!child.isAlive()) return true;
        child.destroy();
        try {
            if (!child.waitFor(100, TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                return child.waitFor(900, TimeUnit.MILLISECONDS);
            }
            return true;
        } catch (InterruptedException interrupted) {
            child.destroyForcibly();
            boolean reaped = false;
            try {
                reaped = child.waitFor(900, TimeUnit.MILLISECONDS);
            } catch (InterruptedException again) {
                // Keep the startup executor's interruption state below.
            }
            Thread.currentThread().interrupt();
            return reaped;
        }
    }

    private static boolean removePrivateStagingDirectory(Path directory) {
        boolean removed = true;
        for (String name : List.of(RESULT_NAME, "private-file-reader.mjs")) {
            try {
                Files.deleteIfExists(directory.resolve(name));
            } catch (IOException | RuntimeException ignored) {
                removed = false;
            }
        }
        try {
            Files.deleteIfExists(directory);
        } catch (IOException | RuntimeException ignored) {
            removed = false;
        }
        return removed;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw invalidReference();
        }
    }

    private static IOException invalidReference() {
        return new IOException("private local reference is invalid or unavailable");
    }

    private record UserFileIdentity(Object key, long size, String device, String inode, String changeTime,
                                    String modifiedTime, java.nio.file.attribute.UserPrincipal owner,
                                    Set<PosixFilePermission> permissions) {}
}
