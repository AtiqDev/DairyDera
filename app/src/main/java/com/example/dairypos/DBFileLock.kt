package com.example.dairypos

import java.util.concurrent.locks.ReentrantLock

/**
 * Global lock used to serialize operations that touch the DB file on disk.
 * Acquire before closing/opening/copying the file; ActivityLogger also uses it
 * when performing inserts so inserts wait until file operations finish.
 */
object DBFileLock {
    val lock: ReentrantLock = ReentrantLock()
}