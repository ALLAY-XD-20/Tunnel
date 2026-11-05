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

## Built-in search prefixes

All of these work out of the box (short and long forms): `ytsearch:` `scsearch:` `spsearch:` `amsearch:` `dzsearch:`
`tdsearch:` `jssearch:` `gnsearch:` `ymsearch:` `qbsearch:` `pdsearch:` `abssearch:` `bcsearch:` `adsearch:` `mcsearch:`
`audiomacksearch:` `ngsearch:` `twsearch:` `vmsearch:` `ttsearch:` `rdsearch:` `blsearch:` `igsearch:` `tgsearch:`
`rsssearch:` `shazamsearch:` `lastfmsearch:` `neteasesearch:` `pinterestsearch:` `geniussearch:` `twitchsearch:`
`niconicosearch:` `vimeosearch:` `bandcampsearch:` `soundcloudsearch:` `audiussearch:` `mixcloudsearch:`
`newgroundssearch:` `redditsearch:` `tiktoksearch:` `instagramsearch:` `telegramsearch:` `youtubeshortssearch:`

Short form also works: `tunnel:pd:song`, `tunnel:tt:song` (same as `pdsearch:` / `ttsearch:`).

## Turn sources on and off

```yaml
plugins:
  tunnel:
    sources:
      youtube: true
      spotify: true
      tiktok: false
```

Everything is ON by default. Live toggle (no restart): `POST /v4/tunnel/sources/spotify/disable`, `.../enable`,
`POST /v4/tunnel/sources/reset`. List: `GET /v4/tunnel/sources`.

## Several nodes, no duplicates

With `multiNode: true` (default) a search asks every node that has the source at the same time and merges the
results. A song found on two nodes is only added once.

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

- `GET /v4/tunnel/node`: quick status (all_online / partial / all_offline); `GET /v4/tunnel/node/{name}` for one node
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

## Normal searches (no `tunnel:` needed)

With `interceptSearch: true` (default) a normal Lavalink search is answered by Tunnel's built-in nodes and played on
your server. Nobody has to add a node:

```bash
curl -s -G -H "Authorization: tunnel" \
  --data-urlencode "identifier=scsearch:Alan Walker Faded" \
  http://demo.rozhost.in:4010/v4/loadtracks
```

Works for every search prefix (`ytsearch:`, `scsearch:`, `spsearch:`, `dzsearch:` ...). If the nodes fail,
`localFallback: true` lets your server's own sources try.

## Looks like a normal Lavalink

With `exposeSources: true` (default) `GET /v4/info` lists every enabled source in `sourceManagers` (youtube, spotify,
deezer, ...), so bots and clients that check supported sources before searching work normally.
The list is built at start-up; a live disable via `/v4/tunnel/sources/{id}/disable` still blocks the source.
