package com.pawan.tunnel

/**
 * Built-in catalog of 100+ providers Tunnel recognizes out of the box.
 *
 * It tells Tunnel which provider an identifier needs (by search prefix or by site), so the
 * request goes to a node that has it. A provider can only be played if at least one of your
 * nodes actually has it: see GET /v4/tunnel/catalog for what is available right now.
 */
class BuiltInProvider(
    val id: String,
    val name: String,
    val category: String,
    val hosts: List<String>,
    val prefixes: List<String>,
    val aliases: List<String>
)

object ProviderCatalog {

    private fun p(
        id: String,
        name: String,
        category: String,
        hosts: String = "",
        prefixes: String = "",
        aliases: String = ""
    ) = BuiltInProvider(
        id, name, category,
        hosts.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        prefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        aliases.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    )

    val all: List<BuiltInProvider> = listOf(
        // ---- core / utility
        p("http", "Direct HTTP link", "core", aliases = "http,https"),
        p("tts", "Text to speech (speak:)", "tts", prefixes = "speak", aliases = "speak,tts"),
        p("flowerytts", "Flowery TTS", "tts", prefixes = "ftts"),
        p("getyarn", "GetYarn", "utility", hosts = "getyarn.io,y.yarn.co"),
        p("clypit", "Clyp", "utility", hosts = "clyp.it"),
        p("genius", "Genius", "music", hosts = "genius.com", prefixes = "geniussearch"),
        p("rss", "RSS / podcast feeds", "podcast", prefixes = "rsssearch"),
        p("abs", "ABS (audiobook server)", "music", prefixes = "abssearch"),

        // ---- global music streaming
        p("youtube", "YouTube / YouTube Music", "music",
            hosts = "youtube.com,youtu.be,m.youtube.com,music.youtube.com,youtube-nocookie.com",
            prefixes = "ytsearch,ytmsearch,youtubeshortssearch", aliases = "youtube-music,ytmusic,yt,youtubeshorts"),
        p("soundcloud", "SoundCloud", "music", hosts = "soundcloud.com,on.soundcloud.com,snd.sc", prefixes = "scsearch,soundcloudsearch", aliases = "sc"),
        p("spotify", "Spotify", "music", hosts = "open.spotify.com,spotify.link", prefixes = "spsearch,sprec", aliases = "sp"),
        p("applemusic", "Apple Music", "music", hosts = "music.apple.com", prefixes = "amsearch", aliases = "apple,apple-music"),
        p("deezer", "Deezer", "music", hosts = "deezer.com,deezer.page.link,dzr.page.link", prefixes = "dzsearch,dzisrc,dzrec"),
        p("tidal", "Tidal", "music", hosts = "tidal.com,listen.tidal.com", prefixes = "tdsearch,tdrec"),
        p("qobuz", "Qobuz", "music", hosts = "qobuz.com,open.qobuz.com,play.qobuz.com", prefixes = "qbsearch,qbisrc,qbrec"),
        p("yandexmusic", "Yandex Music", "music",
            hosts = "music.yandex.ru,music.yandex.com,music.yandex.kz,music.yandex.by,music.yandex.uz",
            prefixes = "ymsearch,ymrec", aliases = "yandex"),
        p("vkmusic", "VK Music", "music", hosts = "vk.com,vk.ru", prefixes = "vksearch,vkrec", aliases = "vk"),
        p("jiosaavn", "JioSaavn", "music", hosts = "jiosaavn.com,saavn.com", prefixes = "jssearch,jsrec", aliases = "saavn,js"),
        p("bandcamp", "Bandcamp", "music", hosts = "bandcamp.com", prefixes = "bcsearch,bandcampsearch", aliases = "bc"),
        p("mixcloud", "Mixcloud", "music", hosts = "mixcloud.com", prefixes = "mcsearch,mixcloudsearch", aliases = "mc"),
        p("audiomack", "Audiomack", "music", hosts = "audiomack.com", prefixes = "audiomacksearch"),
        p("pandora", "Pandora", "music", hosts = "pandora.com", prefixes = "pdsearch", aliases = "pd"),
        p("amazonmusic", "Amazon Music", "music", hosts = "music.amazon.com,music.amazon.co.uk,music.amazon.in,music.amazon.de"),
        p("napster", "Napster", "music", hosts = "napster.com,play.napster.com"),
        p("anghami", "Anghami", "music", hosts = "anghami.com,play.anghami.com"),
        p("gaana", "Gaana", "music", hosts = "gaana.com", prefixes = "gnsearch", aliases = "gn"),
        p("wynk", "Wynk Music", "music", hosts = "wynk.in,wynkmusic.in"),
        p("hungama", "Hungama", "music", hosts = "hungama.com"),
        p("boomplay", "Boomplay", "music", hosts = "boomplay.com"),
        p("audius", "Audius", "music", hosts = "audius.co", prefixes = "adsearch,audiussearch", aliases = "ad"),
        p("lastfm", "Last.fm", "music", hosts = "last.fm", prefixes = "lastfmsearch"),
        p("shazam", "Shazam", "music", hosts = "shazam.com", prefixes = "shazamsearch"),
        p("beatport", "Beatport", "music", hosts = "beatport.com"),
        p("traxsource", "Traxsource", "music", hosts = "traxsource.com"),
        p("reverbnation", "ReverbNation", "music", hosts = "reverbnation.com"),
        p("datpiff", "DatPiff", "music", hosts = "datpiff.com"),
        p("audioboom", "Audioboom", "music", hosts = "audioboom.com"),
        p("hearthis", "HearThis.at", "music", hosts = "hearthis.at"),
        p("jamendo", "Jamendo", "music", hosts = "jamendo.com"),
        p("freemusicarchive", "Free Music Archive", "music", hosts = "freemusicarchive.org"),
        p("internetarchive", "Internet Archive", "music", hosts = "archive.org"),
        p("ocremix", "OverClocked ReMix", "music", hosts = "ocremix.org"),
        p("bandlab", "BandLab", "music", hosts = "bandlab.com"),
        p("spinrilla", "Spinrilla", "music", hosts = "spinrilla.com"),
        p("ccmixter", "ccMixter", "music", hosts = "ccmixter.org"),
        p("soundclick", "SoundClick", "music", hosts = "soundclick.com"),
        p("newgrounds", "Newgrounds", "music", hosts = "newgrounds.com", prefixes = "ngsearch,newgroundssearch", aliases = "ng"),
        p("siriusxm", "SiriusXM", "music", hosts = "siriusxm.com"),

        // ---- regional music streaming
        p("netease", "NetEase Cloud Music", "regional", hosts = "music.163.com", prefixes = "neteasesearch"),
        p("qqmusic", "QQ Music", "regional", hosts = "y.qq.com"),
        p("kugou", "KuGou", "regional", hosts = "kugou.com"),
        p("kuwo", "Kuwo", "regional", hosts = "kuwo.cn"),
        p("melon", "Melon", "regional", hosts = "melon.com"),
        p("genie", "Genie Music", "regional", hosts = "genie.co.kr"),
        p("bugs", "Bugs", "regional", hosts = "music.bugs.co.kr"),
        p("zingmp3", "Zing MP3", "regional", hosts = "zingmp3.vn"),
        p("nhaccuatui", "NhacCuaTui", "regional", hosts = "nhaccuatui.com"),
        p("joox", "JOOX", "regional", hosts = "joox.com"),
        p("kkbox", "KKBOX", "regional", hosts = "kkbox.com,play.kkbox.com"),
        p("linemusic", "LINE MUSIC", "regional", hosts = "music.line.me"),
        p("awa", "AWA", "regional", hosts = "awa.fm"),
        p("zvuk", "Zvuk", "regional", hosts = "zvuk.com,sber-zvuk.com"),

        // ---- video and social
        p("twitch", "Twitch", "video", hosts = "twitch.tv,clips.twitch.tv", prefixes = "twsearch,twitchsearch", aliases = "tw"),
        p("vimeo", "Vimeo", "video", hosts = "vimeo.com", prefixes = "vmsearch,vimeosearch", aliases = "vm"),
        p("dailymotion", "Dailymotion", "video", hosts = "dailymotion.com,dai.ly"),
        p("facebook", "Facebook", "social", hosts = "facebook.com,fb.watch,fb.com"),
        p("instagram", "Instagram", "social", hosts = "instagram.com", prefixes = "igsearch,instagramsearch", aliases = "ig"),
        p("tiktok", "TikTok", "social", hosts = "tiktok.com,vm.tiktok.com,vt.tiktok.com", prefixes = "ttsearch,tiktoksearch", aliases = "tt"),
        p("twitter", "X / Twitter", "social", hosts = "twitter.com,x.com,t.co,vxtwitter.com,fxtwitter.com"),
        p("reddit", "Reddit", "social", hosts = "reddit.com,v.redd.it,redd.it", prefixes = "rdsearch,redditsearch", aliases = "rd"),
        p("telegram", "Telegram", "social", hosts = "t.me,telegram.me", prefixes = "tgsearch,telegramsearch", aliases = "tg"),
        p("odysee", "Odysee", "video", hosts = "odysee.com"),
        p("rumble", "Rumble", "video", hosts = "rumble.com"),
        p("tumblr", "Tumblr", "social", hosts = "tumblr.com"),
        p("pinterest", "Pinterest", "social", hosts = "pinterest.com,pin.it", prefixes = "pinterestsearch"),
        p("snapchat", "Snapchat", "social", hosts = "snapchat.com"),
        p("kick", "Kick", "video", hosts = "kick.com"),
        p("streamable", "Streamable", "video", hosts = "streamable.com"),
        p("imgur", "Imgur", "social", hosts = "imgur.com"),
        p("ted", "TED", "video", hosts = "ted.com"),
        p("vevo", "Vevo", "video", hosts = "vevo.com"),
        p("youku", "Youku", "video", hosts = "youku.com"),
        p("iqiyi", "iQIYI", "video", hosts = "iq.com,iqiyi.com"),
        p("rutube", "RuTube", "video", hosts = "rutube.ru"),
        p("okru", "OK.ru", "social", hosts = "ok.ru"),
        p("weibo", "Weibo", "social", hosts = "weibo.com,weibo.cn"),
        p("douyin", "Douyin", "social", hosts = "douyin.com"),
        p("naver", "Naver TV", "video", hosts = "tv.naver.com"),
        p("bilibili", "Bilibili", "video", hosts = "bilibili.com,b23.tv", prefixes = "blsearch,bilibilisearch", aliases = "bl"),
        p("nicovideo", "Niconico", "video", hosts = "nicovideo.jp,nico.ms", prefixes = "niconicosearch", aliases = "nico,niconico"),
        p("arte", "ARTE", "video", hosts = "arte.tv"),
        p("bbc", "BBC", "video", hosts = "bbc.co.uk,bbc.com"),
        p("npr", "NPR", "radio", hosts = "npr.org"),

        // ---- podcasts and radio
        p("applepodcasts", "Apple Podcasts", "podcast", hosts = "podcasts.apple.com"),
        p("podbean", "Podbean", "podcast", hosts = "podbean.com"),
        p("anchor", "Anchor", "podcast", hosts = "anchor.fm"),
        p("libsyn", "Libsyn", "podcast", hosts = "libsyn.com"),
        p("buzzsprout", "Buzzsprout", "podcast", hosts = "buzzsprout.com"),
        p("acast", "Acast", "podcast", hosts = "acast.com"),
        p("iheart", "iHeart", "radio", hosts = "iheart.com"),
        p("tunein", "TuneIn", "radio", hosts = "tunein.com"),

        // ---- file hosts (direct media links)
        p("googledrive", "Google Drive", "storage", hosts = "drive.google.com"),
        p("dropbox", "Dropbox", "storage", hosts = "dropbox.com,dl.dropboxusercontent.com"),
        p("onedrive", "OneDrive", "storage", hosts = "1drv.ms,onedrive.live.com"),
        p("githubraw", "GitHub raw files", "storage", hosts = "raw.githubusercontent.com,gist.githubusercontent.com"),
        p("discordcdn", "Discord CDN", "storage", hosts = "cdn.discordapp.com,media.discordapp.net"),
        p("catbox", "Catbox", "storage", hosts = "catbox.moe,files.catbox.moe"),
        p("vocaroo", "Vocaroo", "storage", hosts = "vocaroo.com,voca.ro"),
        p("mediafire", "MediaFire", "storage", hosts = "mediafire.com"),
        p("mega", "MEGA", "storage", hosts = "mega.nz,mega.io"),
        p("yandexdisk", "Yandex Disk", "storage", hosts = "disk.yandex.ru,disk.yandex.com,yadi.sk"),
        p("gofile", "Gofile", "storage", hosts = "gofile.io")
    )

