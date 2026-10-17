package com.m3u.data.repository.tv

/**
 * Optional app setting for the phone-remote server that a TV build starts on launch (an HTTP
 * server plus a network service broadcast, shown as a pairing pin). Apps that don't bind it keep
 * the upstream behaviour: the server starts.
 */
data class TvRemoteControlConfig(
    val broadcastOnTv: Boolean,
)
