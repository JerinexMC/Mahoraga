MAHORAGA - Paper 1.21.11 plugin project

Features
- Type exactly WARA WARA in chat to begin the ritual. The trigger text is hidden.
- Dramatic incantation broadcast and title.
- Nearby entities are frozen, including the summoner.
- Sculk spreads in expanding rings over five seconds (natural ground blocks only).
- Five Warden heartbeat sounds with short darkness pulses.
- A persistent, powered-up Warden spawns after the ritual.
- The summoner is excluded from the Warden's target event.
- One Mahoraga per 15-block area.
- Default cooldown is 1 second.
- /mahoraga cooldown <seconds> changes the global cooldown (OP permission).
- Operators and configured extra players can summon.

IMPORTANT
The sculk spread permanently replaces natural ground blocks. Back up your world first.
The freeze affects nearby entities, including players, dropped items and mobs. Some unusual
entity types may not behave exactly like living mobs. This plugin is designed for Paper 1.21.11.

BUILDING
Requires JDK 21 and Maven. Run:
  mvn -B package
The output JAR is target/mahoraga-1.0.0.jar

INSTALLING
1. Stop your Paper server.
2. Upload target/mahoraga-1.0.0.jar into the server's plugins folder.
3. Start the server.
4. Edit plugins/Mahoraga/config.yml if needed, then restart.

CONFIG
cooldown-seconds: 1
freeze-radius: 15
sculk-radius: 15
sculk-spread: true
extra-allowed-players: [CasualUser, Jerinex]
