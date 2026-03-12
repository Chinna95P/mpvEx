package app.marlboroadvance.mpvex.domain.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import app.marlboroadvance.mpvex.domain.media.model.Video
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

class ThumbnailRepository(
  private val context: Context,
  private val imageLoader: ImageLoader,
) {
  private val appearancePreferences by lazy {
    KoinJavaComponent.get<app.marlboroadvance.mpvex.preferences.AppearancePreferences>(
      app.marlboroadvance.mpvex.preferences.AppearancePreferences::class.java,
    )
  }
  private val browserPreferences by lazy {
    KoinJavaComponent.get<app.marlboroadvance.mpvex.preferences.BrowserPreferences>(
      app.marlboroadvance.mpvex.preferences.BrowserPreferences::class.java,
    )
  }

  private val memoryCache: LruCache<String, Bitmap>
  private val ongoingOperations = ConcurrentHashMap<String, Deferred<Bitmap?>>()
  private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val maxConcurrentFolders = 3

  private data class FolderState(
    val signature: String,
    @Volatile var nextIndex: Int = 0,
  )

  private val folderStates = ConcurrentHashMap<String, FolderState>()
  private val folderJobs = ConcurrentHashMap<String, Job>()

  private val _thumbnailReadyKeys =
    MutableSharedFlow<String>(
      extraBufferCapacity = 256,
    )
  val thumbnailReadyKeys: SharedFlow<String> = _thumbnailReadyKeys.asSharedFlow()

  init {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
    val cacheSizeKb = maxMemoryKb / 6
    memoryCache =
      object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(
          key: String,
          value: Bitmap,
        ): Int = value.byteCount / 1024
      }
  }

  suspend fun getThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      val key = thumbnailKey(video, widthPx, heightPx)

      if (isNetworkUrl(video.path) && !appearancePreferences.showNetworkThumbnails.get()) {
        return@withContext null
      }

      synchronized(memoryCache) {
        memoryCache.get(key)
      }?.let { return@withContext it }

      ongoingOperations[key]?.let { return@withContext it.await() }

      val deferred =
        async {
          try {
            getCachedThumbnail(video, widthPx, heightPx)?.let { cached ->
              synchronized(memoryCache) {
                memoryCache.put(key, cached)
              }
              _thumbnailReadyKeys.tryEmit(key)
              return@async cached
            }

            val result =
              runCatching {
                imageLoader.execute(buildRequest(video))
              }.getOrNull() as? SuccessResult ?: return@async null

            val bitmap = scaleBitmap(result.image.toBitmap(), widthPx, heightPx)
            synchronized(memoryCache) {
              memoryCache.put(key, bitmap)
            }
            _thumbnailReadyKeys.tryEmit(key)
            bitmap
          } finally {
            ongoingOperations.remove(key)
          }
        }

      ongoingOperations[key] = deferred
      deferred.await()
    }

  suspend fun getCachedThumbnail(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? =
    withContext(Dispatchers.IO) {
      if (isNetworkUrl(video.path) && !appearancePreferences.showNetworkThumbnails.get()) {
        return@withContext null
      }

      val key = thumbnailKey(video, widthPx, heightPx)
      synchronized(memoryCache) {
        memoryCache.get(key)
      }?.let { return@withContext it }

      val snapshot = imageLoader.diskCache?.openSnapshot(diskCacheKey(video)) ?: return@withContext null
      snapshot.use {
        val decoded =
          runCatching {
            BitmapFactory.decodeStream(it.data.toFile().inputStream())
          }.getOrNull() ?: return@withContext null

        val scaled = scaleBitmap(decoded, widthPx, heightPx)
        synchronized(memoryCache) {
          memoryCache.put(key, scaled)
        }
        return@withContext scaled
      }
    }

  fun getThumbnailFromMemory(
    video: Video,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? {
    if (isNetworkUrl(video.path) && !appearancePreferences.showNetworkThumbnails.get()) {
      return null
    }

    val key = thumbnailKey(video, widthPx, heightPx)
    return synchronized(memoryCache) {
      memoryCache.get(key)
    }
  }

  fun clearThumbnailCache() {
    folderJobs.values.forEach { it.cancel() }
    folderJobs.clear()
    folderStates.clear()
    ongoingOperations.clear()

    synchronized(memoryCache) {
      memoryCache.evictAll()
    }

    imageLoader.memoryCache?.clear()
    imageLoader.diskCache?.clear()
  }

  fun startFolderThumbnailGeneration(
    folderId: String,
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ) {
    val filteredVideos =
      if (appearancePreferences.showNetworkThumbnails.get()) {
        videos
      } else {
        videos.filterNot { isNetworkUrl(it.path) }
      }

    if (filteredVideos.isEmpty()) {
      return
    }

    folderJobs.entries.removeAll { !it.value.isActive }

    if (folderJobs.size >= maxConcurrentFolders && !folderJobs.containsKey(folderId)) {
      folderJobs.entries.firstOrNull()?.let { (oldestId, job) ->
        job.cancel()
        folderJobs.remove(oldestId)
        folderStates.remove(oldestId)
      }
    }

    val signature = folderSignature(filteredVideos, widthPx, heightPx)
    val state =
      folderStates.compute(folderId) { _, existing ->
        if (existing == null || existing.signature != signature) {
          FolderState(signature = signature, nextIndex = 0)
        } else {
          existing
        }
      }!!

    folderJobs.remove(folderId)?.cancel()
    folderJobs[folderId] =
      repositoryScope.launch {
        var i = state.nextIndex
        while (i < filteredVideos.size) {
          getThumbnail(filteredVideos[i], widthPx, heightPx)
          i++
          state.nextIndex = i
        }
      }
  }

  fun thumbnailKey(
    video: Video,
    width: Int,
    height: Int,
  ): String = "${videoBaseKey(video)}|$width|$height|${thumbnailModeKey()}"

  fun diskCacheKey(video: Video): String = "video-thumb|${videoBaseKey(video)}|${thumbnailModeKey()}"

  private fun videoBaseKey(video: Video): String {
    if (isNetworkUrl(video.path)) {
      val base = video.path.ifBlank { video.uri.toString() }
      return "$base|network"
    }

    return "${video.size}|${video.dateModified}|${video.duration}"
  }

  private fun buildRequest(video: Video): ImageRequest =
    ImageRequest.Builder(context)
      .data(requestData(video))
      .memoryCacheKey(diskCacheKey(video))
      .diskCacheKey(diskCacheKey(video))
      .build()

  private fun requestData(video: Video): Any =
    when {
      isNetworkUrl(video.path) -> video.path
      video.uri.scheme == "content" || video.uri.scheme == "file" -> video.uri
      video.path.isNotBlank() -> File(video.path)
      else -> video.uri
    }

  private fun scaleBitmap(
    bitmap: Bitmap,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap {
    if (widthPx <= 0 || heightPx <= 0) {
      return bitmap
    }

    val scale = max(widthPx / bitmap.width.toFloat(), heightPx / bitmap.height.toFloat())
    if (scale >= 1f && bitmap.width <= widthPx * 2 && bitmap.height <= heightPx * 2) {
      return bitmap
    }

    val scaledWidth = max(1, (bitmap.width * scale).roundToInt())
    val scaledHeight = max(1, (bitmap.height * scale).roundToInt())
    val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
    if (scaled != bitmap) {
      bitmap.recycle()
    }
    return scaled
  }

  private fun isNetworkUrl(path: String): Boolean =
    path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true) ||
      path.startsWith("rtmp://", ignoreCase = true) ||
      path.startsWith("rtsp://", ignoreCase = true) ||
      path.startsWith("ftp://", ignoreCase = true) ||
      path.startsWith("sftp://", ignoreCase = true) ||
      path.startsWith("smb://", ignoreCase = true)

  private fun isHttpUrl(path: String): Boolean =
    path.startsWith("http://", ignoreCase = true) ||
      path.startsWith("https://", ignoreCase = true)

  /**
   * Retrieve a thumbnail for a raw network file path (for use from [NetworkVideoCard]).
   * Only works for HTTP/HTTPS URLs — other protocols return null.
   * Respects the [showNetworkThumbnails] preference gate.
   */
  suspend fun getThumbnailForNetworkPath(
    path: String,
    widthPx: Int,
    heightPx: Int,
  ): Bitmap? = withContext(Dispatchers.IO) {
    if (!appearancePreferences.showNetworkThumbnails.get()) return@withContext null
    if (!isHttpUrl(path)) return@withContext null

    val memKey  = "$path|network|$widthPx|$heightPx|${thumbnailModeKey()}"
    val diskKey = "video-thumb|$path|network|${thumbnailModeKey()}"

    // Memory cache hit
    synchronized(memoryCache) { memoryCache.get(memKey) }?.let { return@withContext it }

    // Disk cache hit
    imageLoader.diskCache?.openSnapshot(diskKey)?.use { snapshot ->
      BitmapFactory.decodeStream(snapshot.data.toFile().inputStream())?.let { bmp ->
        val scaled = scaleBitmap(bmp, widthPx, heightPx)
        synchronized(memoryCache) { memoryCache.put(memKey, scaled) }
        return@withContext scaled
      }
    }

    // Extract directly via MediaMetadataRetriever HTTP streaming (efficient — only seeks header bytes)
    val bitmap = runCatching {
      val mmr = MediaMetadataRetriever()
      try {
        mmr.setDataSource(path, emptyMap())
        val raw = mmr.getFrameAtTime(0) ?: return@runCatching null
        // Rotate if needed
        val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val rotated = if (rotation != 0) {
          val m = Matrix().apply { postRotate(rotation.toFloat()) }
          val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
          if (r != raw) raw.recycle()
          r
        } else raw
        scaleBitmap(rotated, widthPx, heightPx)
      } finally {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mmr.close() else mmr.release()
      }
    }.getOrNull() ?: return@withContext null

    // Write to disk cache
    imageLoader.diskCache?.openEditor(diskKey)?.let { editor ->
      try {
        editor.data.toFile().outputStream().use { out ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        editor.commit()
      } catch (_: Exception) {
        runCatching { editor.abort() }
      }
    }

    synchronized(memoryCache) { memoryCache.put(memKey, bitmap) }
    _thumbnailReadyKeys.tryEmit(memKey)
    bitmap
  }

  /** The memory-cache key used by [getThumbnailForNetworkPath]. */
  fun thumbnailKeyForNetworkPath(path: String, widthPx: Int, heightPx: Int): String =
    "$path|network|$widthPx|$heightPx|${thumbnailModeKey()}"

  private fun folderSignature(
    videos: List<Video>,
    widthPx: Int,
    heightPx: Int,
  ): String {
    val md = MessageDigest.getInstance("MD5")
    md.update("$widthPx|$heightPx|${thumbnailModeKey()}|".toByteArray())
    for (video in videos) {
      md.update(video.path.toByteArray())
      md.update("|".toByteArray())
      md.update(video.size.toString().toByteArray())
      md.update("|".toByteArray())
      md.update(video.dateModified.toString().toByteArray())
      md.update(";".toByteArray())
    }
    return md.digest().joinToString("") { byte -> "%02x".format(byte) }
  }

  private fun thumbnailModeKey(): String =
    browserPreferences.thumbnailMode.get().thumbnailModeCacheKey(browserPreferences.thumbnailFramePosition.get())
}
