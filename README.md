# Cobblemon Achievements Server

Server-side Fabric mod for Cobblemon 1.7.3 on Minecraft 1.21.1.

## Features

- OP-managed defeat achievements for specific players.
- Target players can toggle whether their achievement is active.
- Server-side Cobblemon battle victory checks.
- Snapshot export of every online player's party and PC Pokemon.
- Export to local JSON files, an HTTP endpoint, or GitHub Contents API.
- Signed remote manifest support for GitHub/IPFS-style data and update checks.
- Server-side player list/nameplate badges using vanilla scoreboard teams.
- Pokemon type badges and gym leader tags that work without a client-side install.

## Requirements

- Minecraft server: `1.21.1`
- Fabric Loader: `0.19.2` or newer
- Fabric API: `0.116.11+1.21.1` or compatible
- Fabric Language Kotlin: `1.13.6+kotlin.2.2.20` or compatible
- Cobblemon Fabric: `1.7.3`
- Java: `21`

## Download And Install

Download the source from GitHub:

```powershell
git clone https://github.com/Potato4507/cobblemon-achievements-server.git
cd cobblemon-achievements-server
```

Build the server mod:

```powershell
.\gradlew.bat build
```

If Java is not on your PATH, set `JAVA_HOME` first:

```powershell
$env:JAVA_HOME = "C:\Path\To\Java21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build
```

Install the built jar:

```powershell
Copy-Item .\build\libs\cobblemon-achievements-server-0.1.4.jar "C:\Path\To\Your\Server\mods\"
```

Then restart the Minecraft server. Do not put this jar in every player's client mods folder; this is a server-side mod.

## Short Badge Commands

Use `/b help` in-game for the quick command guide.

```mcfunction
/b help
/b types
/b set <player> <type>
/b gym <player> on [type]
/b gym <player> off
/b clear <player>
/b list
```

Examples:

```mcfunction
/b set Steve ground
/b gym Steve on ground
/b clear Steve
```

Aliases:

```mcfunction
/badge ...
/typebadge ...
/cach badge ...
```

Valid badge types:

```text
Normal, Fire, Water, Electric, Grass, Ice, Fighting, Poison, Ground, Flying, Psychic, Bug, Rock, Ghost, Dragon, Dark, Steel, Fairy
```

Notes:

- Badge commands require OP permission.
- Badge commands now accept normal player names, even when that player is offline.
- Badges are saved in `config/cobblemon-achievements-server.json`.
- `playerBadgesEnabled` can turn all badge display on or off.
- `playerBadgesOverrideExistingTeams` defaults to `false`, so badges will not steal players from scoreboard teams created by another mod/plugin.
- Set `playerBadgesOverrideExistingTeams` to `true` only if you want this mod to force badge display over other scoreboard teams.

## Easy Achievement Commands

Use `/ach help` in-game for the quick achievement guide.

```mcfunction
/ach help
/ach <player> [title]
/ach add <player> [title]
/ach set <player> [title]
/ach title <player> <title>
/ach remove <player>
/ach list
/ach status
/ach on
/ach off
/ach enable
/ach disable
```

Examples:

```mcfunction
/ach Steve
/ach Steve Defeated the Ground Gym Leader
/ach add Steve
/ach add Steve Defeated the Ground Gym Leader
/ach title Steve "Defeated the Ground Gym Leader"
/ach remove Steve
```

Command notes:

- `/ach add <player> [title]` creates the achievement people earn by beating that player.
- `/ach <player> [title]` is the shortest OP setup form.
- Titles can have spaces. Quotes are optional; `/ach title Steve "Ground Gym Badge"` and `/ach title Steve Ground Gym Badge` both work.
- Extra spaces around titles are cleaned up automatically.
- You usually do not need to set an achievement ID. The mod makes one automatically.
- `/ach add`, `/ach remove`, and `/ach list` require OP.
- `/ach on`, `/ach off`, `/ach enable`, and `/ach disable` let a configured target player control whether people can earn their achievement.
- Achievement commands accept normal player names. If the player is offline, the target migrates to their UUID when they next battle.
- Advanced ID override: `/ach id <player> <id> [title]`.

## Other Commands

```mcfunction
/cach help
/cach reload
/cach snapshot now
/cach snapshot toclient
/cach remote status
/cach remote refresh
/cach target add <player> [achievementId] [title]
/cach target remove <player>
/cach target list
```

- `/cach target ...` is the older advanced achievement command path.
- `/cach snapshot now` exports the current server snapshot.
- `/cach snapshot toclient` sends the full server snapshot to the configured owner's client mod.
- `/cach remote status` and `/cach remote refresh` require owner access.
- `/cach remote refresh` runs in the background so a slow GitHub check will not freeze the server tick thread.

## Remote Manifest

- Publish `remote/manifest.signed.json` to GitHub raw, IPFS, or another static file host.
- The default GitHub raw manifest URL is already configured:
  `https://raw.githubusercontent.com/Potato4507/cobblemon-achievements-server/main/remote/manifest.signed.json`
- Put the new jar URL in `downloadUrl`, or put base64 chunk URLs in `downloadBase64Chunks`.
- The mod verifies `downloadSha256` before using a downloaded jar.
- Add fallback URLs to `remoteManifestUrls` in `config/cobblemon-achievements-server.json`.
- The unsigned payload lives at `remote/manifest.payload.json`.
- Sign it with `tools/sign-manifest.mjs`.
- Keep `local-secrets/manifest-private-key.pem` private. It is ignored by git.

Signing example:

```powershell
$env:COBBLE_ACHIEVEMENTS_MANIFEST_PRIVATE_KEY_PEM = Get-Content -Raw local-secrets/manifest-private-key.pem
node tools/sign-manifest.mjs
```
