![banner](https://cdn.modrinth.com/data/cached_images/f8ff2a0bd1158cd5b49113d86a070a946c4fe974.png)

<p align="center">
  <a href="https://github.com/palicz/Soul-Link-Speedrun">
    <img src="https://cdn.modrinth.com/data/cached_images/14bb5f6380dbf0e9a0bc20179ef4d9728b0f88d9.png" alt="github_link">
  </a>
  <a href="https://discord.gg/JAUa2DEHfp">
    <img src="https://cdn.modrinth.com/data/cached_images/e03629e989e9744138963451f8877bb5d65aceea.png" alt="discord">
  </a>
  <a href="https://modrinth.com/mod/soul-link-speedrun">
    <img src="https://cdn.modrinth.com/data/cached_images/2df5ae65196aa7a4a0aef20e208c0005ff06471f.png" alt="modrinth_link">
  </a>
  <a href="https://www.curseforge.com/minecraft/mc-mods/soul-link-speedrun">
    <img src="https://cdn.modrinth.com/data/cached_images/59902fedac100ce3cc3249dc677e76281aa597d0.png" alt="curseforge_link">
  </a>
</p>

![divider](https://cdn.modrinth.com/data/cached_images/f1555fa7709bdbd4776c2bf3fa8fd763f659e052.png)


![about](https://cdn.modrinth.com/data/cached_images/9872283263bf494fa11d3c3b54326d1b5f64c0ba_0.webp)

Transforms multiplayer survival into a cooperative speedrun experience where all players **share the same health, hunger, and saturation**. Each run generates completely **isolated temporary worlds** that are automatically cleaned up after completion, ensuring a fresh start every time.

Work together with your team to defeat the Ender Dragon as fast as possible. If **any player dies, the run ends for everyone** - making teamwork and protection essential. The mod features a precise two-phase timer system that tracks your speedrun time from the moment you start until victory or defeat.

_Since this mod is server-side only, players connecting to a server with this mod do not need to install it on their client. It works seamlessly with any Fabric server setup._

![divider](https://cdn.modrinth.com/data/cached_images/f1555fa7709bdbd4776c2bf3fa8fd763f659e052.png)

![features](https://cdn.modrinth.com/data/cached_images/95c27e9d0693b6d1f5eba2a6792c9a36bae2c09b_0.webp)

Give your multiplayer server a true cooperative challenge.

### Shared Vitality: Health & Hunger
All players share a single, synchronized pool of Health, Hunger, and Saturation. If one player takes damage, everyone takes damage. When one player eats, everyone’s hunger bar refills.

<div align="center">

![health_sync](https://cdn.modrinth.com/data/cached_images/d7b69a48fd37272862fa6022f2a26066ca1d7164.gif)

_**Player A** gets attacked by zombies and **Player B** takes damage too_

![eating_sync](https://cdn.modrinth.com/data/cached_images/c704a23b7d2fa626396ab52bfe5f4016e100da3b.gif)

_**Player A** eats and **Player B**'s hunger reduces too_

</div>

### Customize Your Challenge (New!)
Tailor the difficulty to your team using the new **Settings GUI**. Toggle specific mechanics to create the ultimate speedrun gauntlet:

* **Shared Potions:** If one player drinks a Speed potion, the whole team speeds up. But be careful—negative effects like Poison are shared too!
* **Shared Jumping:** Coordinate your movement! When enabled, jumps are synchronized across all players.
* **Half-Heart Mode:** For the absolute pros. The team's max health is capped at 0.5 hearts. One hit ends the run.
* **Difficulty:** Adjust the world difficulty directly within the settings menu.

### One Death, All Dead
If a single player makes a fatal mistake -like falling into lava- the run ends immediately for everyone.

<div align="center">

![death](https://cdn.modrinth.com/data/cached_images/daf8fee661cf4686cedd8f8c31a63fb0b5ef4846.gif)

</div>

### The Objective
The run is only considered a success once the Ender Dragon is defeated.

<div align="center">
  
![victory](https://cdn.modrinth.com/data/cached_images/2e1cff3f589246d1cc8d553fd5113abefde2d1a4.gif)

</div>

### Game mechanics & World generation

- A live timer tracks your run in the action bar and captures your final time automatically.
- Late-joining players can hop in mid-run and instantly sync to the current state, or join fresh after a reset.
  
- When a run ends (via death or victory), simply click the text in chat to immediately generate a fresh world and start a new run.

<div align="center">
  
![world_generation](https://cdn.modrinth.com/data/cached_images/33963db02e1e0c6f200463a9b92f391fd7b5e1a5.gif)

</div>

### Commands

- `/start` - Begin a new speedrun attempt (generates fresh worlds)
- `/reset` - Instantly restart the current run (No OP required)
- `/stoprun` - Admin command to stop current run (requires operator)
- `/runinfo` - Display current run state, timer, and shared stats

<div align="center">

[![Essentials](https://cdn.modrinth.com/data/cached_images/0b42e12528e5968b59f756dd2146f0e9610f574d.png)](https://essentials.gg)

</div>

![divider](https://cdn.modrinth.com/data/cached_images/f1555fa7709bdbd4776c2bf3fa8fd763f659e052.png)

![compatibilty](https://cdn.modrinth.com/data/cached_images/859644fb383acbebd77c45c94d3d1e5a71deb389.png)

<div align="center">

| Dependency | Version | Type | Note |
| :--- | :---: | :---: | :--- |
| **Fabric Loader** | `>=0.18.4` | Required | Minimum version 0.18.4 |
| **Fabric API** | `*` | Required | Any version compatible with 1.21.11 |
| **Fantasy** | `0.7.0...` | Included | Bundled in mod JAR |

<br>

| Compatibility | Status | Note |
| :--- | :---: | :--- |
| **Server-side** | ✅ | |
| **Client-side** | ⚠️ | Players don't need to install the mod only the server/host |
| Single-player | ✅ | |
| [Essentials](https://essentials.gg) | ✅ | Only the host needs to have it installed |
| Other Fabric mods | ✅ Generally compatible | May conflict with mods that modify health/hunger, portals, world gen, or death handling |

</div>

![divider](https://cdn.modrinth.com/data/cached_images/f1555fa7709bdbd4776c2bf3fa8fd763f659e052.png)

![credits](https://cdn.modrinth.com/data/cached_images/674749e7f408bb9a2a4714d35641c7377265b06c.png)

👤 **Author:** zenzty

📄 **License:** GNU General Public License v3.0

Copyright (c) 2026 zenzty

Feel free to use this mod in your Modrinth/CurseForge hosted modpacks without asking for permission.

**Credits:**

- Uses the [Fantasy](https://github.com/NucleoidMC/fantasy) library by NucleoidMC for runtime world generation

**Source Code:** [GitHub Repository](https://github.com/palicz/Soul-Link-Speedrun)
