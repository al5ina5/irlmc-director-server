package com.irlmc.directorserver;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(IrlmcDirectorServer.MOD_ID)
public class IrlmcDirectorServer {
    public static final String MOD_ID = "irlmc_director_server";

    public IrlmcDirectorServer(IEventBus modBus) {
        // Game-bus handlers (tick, commands, login/out) self-register.
        // Works with vanilla clients: spectator + teleport only.
    }
}
