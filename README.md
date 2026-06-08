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
Copy-Item .\build\libs\cobblemon-achievements-server-0.1.1.jar "C:\Path\To\Your\Server\mods\"
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
- Badges are saved in `config/cobblemon-achievements-server.json`.
- `playerBadgesEnabled` can turn all badge display on or off.
- The mod uses vanilla scoreboard teams. Another mod/plugin that also manages scoreboard teams can override or conflict with name badges.

## Achievement Commands

```mcfunction
/cach active on
/cach active off
/cach target add <player> [achievementId] [title]
/cach target remove <player>
/cach target list
/cach snapshot now
/cach snapshot toclient
/cach remote status
/cach remote refresh
```

Command notes:

- `/cach active <on|off>` lets a configured target player control achievement availability.
- `/cach target ...` commands require OP.
- `/cach snapshot now` exports the current server snapshot.
- `/cach snapshot toclient` sends the full server snapshot to the configured owner's client mod.
- `/cach remote status` and `/cach remote refresh` require owner access.

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
