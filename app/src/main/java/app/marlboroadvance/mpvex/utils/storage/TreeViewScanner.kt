package app.marlboroadvance.mpvex.utils.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import app.marlboroadvance.mpvex.domain.media.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Tree View Scanner - Optimized for tree/browser view
 * 
 * Includes parent folders and recursive counts
 * Used for directory navigation with hierarchical structure
 */
object TreeViewScanner {
    private const val TAG = "TreeViewScanner"
    
    // Smart cache with short TTL (30 seconds)
    private var cachedTreeViewData: Map<String, FolderData>? = null
    private var cacheTimestamp: Long = 0
    private var cacheOptionsKey: String? = null
    private const val CACHE_TTL_MS = 10_000L // 10 seconds for faster refresh
    
    /**
     * Clear cache (call when media library changes)
     */
    fun clearCache() {
        cachedTreeViewData = null
        cacheTimestamp = 0
        cacheOptionsKey = null
    }
    
    /**
     * Folder metadata with recursive counts
     */
    data class FolderData(
        val path: String,
        val name: String,
        val videoCount: Int,
        val totalSize: Long,
        val totalDuration: Long,
        val lastModified: Long,
        val hasSubfolders: Boolean = false
    )
    
    /**
     * Helper data class for video info during scanning
     */
    private data class VideoInfo(
        val size: Long,
        val duration: Long,
        val dateModified: Long
    )

    private fun mergeFolderData(
        existing: FolderData?,
        path: String,
        name: String,
        videoCount: Int,
        totalSize: Long,
        totalDuration: Long,
        lastModified: Long,
        hasSubfolders: Boolean,
    ): FolderData {
        if (existing == null) {
            return FolderData(
                path = path,
                name = name,
                videoCount = videoCount,
                totalSize = totalSize,
                totalDuration = totalDuration,
                lastModified = lastModified,
                hasSubfolders = hasSubfolders,
            )
        }

        return existing.copy(
            videoCount = existing.videoCount + videoCount,
            totalSize = existing.totalSize + totalSize,
            totalDuration = existing.totalDuration + totalDuration,
            lastModified = maxOf(existing.lastModified, lastModified),
            hasSubfolders = existing.hasSubfolders || hasSubfolders,
        )
    }
    
    /**
     * Get direct child folders of a parent directory for tree view
     * Uses recursive counts with parent hierarchy
     */
    suspend fun getFoldersInDirectory(
        context: Context,
        parentPath: String,
        options: MediaScanOptions = MediaScanOptions(),
        forceFileSystemCheck: Boolean = false,
    ): List<FolderData> = withContext(Dispatchers.IO) {
        val allFolders = getOrBuildTreeViewData(context, options, forceFileSystemCheck)
        
        // Filter for direct children only
        allFolders.values.filter { folder ->
            val parent = File(folder.path).parent
            parent == parentPath
        }.sortedBy { it.name.lowercase(Locale.getDefault()) }
    }
    
    /**
     * Get folder data with recursive counts for a specific path
     * Used for storage roots to show total video counts
     */
    suspend fun getFolderDataRecursive(
        context: Context,
        folderPath: String,
        options: MediaScanOptions = MediaScanOptions(),
        forceFileSystemCheck: Boolean = false,
    ): FolderData? = withContext(Dispatchers.IO) {
        val allFolders = getOrBuildTreeViewData(context, options, forceFileSystemCheck)
        
        // First try exact match
        allFolders[folderPath]?.let { return@withContext it }
        
        // If no exact match, aggregate from ALL descendants (not just immediate children)
        // This is needed for storage roots that don't have direct videos
        var totalCount = 0
        var totalSize = 0L
        var totalDuration = 0L
        var lastModified = 0L
        var hasSubfolders = false
        
        // Find all descendants (any folder that starts with this path)
        for ((path, data) in allFolders) {
            if (path.startsWith("$folderPath${File.separator}")) {
                // Check if this is a direct child (immediate subdirectory)
                val relativePath = path.substring(folderPath.length + 1)
                val isDirectChild = !relativePath.contains(File.separator)
                
                if (isDirectChild) {
                    // Direct child - its counts are already recursive, so just add them
                    totalCount += data.videoCount
                    totalSize += data.totalSize
                    totalDuration += data.totalDuration
                    if (data.lastModified > lastModified) {
                        lastModified = data.lastModified
                    }
                    hasSubfolders = true
                }
            }
        }
        
        if (totalCount > 0) {
            FolderData(
                path = folderPath,
                name = File(folderPath).name,
                videoCount = totalCount,
                totalSize = totalSize,
                totalDuration = totalDuration,
                lastModified = lastModified,
                hasSubfolders = hasSubfolders
            )
        } else {
            null
        }
    }
    
