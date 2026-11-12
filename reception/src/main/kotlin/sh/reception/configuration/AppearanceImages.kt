package sh.reception.configuration

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import sh.reception.client.HttpExchange

/**
 * Coil for uploaded appearance images: the SDK's own loader (never the host's singleton) with a small memory cache and
 * no disk cache; the service's cache headers and the appearance snapshot decide what is current.
 */
internal object AppearanceImages {
    private var loader: ImageLoader? = null

    fun loader(context: Context): ImageLoader = loader ?: ImageLoader.Builder(context.applicationContext)
        .okHttpClient { HttpExchange.client }
        .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizePercent(MEMORY_FRACTION).build() }
        .diskCache(null as DiskCache?)
        .build()
        .also { loader = it }

    private const val MEMORY_FRACTION = 0.02
}
