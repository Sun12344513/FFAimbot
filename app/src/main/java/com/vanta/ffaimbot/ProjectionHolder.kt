package com.vanta.ffaimbot

import android.media.projection.MediaProjection

// Singleton bridge — avoids re-parceling the MediaProjection token through Intent extras.
// MIUI corrupts the token if you pass result data to a Service and call getMediaProjection() there.
// Pattern: create projection immediately in onActivityResult, store here, Service reads directly.
object ProjectionHolder {
    @Volatile
    var projection: MediaProjection? = null
}
