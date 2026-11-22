# Discordify

Inline GIF/image embeds in Minecraft chat, Discord-style. Client-side Fabric mod for Minecraft 1.21.11.

Current alpha: `0.1.0-alpha.1`. This is a SemVer pre-release; breaking changes are possible between alpha versions.

## Install

- Minecraft 1.21.11
- Fabric Loader >= 0.18.1 (modpacks using chatpatches may need loader >= 0.19.0)
- Fabric API
- Java 21

Drop `discordify-0.1.0-alpha.1.jar` into your `mods/` folder. No server-side install is needed; the mod runs entirely on the client.

## Features

- Direct image URLs: `.gif`, `.png`, `.jpg`, `.webp`.
- Tenor and Giphy share links are rewritten to their media URLs.
- KLIPY share pages (klipy.com/gifs/<slug>) resolve through the KLIPY items API using a public client key embedded in this alpha build; no user configuration file is needed.
- Signed Discord CDN URLs are supported, including links that serve a static WebP despite a `.gif` path.
- A rendered GIF reserves honest chat rows and follows its message as the chat scrolls.
- Embeds hide together with the chat (e.g. when chat is closed with Escape).
- Resource caps: at most 3 embeds drawn and 8 decoded at once; decoded media is cached with LFU eviction and a 24-hour TTL.

## Controls

No dedicated keybinds. Embeds follow normal chat behavior:

- Open/close chat (default `T`) to show/hide embeds.
- Scroll the chat to move embeds with their messages.

## Compatibility

- Tested with chat_heads 1.3.2 and chatpatches 8.0.0.
- Uses a cooperative mixin at priority 1001 and does not `ci.cancel` the vanilla background fill, so other chat mods' edits to the same call survive.

## Known limits

- Animated WebP is unsupported; only the static first frame is shown.
- Dynamic chat shifts from chatpatches are handled via a captured `chatBottom` value.
- Resource caps: animated GIFs are limited to 512px / 120 frames; static images to 1024px.

## Build

Builds run in CI via GitHub Actions: `https://github.com/ShadowCvrsxd/discordify/actions`. The build command is `./gradlew build`.

## License

License: see repository.
