package app.marlboroadvance.mpvex.di

import app.marlboroadvance.mpvex.domain.thumbnail.CoilVideoThumbnailDecoder
import app.marlboroadvance.mpvex.domain.thumbnail.toThumbnailStrategy
import app.marlboroadvance.mpvex.domain.anime4k.Anime4KManager
import app.marlboroadvance.mpvex.preferences.BrowserPreferences
import app.marlboroadvance.mpvex.repository.AniCliRepository
import app.marlboroadvance.mpvex.repository.wyzie.WyzieSearchRepository
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.CachePolicy
import coil3.request.crossfade
import okhttp3.OkHttpClient
import okio.FileSystem
import org.koin.dsl.module
import org.koin.android.ext.koin.androidContext
import java.util.concurrent.TimeUnit

val domainModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    single<ImageLoader> {
        val context = androidContext()
        val browserPreferences = get<BrowserPreferences>()
        ImageLoader.Builder(context)
            .components {
                add(
                    CoilVideoThumbnailDecoder.Factory(
                        thumbnailStrategy = {
                            browserPreferences.thumbnailMode.get().toThumbnailStrategy(
                                browserPreferences.thumbnailFramePosition.get()
                            )
                        }
                    )
                )
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache(
                DiskCache.Builder()
                    .fileSystem(FileSystem.SYSTEM)
                    .directory(context.cacheDir.resolve("thumbnails"))
                    .maxSizePercent(0.05)
                    .build()
            )
            .crossfade(true)
            .build()
    }
    single { Anime4KManager(androidContext()) }
    single { WyzieSearchRepository(androidContext(), get(), get(), get()) }
    single { AniCliRepository(get(), get()) }
}
