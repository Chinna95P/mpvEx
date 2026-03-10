package app.marlboroadvance.mpvex.utils.storage

internal fun shouldRunFilesystemVideoCheck(
  forceFileSystemCheck: Boolean,
  mediaStoreResultCount: Int,
): Boolean = forceFileSystemCheck || mediaStoreResultCount == 0

internal fun shouldIncludePrimaryStorageInFilesystemFolderScan(
  options: MediaScanOptions,
  forceFileSystemCheck: Boolean,
): Boolean = forceFileSystemCheck || options.includeNoMediaFolders
