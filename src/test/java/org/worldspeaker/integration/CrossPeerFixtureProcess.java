package org.worldspeaker.integration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Bounded process owner for the compiled real TypeScript transport used by Java cross-peer tests. */
public final class CrossPeerFixtureProcess implements AutoCloseable {
    private static final int READY_TIMEOUT_SECONDS = 3;
    private static final int STOP_TIMEOUT_SECONDS = 3;

    private final Process process;
    private final LinkedBlockingQueue<String> output = new LinkedBlockingQueue<>();
    private final StringBuilder errors = new StringBuilder();
    private final OutputStreamWriter input;

    private CrossPeerFixtureProcess(Path repository, int port, String secret, Path bundleFile) throws IOException {
        Path fixture = repository.resolve("companion/dist/test/java-bound-client-fixture.js");
        if (!Files.isRegularFile(fixture)) throw new IOException("compiled cross-peer fixture is missing: " + fixture);
        List<String> command = new ArrayList<>(List.of("node", fixture.toString(), Integer.toString(port), secret));
        if (bundleFile != null) command.add(bundleFile.toAbsolutePath().toString());
        process = new ProcessBuilder(command)
                .directory(repository.toFile()).start();
        input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        Thread stdout = new Thread(() -> pump(process.getInputStream(), output::offer),
                "worldspeaker-ts-fixture-stdout");
        stdout.setDaemon(true);
        stdout.start();
        Thread stderr = new Thread(() -> pump(process.getErrorStream(), this::appendError),
                "worldspeaker-ts-fixture-stderr");
        stderr.setDaemon(true);
        stderr.start();
    }

    public static CrossPeerFixtureProcess start(Path repository, int port, String secret) throws IOException {
        return new CrossPeerFixtureProcess(repository.toAbsolutePath(), port, secret, null);
    }

    public static CrossPeerFixtureProcess start(Path repository, int port, String secret, Path bundleFile) throws IOException {
        return new CrossPeerFixtureProcess(repository.toAbsolutePath(), port, secret, bundleFile);
    }

    public JsonObject awaitStarted() throws Exception {
        return awaitEvent(event -> "started".equals(string(event, "kind")), READY_TIMEOUT_SECONDS);
    }

    public JsonObject awaitEvent(Predicate<JsonObject> predicate, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        List<String> deferred = new ArrayList<>();
        try {
            while (System.nanoTime() < deadline) {
                String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (line == null) break;
                JsonObject event;
                try {
                    event = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException invalid) {
                    throw new AssertionError("TypeScript fixture wrote invalid JSON: " + line + diagnostic(), invalid);
                }
                if ("driver-error".equals(string(event, "kind"))) {
                    throw new AssertionError("TypeScript fixture failed: " + event + diagnostic());
                }
                if (predicate.test(event)) return event;
                deferred.add(line);
            }
            throw new AssertionError("timed out waiting for TypeScript fixture event" + diagnostic());
        } finally {
            deferred.forEach(output::offer);
        }
    }

    public void command(String command) throws IOException {
        input.write(command);
        input.write('\n');
        input.flush();
    }

    private synchronized void appendError(String line) {
        errors.append(line).append('\n');
    }

    private String diagnostic() {
        synchronized (this) {
            return "; childAlive=" + process.isAlive() + "; stderr=" + errors;
        }
    }

    private static String string(JsonObject object, String field) {
        return object.get(field).getAsString();
    }

    private static void pump(java.io.InputStream stream, java.util.function.Consumer<String> consumer) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) consumer.accept(line);
        } catch (IOException ignored) {
            // Process shutdown closes the pipes; the owner observes its explicit stop event.
        }
    }

    @Override
    public void close() {
        try {
            if (process.isAlive()) {
                command("STOP");
                awaitEvent(event -> "stopped".equals(string(event, "kind")), STOP_TIMEOUT_SECONDS);
                if (!process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly();
            }
        } catch (Exception ignored) {
            process.destroyForcibly();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                process.destroyForcibly();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                try {
                    process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
