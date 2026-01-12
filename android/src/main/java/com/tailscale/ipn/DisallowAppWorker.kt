// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DisallowAppWorker handles adding or removing apps from the VPN disallow list.
 * When an app is disallowed, its traffic will bypass the VPN tunnel.
 *
 * This worker integrates with the existing App.disallowedPackageNames() mechanism
 * and uses the same SharedPreferences storage for consistency.
 */
class DisallowAppWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DisallowAppWorker"
        const val PACKAGE_NAME = "packageName"
        const val ACTION = "action" // "add" or "remove"
        const val ACTION_ADD = "add"
        const val ACTION_REMOVE = "remove"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packageName = inputData.getString(PACKAGE_NAME)
        val action = inputData.getString(ACTION) ?: ACTION_ADD

        if (packageName.isNullOrBlank()) {
            TSLog.e(TAG, "Package name is required")
            return@withContext Result.failure()
        }

        try {
            when (action) {
                ACTION_ADD -> {
                    // Verify the package exists before adding
                    if (isPackageInstalled(packageName)) {
                        DisallowedAppsManager.addDisallowedApp(applicationContext, packageName)
                        TSLog.d(TAG, "Added $packageName to disallow list")
                    } else {
                        TSLog.w(TAG, "Package $packageName is not installed")
                        return@withContext Result.failure()
                    }
                }
                ACTION_REMOVE -> {
                    DisallowedAppsManager.removeDisallowedApp(applicationContext, packageName)
                    TSLog.d(TAG, "Removed $packageName from disallow list")
                }
                else -> {
                    TSLog.e(TAG, "Unknown action: $action")
                    return@withContext Result.failure()
                }
            }

            Result.success()
        } catch (e: Exception) {
            TSLog.e(TAG, "Failed to update disallow list: $e")
            Result.failure()
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            applicationContext.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Utility object for managing disallowed apps from other parts of the app.
 * Uses the same SharedPreferences as App class for consistency.
 * All operations are synchronized to prevent race conditions when multiple
 * workers or threads try to modify the list simultaneously.
 */
object DisallowedAppsManager {
    private const val TAG = "DisallowedAppsManager"
    // Use the same SharedPreferences as App class
    private const val PREFS_NAME = "unencrypted"
    private const val KEY_DISALLOWED_APPS = "disallowedApps"
    
    // Lock object for synchronizing access to SharedPreferences
    private val lock = Any()

    /**
     * Get the set of user-disallowed apps (does not include built-in or MDM disallowed apps).
     * For the complete list including built-in apps, use App.get().disallowedPackageNames()
     */
    fun getDisallowedApps(context: Context): Set<String> {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet())?.toSet() ?: emptySet()
        }
    }

    /**
     * Set the user-disallowed apps.
     * Note: VPN restart is NOT automatic. Changes take effect on next VPN connection.
     */
    fun setDisallowedApps(context: Context, apps: Set<String>) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Use commit() instead of apply() to ensure synchronous write
            prefs.edit().putStringSet(KEY_DISALLOWED_APPS, apps).commit()
        }
    }

    /**
     * Add a single app to the disallow list.
     * Thread-safe: uses synchronization to prevent race conditions.
     * Note: VPN restart is NOT automatic. Changes take effect on next VPN connection.
     */
    fun addDisallowedApp(context: Context, packageName: String) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val apps = prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()
            apps.add(packageName)
            // Use commit() instead of apply() to ensure synchronous write
            prefs.edit().putStringSet(KEY_DISALLOWED_APPS, apps).commit()
        }
    }

    /**
     * Remove a single app from the disallow list.
     * Thread-safe: uses synchronization to prevent race conditions.
     * Note: VPN restart is NOT automatic. Changes take effect on next VPN connection.
     */
    fun removeDisallowedApp(context: Context, packageName: String) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val apps = prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()
            apps.remove(packageName)
            // Use commit() instead of apply() to ensure synchronous write
            prefs.edit().putStringSet(KEY_DISALLOWED_APPS, apps).commit()
        }
    }

    /**
     * Clear all user-disallowed apps.
     * Note: VPN restart is NOT automatic. Changes take effect on next VPN connection.
     */
    fun clearDisallowedApps(context: Context) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_DISALLOWED_APPS).commit()
        }
    }
}