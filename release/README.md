# Tunnel 1.0.0

**Lavalink plugin by Pawan.** Tunnel lets your own Lavalink use other Lavalink servers (nodes) to find music.
Your bot only talks to your Lavalink. Tunnel asks the remote nodes, gets the track info, and plays it from your server.

## What you get

- Several nodes at once, with automatic failover
- 100+ built-in providers (YouTube, Spotify, SoundCloud, Deezer, Apple Music, Tidal, Qobuz and many more)
- Auto-detect + Advanced Search Driver: type plain words, a link, or an ISRC code, and the best results come first
- Auto-update: new versions download themselves and the old one is deleted

## Install (2 minutes)

1. Put `plugins/tunnel-1.0.0.jar` into your Lavalink `plugins/` folder.
2. Open `exp.yml`. Copy the `plugins: tunnel:` block into your Lavalink `application.yml` and add your nodes.
3. Restart Lavalink. The log shows `[Tunnel] by Pawan v1.0.0 loaded with N node(s)`.

Your Lavalink also needs the real sources itself (for example a YouTube source plugin), because it plays the final track URL.

## Use it

Add `tunnel:` in front of what you would normally load:

| You type | What happens |
|---|---|
| `tunnel:never gonna give you up` | searches several providers and ranks the results |
| `tunnel:USRC17607839` | looks up an ISRC code |
| `tunnel:https://open.spotify.com/track/...` | sent to a node that has Spotify |
| `tunnel:ytsearch:lofi` or `tunnel:sp:lofi` | sent to that provider |
| `tunnel:asd:yt: remix` | always uses the search driver |

Shorthands: `yt` `ytm` `sc` `sp` `am` `dz` `td` `qb` `ym` `vk` `js`.

## Settings

All settings are under `plugins.tunnel` (see `exp.yml`, every option has a comment):

- `nodes`: your remote Lavalink servers (host, port, password, priority)
- `asd`: search driver (`mode: merge` or `first`, provider order, how many providers)
- `autoUpdate`: on by default

## Auto-update

Tunnel checks for a new release every hour. When one is found it downloads it, **deletes the old jar**, and asks you to restart.
Set `restartAfterUpdate: true` and Lavalink exits by itself after an update. This only helps if your panel, systemd or Docker starts it again.
Turn it off with `autoUpdate.enabled: false`.

## Check that it works

Call these with your Lavalink password in the `Authorization` header:

- `GET /v4/tunnel/nodes`: your nodes, online or not, and their providers
- `GET /v4/tunnel/catalog`: all built-in providers and which ones are available now
- `GET /v4/tunnel/asd/search?query=daft punk`: ranked results, nothing is played
- `POST /v4/tunnel/update`: check for a new version right now

## Troubleshooting

- **Node shows offline:** check host, port, password, and `secure: true` if the node uses https.
- **Nothing plays:** your own Lavalink needs the source (for example YouTube) for the track it was given.
- **Two Tunnel jars in `plugins/`:** delete the older one and restart.

## Good to know

- The password of any node you build into the jar can be read by whoever has the jar. Use passwords made only for this.
- Only connect to Lavalink servers you own or have permission to use.

Made by Pawan. All rights reserved, see `LICENSE.txt`.