    /**
     * Get or build tree view data with smart caching
     */
    private suspend fun getOrBuildTreeViewData(
        context: Context,
        options: MediaScanOptions,
        forceFileSystemCheck: Boolean,
    ): Map<String, FolderData> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        
        // Return cached data if still valid
        cachedTreeViewData?.let { cached ->
            if (!forceFileSystemCheck && now - cacheTimestamp < CACHE_TTL_MS && cacheOptionsKey == options.cacheKey) {
                return@withContext cached
            }
        }
        
        // Build fresh data
        val data = buildTreeViewData(context, options, forceFileSystemCheck)
        
        // Update cache
        cachedTreeViewData = data
        cacheTimestamp = now
        cacheOptionsKey = options.cacheKey
        
        data
    }
    
    /**
     * Build tree view data (no caching)
     */
    private suspend fun buildTreeViewData(
        context: Context,
        options: MediaScanOptions,
        forceFileSystemCheck: Boolean,
    ): Map<String, FolderData> = withContext(Dispatchers.IO) {
        val allFolders = mutableMapOf<String, FolderData>()
        val noMediaPathFilter = NoMediaPathFilter(options)
        
        // Step 1: Scan MediaStore with recursive counts
        scanMediaStoreRecursive(context, allFolders, noMediaPathFilter)
        
        // Step 2: Scan filesystem for folders hidden from MediaStore.
        scanFileSystemRoots(context, allFolders, options, noMediaPathFilter, forceFileSystemCheck)
        
        // Step 3: Build parent folder hierarchy
        buildParentHierarchy(allFolders)
        
        allFolders
    }
    
    /**
     * Scan MediaStore for all videos and build folder map (recursive counts)
     */
    private fun scanMediaStoreRecursive(
        context: Context,
        folders: MutableMap<String, FolderData>,
        noMediaPathFilter: NoMediaPathFilter
    ) {
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                
                val videosByFolder = mutableMapOf<String, MutableList<VideoInfo>>()
                
                while (cursor.moveToNext()) {
                    val videoPath = cursor.getString(dataColumn)
                    val file = File(videoPath)
                    
                    if (!file.exists()) continue
                    if (noMediaPathFilter.shouldExcludeDirectory(file.parentFile)) continue
                    
                    val folderPath = file.parent ?: continue
                    val size = cursor.getLong(sizeColumn)
                    val duration = cursor.getLong(durationColumn)
                    val dateModified = cursor.getLong(dateColumn)
                    
                    videosByFolder.getOrPut(folderPath) { mutableListOf() }.add(
                        VideoInfo(size, duration, dateModified)
                    )
                }
                
                for ((folderPath, videos) in videosByFolder) {
                    val totalCount = videos.size
                    val totalSize = videos.sumOf { it.size }
                    val totalDuration = videos.sumOf { it.duration }
                    val lastModified = videos.maxOfOrNull { it.dateModified } ?: 0L

                    folders[folderPath] =
                        mergeFolderData(
                            existing = folders[folderPath],
                            path = folderPath,
                            name = File(folderPath).name,
                            videoCount = totalCount,
                            totalSize = totalSize,
                            totalDuration = totalDuration,
                            lastModified = lastModified,
                            hasSubfolders = false,
                        )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore scan error", e)
        }
    }
    
    /**
     * Scan external volumes (USB OTG, SD cards) via filesystem
     */
    private fun scanFileSystemRoots(
        context: Context,
        folders: MutableMap<String, FolderData>,
        options: MediaScanOptions,
        noMediaPathFilter: NoMediaPathFilter,
        forceFileSystemCheck: Boolean,
    ) {
        try {
            val rootsToScan = linkedSetOf<File>()

            if (shouldIncludePrimaryStorageInFilesystemFolderScan(options, forceFileSystemCheck)) {
                rootsToScan += Environment.getExternalStorageDirectory()
            }

            for (volume in StorageVolumeUtils.getExternalStorageVolumes(context)) {
                val volumePath = StorageVolumeUtils.getVolumePath(volume)
                if (volumePath == null) {
                    continue
                }

                rootsToScan += File(volumePath)
            }

            for (root in rootsToScan) {
                if (!root.exists() || !root.canRead() || !root.isDirectory) {
                    continue
                }

                scanDirectoryRecursive(root, folders, maxDepth = 20, options = options, noMediaPathFilter = noMediaPathFilter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Filesystem tree scan error", e)
        }
    }
    
    /**
     * Recursively scan directory for videos
     */
    private fun scanDirectoryRecursive(
        directory: File,
        folders: MutableMap<String, FolderData>,
        maxDepth: Int,
        currentDepth: Int = 0,
        options: MediaScanOptions,
        noMediaPathFilter: NoMediaPathFilter
    ) {
        if (currentDepth >= maxDepth) return
        if (!directory.exists() || !directory.canRead() || !directory.isDirectory) return
        if (FileFilterUtils.shouldSkipFolder(directory, options, noMediaPathFilter)) return
        
        try {
            val files = directory.listFiles() ?: return
            
            val videoFiles = mutableListOf<File>()
            val subdirectories = mutableListOf<File>()
            
            for (file in files) {
                try {
                    when {
                        file.isDirectory -> {
                            if (!FileFilterUtils.shouldSkipFolder(file, options, noMediaPathFilter)) {
                                subdirectories.add(file)
                            }
                        }
                        file.isFile -> {
                            if (FileFilterUtils.shouldSkipFile(file, options, noMediaPathFilter)) {
                                continue
                            }
                            val extension = file.extension.lowercase(Locale.getDefault())
                            if (FileTypeUtils.VIDEO_EXTENSIONS.contains(extension)) {
                                videoFiles.add(file)
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    continue
                }
            }
            
            // Add folder if it has videos
            if (videoFiles.isNotEmpty()) {
                val folderPath = directory.absolutePath
                
                // Skip if already from MediaStore
                val totalSize = videoFiles.sumOf { it.length() }
                val lastModified = videoFiles.maxOfOrNull { it.lastModified() } ?: 0L

                val existing = folders[folderPath]
                folders[folderPath] =
                    if (existing == null) {
                        FolderData(
                            path = folderPath,
                            name = directory.name,
                            videoCount = videoFiles.size,
                            totalSize = totalSize,
                            totalDuration = 0L,
                            lastModified = lastModified / 1000,
                            hasSubfolders = subdirectories.isNotEmpty(),
                        )
                    } else {
                        existing.copy(
                            hasSubfolders = existing.hasSubfolders || subdirectories.isNotEmpty(),
                        )
                    }
            }
            
            // Recurse into subdirectories
            for (subdir in subdirectories) {
                scanDirectoryRecursive(subdir, folders, maxDepth, currentDepth + 1, options, noMediaPathFilter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning: ${directory.absolutePath}", e)
        }
    }
    
    /**
     * Build parent folder hierarchy
     * Ensures intermediate folders (without direct videos) are included with correct recursive counts
     */
    private fun buildParentHierarchy(folders: MutableMap<String, FolderData>) {
        val allPaths = folders.keys.toMutableSet()

        for (folderPath in folders.keys.toList()) {
            var currentPath = File(folderPath).parent
            while (currentPath != null && currentPath != "/" && currentPath.length > 1) {
                allPaths += currentPath
                currentPath = File(currentPath).parent
            }
        }

        val aggregated = allPaths.associateWith { path ->
            folders[path] ?: FolderData(
                path = path,
                name = File(path).name,
                videoCount = 0,
                totalSize = 0L,
                totalDuration = 0L,
                lastModified = 0L,
                hasSubfolders = false,
            )
        }.toMutableMap()

        val sortedPaths = allPaths.sortedByDescending { it.count { c -> c == File.separatorChar } }
        for (path in sortedPaths) {
            val parentPath = File(path).parent ?: continue
            val childData = aggregated[path] ?: continue
            val parentData = aggregated[parentPath] ?: continue

            aggregated[parentPath] = parentData.copy(
                videoCount = parentData.videoCount + childData.videoCount,
                totalSize = parentData.totalSize + childData.totalSize,
                totalDuration = parentData.totalDuration + childData.totalDuration,
                lastModified = maxOf(parentData.lastModified, childData.lastModified),
                hasSubfolders = true,
            )
        }

        folders.clear()
        aggregated.values
            .filter { it.videoCount > 0 }
            .forEach { folderData ->
                folders[folderData.path] = folderData
            }
    }
}
