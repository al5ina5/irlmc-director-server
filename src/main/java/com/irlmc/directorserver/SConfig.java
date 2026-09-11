package com.irlmc.directorserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    public List<ShotType> pool = new ArrayList<>(List.of(
            ShotType.ORBIT, ShotType.FLYBY, ShotType.CRANE,
            ShotType.DYNAMIC_BEHIND, ShotType.BEHIND, ShotType.MOVE));

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
