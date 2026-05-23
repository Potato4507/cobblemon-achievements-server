# Cobblemon Achievements Server

Server-side Fabric mod for Cobblemon 1.7.3 on Minecraft 1.21.1.

Features:

- OP-managed defeat achievements for specific players.
- Target players can toggle whether their achievement is active.
- Server-side battle victory checks.
- Snapshot export of every online player's party and PC Pokemon, including levels, moves, ability, nature, held item, and health.
- Export to local JSON files, an HTTP endpoint, or GitHub Contents API.
- Signed remote manifest support for GitHub/IPFS-style data and update checks. The mod verifies the manifest with a hardcoded Ed25519 public key before caching data or downloading an update jar.
- GitHub auto-update checks every 5 minutes by default. When the signed manifest changes, the server downloads the new jar, tries to replace the loaded mod jar, and tells the server that a restart is required.


Build:

- Install Java 21 and Gradle 9.4.0 or newer.
- Put the Cobblemon Fabric 1.7.3 jar at the path in `gradle.properties`, or change `cobblemon_jar` to your local jar path.
- Run `gradle build`.

Commands:

- `/cach active <on|off>` lets a target player control achievement availability.
- `/cach target add <player> [achievementId] [title]` adds a target. Requires OP.
- `/cach target remove <player>` removes a target. Requires OP.
- `/cach target list` lists target status. Requires OP.
- `/cach snapshot now` writes and sends the current snapshot.
- `/cach snapshot toclient` sends the current full server snapshot to the configured owner's client mod.
- `/cach remote status` shows the signed remote manifest status. Requires owner.
- `/cach remote refresh` fetches and verifies the signed remote manifest immediately. Requires owner.


Remote manifest:

- Publish `remote/manifest.signed.json` to GitHub raw, IPFS, or another static file host.
- The default GitHub raw manifest URL is already configured:
  `https://raw.githubusercontent.com/Potato4507/cobblemon-achievements-server/main/remote/manifest.signed.json`
- Put the new jar URL in `downloadUrl`, or put base64 chunk URLs in `downloadBase64Chunks`; the mod verifies `downloadSha256` before using the jar.
- Add more fallback URLs to `remoteManifestUrls` in `config/cobblemon-achievements-server.json` if wanted.
- The unsigned payload lives at `remote/manifest.payload.json`.
- Sign it with `tools/sign-manifest.mjs`.
- Keep `local-secrets/manifest-private-key.pem` private. It is ignored by git.

Signing example:

```powershell
$env:COBBLE_ACHIEVEMENTS_MANIFEST_PRIVATE_KEY_PEM = Get-Content -Raw local-secrets/manifest-private-key.pem
node tools/sign-manifest.mjs
```
