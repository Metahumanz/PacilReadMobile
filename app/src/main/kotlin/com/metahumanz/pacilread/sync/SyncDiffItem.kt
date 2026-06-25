package com.metahumanz.pacilread.sync

class SyncDiffItem {
    @JvmField var entityType: String? = null
    @JvmField var key: String? = null
    @JvmField var title: String? = null
    @JvmField var status: String? = null
    @JvmField var summary: String? = null
    @JvmField var localUpdatedAt: Long = 0
    @JvmField var remoteUpdatedAt: Long = 0

    companion object {
        const val STATUS_LOCAL = "local"
        const val STATUS_REMOTE = "remote"
        const val STATUS_CONFLICT = "conflict"
        const val STATUS_UNCHANGED = "unchanged"
    }
}
