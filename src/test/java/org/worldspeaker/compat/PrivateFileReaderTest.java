package org.worldspeaker.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateFileReaderTest {
    @TempDir Path temporaryDirectory;

    @Test
    void rejectsFileReplacementGrowthAndNewHardLinkAfterDescriptorOpen() throws Exception {
        Path directory = privateDirectory("descriptor-races");
        byte[] original = "descriptor-bound bytes".getBytes(StandardCharsets.UTF_8);

        Path replaced = privateFile(directory.resolve("replaced.txt"), original);
        Path moved = directory.resolve("descriptor-opened.txt");
        AtomicBoolean replacementHookRan = new AtomicBoolean();
        IOException replacement = assertThrows(IOException.class, () -> PrivateFileReader.readForTest(
                replaced, StartupConfig.MAX_BOOTSTRAP_BYTES, false, 2_000,
                () -> {
                    try {
                        replacementHookRan.set(true);
                        Files.move(replaced, moved);
                        Files.write(replaced, original);
                        Files.setPosixFilePermissions(replaced, PosixFilePermissions.fromString("rw-------"));
                    } catch (IOException failure) {
                        throw new AssertionError("test replacement failed", failure);
                    }
                }, null));
        assertTrue(replacementHookRan.get(), "the replacement must occur after the helper opens the descriptor");
        assertValueFree(replacement);

        Path grown = privateFile(directory.resolve("grown.txt"), original);
        AtomicBoolean growthHookRan = new AtomicBoolean();
        IOException growth = assertThrows(IOException.class, () -> PrivateFileReader.readForTest(
                grown, StartupConfig.MAX_BOOTSTRAP_BYTES, false, 2_000,
                () -> {
                    try {
                        growthHookRan.set(true);
                        Files.write(grown, new byte[]{'!'}, java.nio.file.StandardOpenOption.APPEND);
                    } catch (IOException failure) {
                        throw new AssertionError("test growth failed", failure);
                    }
                }, null));
        assertTrue(growthHookRan.get(), "growth must occur after the helper opens the descriptor");
        assertValueFree(growth);

        Path linked = privateFile(directory.resolve("linked.txt"), original);
        Path extraLink = directory.resolve("linked-alias.txt");
        AtomicBoolean linkHookRan = new AtomicBoolean();
        IOException link = assertThrows(IOException.class, () -> PrivateFileReader.readForTest(
                linked, StartupConfig.MAX_BOOTSTRAP_BYTES, false, 2_000,
                () -> {
                    try {
                        linkHookRan.set(true);
                        Files.createLink(extraLink, linked);
                    } catch (IOException failure) {
                        throw new AssertionError("test hard link failed", failure);
                    }
                }, null));
        assertTrue(linkHookRan.get(), "the hard link must be added after the helper opens the descriptor");
        assertValueFree(link);
    }

    @Test
    void rejectsReplacementOfAPathAncestorAfterDescriptorOpen() throws Exception {
        Path parent = privateDirectory("ancestor-race");
        Path source = privateFile(parent.resolve("source.txt"), "same bytes".getBytes(StandardCharsets.UTF_8));
        Path movedParent = parent.resolveSibling(parent.getFileName() + "-opened");
        AtomicBoolean ancestorHookRan = new AtomicBoolean();

        IOException failure = assertThrows(IOException.class, () -> PrivateFileReader.readForTest(
                source, StartupConfig.MAX_BOOTSTRAP_BYTES, false, 2_000,
                () -> {
                    try {
                        ancestorHookRan.set(true);
                        Files.move(parent, movedParent);
                        Files.createDirectory(parent);
                        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
                        Files.write(parent.resolve("source.txt"), "same bytes".getBytes(StandardCharsets.UTF_8));
                        Files.setPosixFilePermissions(parent.resolve("source.txt"), PosixFilePermissions.fromString("rw-------"));
                    } catch (IOException problem) {
                        throw new AssertionError("test ancestor replacement failed", problem);
                    }
                }, null));
        assertTrue(ancestorHookRan.get(), "the ancestor replacement must occur after the helper opens the descriptor");
        assertValueFree(failure);
    }

    @Test
    void fifoReplacementUsesNonblockingDescriptorAndTwentyFourStartupWorkersFinishWithinFiveSeconds() throws Exception {
        Path bootstrap = StartupConfigTestFiles.createPrivate(temporaryDirectory, java.util.Map.of());
        Path fifo = bootstrap.getParent().resolve("startup.json");
        Files.delete(fifo);
        createFifo(fifo);

        AtomicReference<ProcessHandle> fifoChild = new AtomicReference<>();
        long singleStarted = System.nanoTime();
        IOException fifoFailure = assertThrows(IOException.class, () -> PrivateFileReader.readForTest(
                fifo, StartupConfig.MAX_BUNDLE_BYTES, false, 3_000, null, fifoChild::set));
        assertValueFree(fifoFailure);
        assertTrue(Duration.ofNanos(System.nanoTime() - singleStarted).compareTo(Duration.ofSeconds(1)) < 0,
                "FIFO descriptor open exceeded its short nonblocking bound");
        assertNotNull(fifoChild.get(), "the actual packaged Node helper must run for the FIFO check");
        assertFalse(fifoChild.get().isAlive(), "the owned FIFO reader child must be reaped");

        int workers = 24;
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> new Thread(runnable, "private-file-fifo-startup"));
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        long started = System.nanoTime();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("worker start barrier timed out");
                    IOException rejected = assertThrows(IOException.class, () -> StartupConfig.load(bootstrap));
                    assertValueFree(rejected);
                    return null;
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS), "all 24 config-load workers should be scheduled");
            started = System.nanoTime();
            start.countDown();
            long deadline = started + TimeUnit.SECONDS.toNanos(5);
            for (Future<?> future : futures) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "24 startup readers exceeded the five-second bound");
                future.get(remaining, TimeUnit.NANOSECONDS);
            }
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5),
                    "24 startup readers exceeded the five-second bound");
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS), "startup worker threads must terminate");
        }
        assertEquals(0, PrivateFileReader.activeChildrenForTest(), "all startup-reader children must be reaped");
        assertFalse(hasPrivateReaderJavaThread(), "the reader must not leave a blocked Java reader thread");
    }

    @Test
    void reapsOwnedChildAfterCallbackFailureAndDeadlineWithoutReaderThreads() throws Exception {
        Path source = privateFile(privateDirectory("child-lifecycle").resolve("source.txt"),
                "bounded input".getBytes(StandardCharsets.UTF_8));
        AtomicReference<ProcessHandle> thrownChild = new AtomicReference<>();
        AtomicBoolean thrownHookRan = new AtomicBoolean();
        IOException thrown = assertThrows(IOException.class, () -> PrivateFileReader.readForTest(
                source, StartupConfig.MAX_BOOTSTRAP_BYTES, false, 2_000,
                () -> {
                    thrownHookRan.set(true);
                    throw new IllegalStateException("test-only callback failure");
                }, thrownChild::set));
        assertValueFree(thrown);
        assertTrue(thrownHookRan.get(), "the callback error must happen after descriptor open");
        assertNotNull(thrownChild.get());
        assertFalse(thrownChild.get().isAlive(), "the owned child must be reaped after a callback failure");

        AtomicReference<ProcessHandle> timedOutChild = new AtomicReference<>();
        AtomicBoolean timeoutHookRan = new AtomicBoolean();
        IOException timeout = assertThrows(IOException.class, () -> PrivateFileReader.readForTest(
                source, StartupConfig.MAX_BOOTSTRAP_BYTES, false, 5,
                () -> {
                    timeoutHookRan.set(true);
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test-only timeout callback interrupted");
                    }
                }, timedOutChild::set));
        assertValueFree(timeout);
        assertTrue(timeoutHookRan.get(), "the deadline must expire after descriptor open");
        assertNotNull(timedOutChild.get());
        assertFalse(timedOutChild.get().isAlive(), "the owned child must be reaped after its deadline");
        assertEquals(0, PrivateFileReader.activeChildrenForTest(), "all failed or timed-out children must be reaped");
        assertFalse(hasPrivateReaderJavaThread(), "no blocked private-file-reader thread may remain");
    }

    @Test
    void packagedHelperBytesMatchTheRuntimePinAndAreBounded() throws Exception {
        byte[] helper;
        try (var stream = PrivateFileReaderTest.class.getResourceAsStream("/org/worldspeaker/compat/private-file-reader.mjs")) {
            assertNotNull(stream);
            helper = stream.readNBytes(16_385);
        }
        assertTrue(helper.length > 0 && helper.length <= 16_384);
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(helper));
        assertEquals(PrivateFileReader.HELPER_SHA256, hash);
    }

    private Path privateDirectory(String name) throws IOException {
        Path directory = Files.createTempDirectory(temporaryDirectory.toRealPath(), "worldspeaker-" + name + "-");
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return directory;
    }

    private static Path privateFile(Path path, byte[] bytes) throws IOException {
        Files.write(path, bytes);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        return path;
    }

    private static void createFifo(Path fifo) throws Exception {
        Process mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        assertTrue(mkfifo.waitFor(2, TimeUnit.SECONDS), "mkfifo must finish promptly");
        assertEquals(0, mkfifo.exitValue(), "mkfifo must create the replacement FIFO");
    }

    private static void assertValueFree(IOException failure) {
        assertFalse(failure.getMessage().contains("descriptor-bound bytes"));
        assertFalse(failure.getMessage().contains("same bytes"));
        assertFalse(failure.getMessage().contains("AAAAAAAA"));
    }

    private static boolean hasPrivateReaderJavaThread() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.isAlive() &&
                thread.getName().contains("private-file-reader"));
    }
}
