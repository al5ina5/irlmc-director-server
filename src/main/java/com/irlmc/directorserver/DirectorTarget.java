package com.irlmc.directorserver;

import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** A follow target resolved live from the server player list each tick. */
public record DirectorTarget(UUID uuid, String name) {
    public ServerPlayer resolve(MinecraftServer server) {
        return server.getPlayerList().getPlayer(uuid);
    }

    public boolean isValid(MinecraftServer server) {
        ServerPlayer p = resolve(server);
        return p != null && !p.isRemoved() && !p.isSpectator();
    }

    public static DirectorTarget of(ServerPlayer p) {
        return new DirectorTarget(p.getUUID(), p.getName().getString());
    }
}
