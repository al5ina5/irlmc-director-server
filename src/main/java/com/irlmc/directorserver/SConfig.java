package com.irlmc.directorserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-side config + persistent sessions, plain properties file. */
public final class SConfig {
    private static final Logger LOG = LoggerFactory.getLogger("irlmc-director-server");
    private static final SConfig INSTANCE = new SConfig();

    public static SConfig get() {
        return INSTANCE;
    }

    /** Camera account name for headless feed use. Empty = manual /director only. */
    public String cameraAccount = "DirectorCam";
    /** Auto-start directing when the camera account joins (headless feed). */
    public boolean autoCamera = true;
    public String targetName = "";
    public int rotationIntervalSec = 20;
    public double minRotationDistance = 5.0;
    public double orbitRadius = 4.0;
    public double orbitSpeedDegPerTick = 2.0;
    public double followDistance = 4.0;
    public double followHeight = 1.0;
    public double dynamicSmoothness = 0.08;
    public int smoothTicks = 60;
    public int permissionLevel = 2;
    /** Default: one stable, world-locked follow angle — best for IRL/stream use. */
    public List<ShotType> pool = new ArrayList<>(List.of(ShotType.FOLLOW));

    // ---- FOLLOW / STEADY shot: a fixed, elevated follow camera ----
    /** "velocity" slowly settles behind movement direction; "steady" keeps a fixed compass angle. */
    public String followMode = "velocity";
    /** Per-tick alignment toward the movement direction (lower = lazier). */
    public double followAlign = 0.05;
    /** Minimum horizontal speed (blocks/tick) before velocity alignment kicks in. */
    public double followDeadzone = 0.04;

    // ---- STEADY shot: a fixed, elevated, world-locked follow camera ----
    /** Horizontal distance from the target. */
    public double steadyDistance = 7.0;
    /** Height above the target's feet. */
    public double steadyHeight = 4.5;
    /** Compass direction the camera sits toward (MC yaw: 0 = south of the target). */
    public double steadyAngleDeg = 0.0;
    /** Height on the target the camera aims at. */
    public double steadyLookHeight = 1.2;
    /** Per-tick follow factor (0..1). Lower = lazier, more tolerant of quick moves. */
    public double steadyFollow = 0.10;

    // ---- spring-arm collision ----
    /** Minimum distance the arm may retract to (blocks). */
    public double armMin = 2.0;
    /** Near-plane clearance kept from any obstruction (blocks). */
    public double armMargin = 0.45;
    /** Camera radius approximated by the sphere cast (blocks). */
    public double armRadius = 0.5;
    /** Per-tick retract factor when a wall intrudes (higher = snaps in faster). */
    public double armRetract = 0.55;
    /** Per-tick extend factor when clear (lower = eases back out slower). */
    public double armExtend = 0.05;

    /** Consecutive shots that may not be reused (keeps cuts varied). */
    public int noRepeatWindow = 2;
    /** Per-shot selection weight; 0 disables a type without removing it. */
    public final Map<ShotType, Double> shotWeights = new EnumMap<>(ShotType.class);

    public SConfig() {
        // Curated defaults: favour the shots that read well on a third-person feed.
        shotWeights.put(ShotType.FOLLOW, 1.0);
        shotWeights.put(ShotType.STEADY, 1.0);
        shotWeights.put(ShotType.ORBIT, 2.0);
        shotWeights.put(ShotType.CRANE, 2.0);
        shotWeights.put(ShotType.DYNAMIC_BEHIND, 3.0);
        shotWeights.put(ShotType.DYNAMIC_FRONT, 1.0);
        shotWeights.put(ShotType.FLYBY, 1.0);
        shotWeights.put(ShotType.BEHIND, 1.0);
        shotWeights.put(ShotType.MOVE, 1.0);
        shotWeights.put(ShotType.FRONT, 0.5);
        shotWeights.put(ShotType.FIX, 0.5);
        shotWeights.put(ShotType.DYNAMIC_POV, 0.0);
    }

    public double weightOf(ShotType type) {
        Double w = shotWeights.get(type);
        return w == null ? 1.0 : w;
    }

    private String weightsString() {
        StringBuilder sb = new StringBuilder();
        for (ShotType t : ShotType.values()) {
            if (shotWeights.containsKey(t)) {
                if (sb.length() > 0) sb.append(',');
                sb.append(t.name()).append(':').append(shotWeights.get(t));
            }
        }
        return sb.toString();
    }

    private void parseWeights(String value) {
        shotWeights.clear();
        if (value == null || value.isBlank()) return;
        for (String part : value.split(",")) {
            String[] kv = part.split(":", 2);
            if (kv.length != 2) continue;
            try {
                shotWeights.put(ShotType.valueOf(kv[0].trim().toUpperCase()),
                        Double.parseDouble(kv[1].trim()));
            } catch (Exception ignored) {
                // Unknown type or bad number: keep defaults for the rest.
            }
        }
    }

