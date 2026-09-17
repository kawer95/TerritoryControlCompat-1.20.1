package com.arxyt.territorycontrolcompat.compat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Queues campaign failures for a dedicated rotating game-log file.
 *
 * <p>Planning runs on the server thread, so this class is deliberately used only after a whole
 * planning attempt or a campaign has failed.  It never receives per-candidate diagnostics and
 * therefore cannot recreate the hot-path log flood in {@code latest.log}.  If the independent
 * file cannot be written, the normal logger is the fallback so that the loss of failure evidence
 * is visible to an administrator.</p>
 */
final class CampaignFailureLog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String FILE_NAME = "territorycontrol-campaign-failures.log";
    private static final long MAX_BYTES = 8L * 1024L * 1024L;
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(256);
    private static final AtomicLong DROPPED = new AtomicLong();
    private static volatile boolean running;
    private static Thread writer;

    private CampaignFailureLog() {
    }

    static void record(String director, String message) {
        ensureWriter();
        String line = Instant.now() + " [" + director + "] " + message + System.lineSeparator();
        if (!QUEUE.offer(line)) DROPPED.incrementAndGet();
    }

    static synchronized void shutdown() {
        running = false;
        Thread current = writer;
        if (current == null) return;
        current.interrupt();
        try {
            current.join(2_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        writer = null;
    }

    static int queued() { return QUEUE.size(); }
    static long dropped() { return DROPPED.get(); }

    private static synchronized void ensureWriter() {
        if (running && writer != null) return;
        running = true;
        writer = new Thread(CampaignFailureLog::writeLoop, "territory-campaign-failure-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeLoop() {
        Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve(FILE_NAME);
        while (running || !QUEUE.isEmpty()) {
            try {
                String line = QUEUE.poll(500L, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                long dropped = DROPPED.getAndSet(0L);
                if (dropped > 0L) append(file, Instant.now() + " [CampaignFailureLog] dropped=" + dropped
                        + " reason=queue_full" + System.lineSeparator());
                append(file, line);
            } catch (InterruptedException interrupted) {
                if (running) Thread.currentThread().interrupt();
            } catch (IOException exception) {
                LOGGER.error("Unable to append campaign failure log at {}", file, exception);
            }
        }
    }

    private static void append(Path file, String line) throws IOException {
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        if (Files.isRegularFile(file) && Files.size(file) + bytes.length > MAX_BYTES) rotate(file);
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void rotate(Path file) throws IOException {
        Path first = file.resolveSibling(FILE_NAME + ".1");
        Path second = file.resolveSibling(FILE_NAME + ".2");
        Path third = file.resolveSibling(FILE_NAME + ".3");
        Files.deleteIfExists(third);
        if (Files.exists(second)) Files.move(second, third, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(first)) Files.move(first, second, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(file)) Files.move(file, first, StandardCopyOption.REPLACE_EXISTING);
    }
}
