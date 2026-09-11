# IRL-MC Director Server

A server-side cinematic camera director for **Minecraft 26.1.2 / NeoForge**.
It turns an ordinary player account into an **auto-following camera** and
cuts between cinematic shots — with **no client mod required**. Vanilla
clients connect normally; the "camera" is just a spectator player that the
server moves with teleports.

```
                       ┌──────────────────────────┐
   target player  ───► │  DirectorServer (tick)   │
   (e.g. you)          │  pick target → shot pose │
                       └────────────┬─────────────┘
                                    │ spectator teleport (server-side)
                                    ▼
                       ┌──────────────────────────┐
   vanilla client  ◄── │  camera account          │
   (any player)        │  directorcam             │
                       └──────────────────────────┘
```

Typical use: run a headless camera account (e.g. on Xvfb) that joins the
server, is switched to spectator, and is driven around the target. Capture
that client's window (or its own stream) and you have a live third-person
cinematic feed of your world — great for OBS, streams, or recording.

## Features

- **Server-side only** — works with vanilla clients, no client mod to install.
- **Auto camera account** — a configured account (default `DirectorCam`)
  automatically enters director mode when it joins.
- **Shot library** — orbit, flyby, crane, dynamic follow (behind/front/POV),
  static tripod, and dolly (`MOVE`).
- **Multiplayer rotation** — with several players online it rotates the
  target; `targetName` locks onto one player.
- **Smooth transitions** — a configurable blend between shots.
- **Collision guard** — a ray from the target pulls the camera out of solid
  blocks.
- **Persistence** — sessions/config survive restarts; a stranded camera is
  restored on the next login.
- **Live commands** — `/director on|off|next|status|interval|target`.

## Requirements

- Minecraft **26.1.2**
- NeoForge **26.1.2.106** (or compatible)
- **Java 25** to build and run
- Works on dedicated servers. No client-side mod needed.

## Build

```bash
./gradlew build
# -> build/libs/irlmc-director-server-<version>.jar
```

You need a JDK 25 toolchain. If Gradle can't find one automatically, set
`org.gradle.java.installations.paths` in `gradle.properties` to your JDK 25
directory (the line is present but commented out).

Development runs are configured in `build.gradle`:

```bash
./gradlew runServer     # dedicated server
./gradlew runCamera     # headless camera client (CAMNAME/CAMHOST/CAMPORT env)
./gradlew runDummy      # a second dummy client
./gradlew runCamprod    # production camera client, 1280x720
```

## Install

1. Drop the built jar into `mods/` on the server.
2. Start the server once to generate `config/irlmc-director-server.properties`.
3. Set `cameraAccount` to the name of the account that should be the camera.
4. Have that account join (vanilla client works). It is put in spectator and
   starts directing automatically.

## Usage

Commands (permission level `permissionLevel`, default 2 = op):

| Command | Effect |
| --- | --- |
| `/director` or `/director status` | Show state, current target and shot |
| `/director on` | Start directing (you become the camera) |
| `/director off` | Stop and restore your game mode/position |
| `/director next` | Cut to the next target now |
| `/director interval <5-300>` | Seconds between shot changes |
| `/director target <player>` | Lock onto one player |
| `/director target clear` | Back to auto-rotate |

## Configuration

`config/irlmc-director-server.properties`:

```properties
cameraAccount=DirectorCam   # account auto-driven as the camera
autoCamera=true             # start directing when that account joins
targetName=                 # empty = auto-rotate
intervalSec=20              # seconds between shots
minDistance=5.0             # tie-break distance filter
orbitRadius=4.0
orbitSpeed=2.0
followDistance=4.0
followHeight=1.0
smoothness=0.08
permissionLevel=2
```

Sessions are stored in `config/irlmc-director-server-sessions.properties` so a
camera caught mid-session is restored after a restart.

## Known limitations / roadmap

- The shot pool is currently chosen with uniform randomness; there is no
  curation, no-repeat scheduling, or context awareness yet.
- The `MOVE` dolly drift is unbounded over long holds.
- FLYBY/CRANE are authored for ~30s arcs but are cut at `intervalSec`.
- A held session on re-login currently restores the player and skips the
  auto-camera start, which can leave the camera as a survival player.
- Collision handling is a single ray; the camera can still clip ceilings or
  film the target through a wall.

Planned work: a weighted, no-repeat shot scheduler; line-of-sight and proper
sphere-cast collision; rule-of-thirds framing and target-velocity look-ahead;
a small "director brain" that adapts shots to player state; per-shot config.

## License

MIT — see [LICENSE](LICENSE). Third-party attribution in [NOTICE](NOTICE).
