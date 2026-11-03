package com.pawan.tunnel

import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor

/**
 * A track that carries the info a node returned (title, author, length, artwork, ISRC, link).
 * Search returns instantly with full info; the real audio is found only when the track is played
 * (its own link first, then the mirrors), so a slow or broken local source no longer makes
 * a search come back empty.
 */
class TunnelAudioTrack(
    trackInfo: AudioTrackInfo,
    private val tunnel: TunnelSourceManager
) : DelegatedAudioTrack(trackInfo) {

    override fun process(executor: LocalAudioTrackExecutor) {
        val real = tunnel.playable(info)
            ?: throw FriendlyException(
                "Tunnel: no playable source found for '${info.title}'",
                FriendlyException.Severity.COMMON,
                null
            )
        processDelegate(real as InternalAudioTrack, executor)
    }

    override fun makeShallowClone(): AudioTrack = TunnelAudioTrack(info, tunnel)

    override fun getSourceManager(): AudioSourceManager = tunnel
}
