package com.irlmc.directorserver;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts/stops the headless camera rig as a detached shell script, so the server
 * mod alone can own the camera lifecycle: server up -> rig up, server down ->
 * rig down (no client running in the background).
 *
 * The scripts themselves launch a new session (setsid), so these calls return
 * immediately and the rig survives independently of the server process.
 */
public final class RigOrchestrator {
    private static final Logger LOG = LoggerFactory.getLogger("irlmc-director-server");
    private static final RigOrchestrator INSTANCE = new RigOrchestrator();

    public static RigOrchestrator get() {
        return INSTANCE;
    }

    public void start(String script) {
        run(script, "start");
    }

    public void stop(String script) {
        run(script, "stop");
    }

    private void run(String script, String label) {
        if (script == null || script.isBlank()) return;
        File f = new File(script);
        if (!f.exists()) {
            LOG.warn("Rig {} script not found: {}", label, script);
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", script);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
            pb.start();
            LOG.info("Rig {} -> {}", label, script);
        } catch (Exception e) {
            LOG.warn("Rig {} failed: {}", label, e.toString());
        }
    }

    /** Best-effort check that the camera feed is up (browser feed port 8080). */
    public boolean isRunning() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 8080), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
