# Soul Link Speedrun

A Fabric mod for Minecraft 1.21.11 that transforms multiplayer survival into a cooperative roguelike speedrun experience with shared health and hunger mechanics.

## Features

### Soul Link System
All players share the same health, hunger, and saturation. When one player takes damage or eats food, all players are affected. This creates a true cooperative experience where teamwork is essential.

### Temporary World Generation
Each run creates completely isolated temporary worlds for the overworld, nether, and end dimensions. Worlds are automatically cleaned up after each run, ensuring a fresh start every time.

### Speedrun Timer
A precise two-phase timer system that:
- Displays elapsed time in the action bar (HH:MM:SS format)
- Tracks time from start until victory or defeat

### Game Mechanics
- **Death = Game Over**: If any player dies, the run ends for everyone
- **Victory Condition**: Defeat the Ender Dragon together to win
- **Late Join Support**: Players joining mid-run are automatically teleported to the active run

## Commands

- `/start` - Begin a new speedrun attempt (generates new worlds)
- `/stoprun` - Admin command to stop the current run (requires operator permissions)
- `/runinfo` - Display current run state, timer, and shared stats

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.18.4 or later
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download the latest release of Soul Link Speedrun
4. Place the mod JAR in your `mods` folder
5. Start your server

## Requirements

- Minecraft 1.21.11
- Fabric Loader >= 0.18.4
- Fabric API
- Java 21 or later
- Fantasy library (included)

## How to Play

1. Start a server with the mod installed
2. Players join and receive a welcome message
3. Use `/start` to begin a new run (generates worlds with random seed)
4. Timer starts automatically
5. Work together with shared health and hunger
6. Defeat the Ender Dragon to win, or die to lose
7. After victory or defeat, use `/start` to begin a new run

## Technical Details

This mod uses the [Fantasy](https://github.com/NucleoidMC/fantasy) library for runtime world generation, allowing temporary dimensions to be created and destroyed on demand. All portal travel is intercepted and redirected to ensure players stay within the temporary world system.

## License

This project is licensed under MIT License.