    private val byId: Map<String, BuiltInProvider> = all.associateBy { it.id }

    /** search prefix (ytsearch, spsearch, ...) -> provider id */
    private val prefixIndex: Map<String, String> =
        all.flatMap { p -> p.prefixes.map { it.lowercase() to p.id } }.toMap()

    /** (domain, provider id), most specific domain first */
    private val hostIndex: List<Pair<String, String>> =
        all.flatMap { p -> p.hosts.map { it.lowercase() to p.id } }.sortedByDescending { it.first.length }

    /** Names a node may use for the same provider in /v4/info. */
    private val universalNames = listOf("yt-dlp", "ytdlp", "yt_dlp", "youtube-dl", "universal")

    /** Every built-in search prefix (ytsearch, scsearch, ...), in catalog order. */
    val allPrefixes: List<String> = all.flatMap { it.prefixes }.distinct()

    fun byId(id: String): BuiltInProvider? = byId[id]

    fun byPrefix(prefix: String): String? = prefixIndex[prefix.lowercase()]

    fun byHost(host: String): String? {
        val h = host.lowercase()
        return hostIndex.firstOrNull { h == it.first || h.endsWith("." + it.first) }?.second
    }

    /** The provider id plus every other name a node might report for it. */
    fun namesFor(id: String): List<String> {
        val p = byId[id] ?: return listOf(id)
        return listOf(p.id) + p.aliases
    }

    /** True for providers recognized by website, which a yt-dlp style node can handle. */
    fun isSiteBased(id: String): Boolean = byId[id]?.hosts?.isNotEmpty() == true

    /** True if a node-reported source name means "any website" (yt-dlp and similar). */
    fun isUniversal(nodeProvider: String): Boolean = universalNames.any { nodeProvider.contains(it) }
}