    private Path path() {
        return FMLPaths.CONFIGDIR.get().resolve("irlmc-director-server.properties");
    }

    public synchronized void load() {
        Path p = path();
        if (!Files.exists(p)) {
            save();
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(p)) {
            props.load(in);
            cameraAccount = props.getProperty("cameraAccount", "DirectorCam").trim();
            autoCamera = Boolean.parseBoolean(props.getProperty("autoCamera", "true"));
            targetName = props.getProperty("targetName", "").trim();
            rotationIntervalSec = Math.max(5, Integer.parseInt(props.getProperty("intervalSec", "20")));
            minRotationDistance = Double.parseDouble(props.getProperty("minDistance", "5.0"));
            orbitRadius = Double.parseDouble(props.getProperty("orbitRadius", "4.0"));
            orbitSpeedDegPerTick = Double.parseDouble(props.getProperty("orbitSpeed", "2.0"));
            followDistance = Double.parseDouble(props.getProperty("followDistance", "4.0"));
            followHeight = Double.parseDouble(props.getProperty("followHeight", "1.0"));
            dynamicSmoothness = Double.parseDouble(props.getProperty("smoothness", "0.08"));
            permissionLevel = Math.max(0, Math.min(4, Integer.parseInt(props.getProperty("permissionLevel", "2"))));
            noRepeatWindow = Math.max(0, Integer.parseInt(props.getProperty("noRepeatWindow", "2")));
            steadyDistance = Double.parseDouble(props.getProperty("steadyDistance", "7.0"));
            steadyHeight = Double.parseDouble(props.getProperty("steadyHeight", "4.5"));
            steadyAngleDeg = Double.parseDouble(props.getProperty("steadyAngleDeg", "0.0"));
            steadyLookHeight = Double.parseDouble(props.getProperty("steadyLookHeight", "1.2"));
            steadyFollow = Double.parseDouble(props.getProperty("steadyFollow", "0.10"));
            followMode = props.getProperty("followMode", "velocity").trim();
            followAlign = Double.parseDouble(props.getProperty("followAlign", "0.05"));
            followDeadzone = Double.parseDouble(props.getProperty("followDeadzone", "0.04"));
            armMin = Double.parseDouble(props.getProperty("armMin", "2.0"));
            armMargin = Double.parseDouble(props.getProperty("armMargin", "0.45"));
            armRadius = Double.parseDouble(props.getProperty("armRadius", "0.5"));
            armRetract = Double.parseDouble(props.getProperty("armRetract", "0.55"));
            armExtend = Double.parseDouble(props.getProperty("armExtend", "0.05"));
            parseWeights(props.getProperty("weights", weightsString()));
        } catch (Exception e) {
            LOG.warn("Failed to load director server config", e);
        }
    }

    public synchronized void save() {
        Properties props = new Properties();
        props.setProperty("cameraAccount", cameraAccount);
        props.setProperty("autoCamera", Boolean.toString(autoCamera));
        props.setProperty("targetName", targetName);
        props.setProperty("intervalSec", Integer.toString(rotationIntervalSec));
        props.setProperty("minDistance", Double.toString(minRotationDistance));
        props.setProperty("orbitRadius", Double.toString(orbitRadius));
        props.setProperty("orbitSpeed", Double.toString(orbitSpeedDegPerTick));
        props.setProperty("followDistance", Double.toString(followDistance));
        props.setProperty("followHeight", Double.toString(followHeight));
        props.setProperty("smoothness", Double.toString(dynamicSmoothness));
        props.setProperty("permissionLevel", Integer.toString(permissionLevel));
        props.setProperty("noRepeatWindow", Integer.toString(noRepeatWindow));
        props.setProperty("steadyDistance", Double.toString(steadyDistance));
        props.setProperty("steadyHeight", Double.toString(steadyHeight));
        props.setProperty("steadyAngleDeg", Double.toString(steadyAngleDeg));
        props.setProperty("steadyLookHeight", Double.toString(steadyLookHeight));
        props.setProperty("steadyFollow", Double.toString(steadyFollow));
        props.setProperty("followMode", followMode);
        props.setProperty("followAlign", Double.toString(followAlign));
        props.setProperty("followDeadzone", Double.toString(followDeadzone));
        props.setProperty("armMin", Double.toString(armMin));
        props.setProperty("armMargin", Double.toString(armMargin));
        props.setProperty("armRadius", Double.toString(armRadius));
        props.setProperty("armRetract", Double.toString(armRetract));
        props.setProperty("armExtend", Double.toString(armExtend));
        props.setProperty("weights", weightsString());
        try {
            Files.createDirectories(path().getParent());
            try (OutputStream out = Files.newOutputStream(path())) {
                props.store(out, "irlmc-director-server");
            }
        } catch (IOException e) {
            LOG.warn("Failed to save director server config", e);
        }
    }
}
