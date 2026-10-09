package com.localtv.viewer

import android.app.Application
import com.localtv.viewer.data.MediaRepository
import com.localtv.viewer.data.MediaServer
import com.localtv.viewer.data.SourceStore
import org.videolan.libvlc.LibVLC

class LocalTvApp : Application() {
    val store by lazy { SourceStore(this) }
    val repository by lazy { MediaRepository(this) }
    val mediaServer by lazy { MediaServer(repository) }
    val thumbnails by lazy { ThumbnailCache(this, mediaServer) }
    // Shared native engine avoids repeatedly initializing codecs when moving between files.
    val playerEngine by lazy { LibVLC(this, arrayListOf("--no-video-title-show", "--network-caching=900", "--file-caching=500")) }
}
