package com.xgimiru.player.dlna

import com.xgimiru.player.dlna.Upnp.esc

/** Описание устройства и трёх служб (SCPD), которые контроллеры читают перед тем, как слать команды. */
object Descriptions {

    /** Что умеет играть Lumen Play: Media3 и VLC берут почти всё, фото показывает [ImageActivity]. */
    val SINK = listOf(
        "video/mp4", "video/x-matroska", "video/x-mkv", "video/webm", "video/mpeg", "video/mp2t", "video/MP2T",
        "video/vnd.dlna.mpeg-tts", "video/x-msvideo", "video/avi", "video/divx", "video/quicktime", "video/x-flv",
        "video/x-ms-wmv", "video/x-ms-asf", "video/3gpp", "video/x-m4v", "video/ogg",
        "application/vnd.apple.mpegurl", "application/x-mpegURL", "application/dash+xml", "application/octet-stream",
        "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/x-aac", "audio/flac", "audio/x-flac",
        "audio/ogg", "audio/opus", "audio/webm", "audio/wav", "audio/x-wav", "audio/x-ms-wma", "audio/ac3", "audio/eac3",
        "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/heic",
    ).joinToString(",") { "http-get:*:$it:*" }

    fun device(uuid: String, name: String, version: String): String = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device>
<deviceType>${Upnp.DEVICE}</deviceType>
<friendlyName>${esc(name)}</friendlyName>
<manufacturer>Lumen</manufacturer>
<manufacturerURL>https://github.com/kmuradoff/kino-player</manufacturerURL>
<modelDescription>Lumen Play media renderer</modelDescription>
<modelName>Lumen Play</modelName>
<modelNumber>${esc(version)}</modelNumber>
<modelURL>https://github.com/kmuradoff/kino-player</modelURL>
<serialNumber>${esc(uuid.take(8))}</serialNumber>
<UDN>uuid:${esc(uuid)}</UDN>
<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
<iconList><icon><mimetype>image/png</mimetype><width>192</width><height>192</height><depth>32</depth><url>/icon.png</url></icon></iconList>
<serviceList>
${service(Upnp.AVT, "AVTransport")}
${service(Upnp.RCS, "RenderingControl")}
${service(Upnp.CMS, "ConnectionManager")}
</serviceList>
</device>
</root>
"""

    private fun service(type: String, id: String) =
        "<service><serviceType>$type</serviceType><serviceId>urn:upnp-org:serviceId:$id</serviceId>" +
        "<SCPDURL>/$id/scpd.xml</SCPDURL><controlURL>/$id/control</controlURL><eventSubURL>/$id/event</eventSubURL></service>"

    // ── SCPD ──
    private class Arg(val name: String, val out: Boolean, val v: String)
    private class Var(val name: String, val type: String = "string", val events: Boolean = false,
                      val allowed: List<String> = emptyList(), val range: IntRange? = null)

    private fun i(name: String, v: String) = Arg(name, false, v)
    private fun o(name: String, v: String) = Arg(name, true, v)
    private val ID = i("InstanceID", "A_ARG_TYPE_InstanceID")

    private fun scpd(actions: List<Pair<String, List<Arg>>>, vars: List<Var>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">")
        append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        for ((name, args) in actions) {
            append("<action><name>").append(name).append("</name>")
            if (args.isNotEmpty()) {
                append("<argumentList>")
                for (a in args) append("<argument><name>").append(a.name).append("</name><direction>")
                    .append(if (a.out) "out" else "in").append("</direction><relatedStateVariable>")
                    .append(a.v).append("</relatedStateVariable></argument>")
                append("</argumentList>")
            }
            append("</action>")
        }
        append("</actionList><serviceStateTable>")
        for (v in vars) {
            append("<stateVariable sendEvents=\"").append(if (v.events) "yes" else "no").append("\"><name>")
                .append(v.name).append("</name><dataType>").append(v.type).append("</dataType>")
            if (v.allowed.isNotEmpty()) {
                append("<allowedValueList>")
                for (a in v.allowed) append("<allowedValue>").append(a).append("</allowedValue>")
                append("</allowedValueList>")
            }
            v.range?.let { append("<allowedValueRange><minimum>${it.first}</minimum><maximum>${it.last}</maximum><step>1</step></allowedValueRange>") }
            append("</stateVariable>")
        }
        append("</serviceStateTable></scpd>")
    }

    val AVT_SCPD: String by lazy {
        scpd(listOf(
            "SetAVTransportURI" to listOf(ID, i("CurrentURI", "AVTransportURI"), i("CurrentURIMetaData", "AVTransportURIMetaData")),
            "SetNextAVTransportURI" to listOf(ID, i("NextURI", "NextAVTransportURI"), i("NextURIMetaData", "NextAVTransportURIMetaData")),
            "GetMediaInfo" to listOf(ID, o("NrTracks", "NumberOfTracks"), o("MediaDuration", "CurrentMediaDuration"),
                o("CurrentURI", "AVTransportURI"), o("CurrentURIMetaData", "AVTransportURIMetaData"),
                o("NextURI", "NextAVTransportURI"), o("NextURIMetaData", "NextAVTransportURIMetaData"),
                o("PlayMedium", "PlaybackStorageMedium"), o("RecordMedium", "RecordStorageMedium"),
                o("WriteStatus", "RecordMediumWriteStatus")),
            "GetTransportInfo" to listOf(ID, o("CurrentTransportState", "TransportState"),
                o("CurrentTransportStatus", "TransportStatus"), o("CurrentSpeed", "TransportPlaySpeed")),
            "GetPositionInfo" to listOf(ID, o("Track", "CurrentTrack"), o("TrackDuration", "CurrentTrackDuration"),
                o("TrackMetaData", "CurrentTrackMetaData"), o("TrackURI", "CurrentTrackURI"),
                o("RelTime", "RelativeTimePosition"), o("AbsTime", "AbsoluteTimePosition"),
                o("RelCount", "RelativeCounterPosition"), o("AbsCount", "AbsoluteCounterPosition")),
            "GetDeviceCapabilities" to listOf(ID, o("PlayMedia", "PossiblePlaybackStorageMedia"),
                o("RecMedia", "PossibleRecordStorageMedia"), o("RecQualityModes", "PossibleRecordQualityModes")),
            "GetTransportSettings" to listOf(ID, o("PlayMode", "CurrentPlayMode"), o("RecQualityMode", "CurrentRecordQualityMode")),
            "GetCurrentTransportActions" to listOf(ID, o("Actions", "CurrentTransportActions")),
            "Stop" to listOf(ID),
            "Play" to listOf(ID, i("Speed", "TransportPlaySpeed")),
            "Pause" to listOf(ID),
            "Seek" to listOf(ID, i("Unit", "A_ARG_TYPE_SeekMode"), i("Target", "A_ARG_TYPE_SeekTarget")),
            "Next" to listOf(ID),
            "Previous" to listOf(ID),
        ), listOf(
            Var("TransportState", allowed = listOf("STOPPED", "PLAYING", "TRANSITIONING", "PAUSED_PLAYBACK", "NO_MEDIA_PRESENT")),
            Var("TransportStatus", allowed = listOf("OK", "ERROR_OCCURRED")),
            Var("PlaybackStorageMedium", allowed = listOf("NONE", "NETWORK", "UNKNOWN")),
            Var("RecordStorageMedium", allowed = listOf("NOT_IMPLEMENTED")),
            Var("PossiblePlaybackStorageMedia"),
            Var("PossibleRecordStorageMedia"),
            Var("CurrentPlayMode", allowed = listOf("NORMAL")),
            Var("TransportPlaySpeed", allowed = listOf("1")),
            Var("RecordMediumWriteStatus", allowed = listOf("NOT_IMPLEMENTED")),
            Var("CurrentRecordQualityMode", allowed = listOf("NOT_IMPLEMENTED")),
            Var("PossibleRecordQualityModes"),
            Var("NumberOfTracks", "ui4", range = 0..1),
            Var("CurrentTrack", "ui4", range = 0..1),
            Var("CurrentTrackDuration"),
            Var("CurrentMediaDuration"),
            Var("CurrentTrackMetaData"),
            Var("CurrentTrackURI"),
            Var("AVTransportURI"),
            Var("AVTransportURIMetaData"),
            Var("NextAVTransportURI"),
            Var("NextAVTransportURIMetaData"),
            Var("RelativeTimePosition"),
            Var("AbsoluteTimePosition"),
            Var("RelativeCounterPosition", "i4"),
            Var("AbsoluteCounterPosition", "i4"),
            Var("CurrentTransportActions"),
            Var("LastChange", events = true),
            Var("A_ARG_TYPE_SeekMode", allowed = listOf("REL_TIME", "ABS_TIME", "TRACK_NR")),
            Var("A_ARG_TYPE_SeekTarget"),
            Var("A_ARG_TYPE_InstanceID", "ui4"),
        ))
    }

    val RCS_SCPD: String by lazy {
        val ch = i("Channel", "A_ARG_TYPE_Channel")
        scpd(listOf(
            "ListPresets" to listOf(ID, o("CurrentPresetNameList", "PresetNameList")),
            "SelectPreset" to listOf(ID, i("PresetName", "A_ARG_TYPE_PresetName")),
            "GetMute" to listOf(ID, ch, o("CurrentMute", "Mute")),
            "SetMute" to listOf(ID, ch, i("DesiredMute", "Mute")),
            "GetVolume" to listOf(ID, ch, o("CurrentVolume", "Volume")),
            "SetVolume" to listOf(ID, ch, i("DesiredVolume", "Volume")),
        ), listOf(
            Var("LastChange", events = true),
            Var("PresetNameList"),
            Var("Mute", "boolean"),
            Var("Volume", "ui2", range = 0..100),
            Var("A_ARG_TYPE_Channel", allowed = listOf("Master")),
            Var("A_ARG_TYPE_InstanceID", "ui4"),
            Var("A_ARG_TYPE_PresetName", allowed = listOf("FactoryDefaults")),
        ))
    }

    val CMS_SCPD: String by lazy {
        scpd(listOf(
            "GetProtocolInfo" to listOf(o("Source", "SourceProtocolInfo"), o("Sink", "SinkProtocolInfo")),
            "GetCurrentConnectionIDs" to listOf(o("ConnectionIDs", "CurrentConnectionIDs")),
            "GetCurrentConnectionInfo" to listOf(i("ConnectionID", "A_ARG_TYPE_ConnectionID"),
                o("RcsID", "A_ARG_TYPE_RcsID"), o("AVTransportID", "A_ARG_TYPE_AVTransportID"),
                o("ProtocolInfo", "A_ARG_TYPE_ProtocolInfo"), o("PeerConnectionManager", "A_ARG_TYPE_ConnectionManager"),
                o("PeerConnectionID", "A_ARG_TYPE_ConnectionID"), o("Direction", "A_ARG_TYPE_Direction"),
                o("Status", "A_ARG_TYPE_ConnectionStatus")),
        ), listOf(
            Var("SourceProtocolInfo", events = true),
            Var("SinkProtocolInfo", events = true),
            Var("CurrentConnectionIDs", events = true),
            Var("A_ARG_TYPE_ConnectionStatus", allowed = listOf("OK", "ContentFormatMismatch", "InsufficientBandwidth", "UnreliableChannel", "Unknown")),
            Var("A_ARG_TYPE_ConnectionManager"),
            Var("A_ARG_TYPE_Direction", allowed = listOf("Input", "Output")),
            Var("A_ARG_TYPE_ProtocolInfo"),
            Var("A_ARG_TYPE_ConnectionID", "i4"),
            Var("A_ARG_TYPE_AVTransportID", "i4"),
            Var("A_ARG_TYPE_RcsID", "i4"),
        ))
    }
}
