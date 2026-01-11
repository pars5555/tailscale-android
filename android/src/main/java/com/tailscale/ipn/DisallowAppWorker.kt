// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Context
import android.content.SharedPreferences
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

        // Use the same SharedPreferences as App class for consistency
        private const val PREFS_NAME = "unencrypted"
        private const val KEY_DISALLOWED_APPS = "disallowedApps"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packageName = inputData.getString(PACKAGE_NAME)
        val action = inputData.getString(ACTION) ?: ACTION_ADD

        if (packageName.isNullOrBlank()) {
            TSLog.e(TAG, "Package name is required")
            return@withContext Result.failure()
        }

        try {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val disallowedApps = getDisallowedApps(prefs).toMutableSet()

            when (action) {
                ACTION_ADD -> {
                    // Verify the package exists before adding
                    if (isPackageInstalled(packageName)) {
                        disallowedApps.add(packageName)
                        TSLog.d(TAG, "Added $packageName to disallow list")
                    } else {
                        TSLog.w(TAG, "Package $packageName is not installed")
                        return@withContext Result.failure()
                    }
                }
                ACTION_REMOVE -> {
                    disallowedApps.remove(packageName)
                    TSLog.d(TAG, "Removed $packageName from disallow list")
                }
                else -> {
                    TSLog.e(TAG, "Unknown action: $action")
                    return@withContext Result.failure()
                }
            }

            saveDisallowedApps(prefs, disallowedApps)

            // Restart the VPN to apply changes
            restartVpn()

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

    private fun getDisallowedApps(prefs: SharedPreferences): Set<String> {
        return prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet()) ?: emptySet()
    }

    private fun saveDisallowedApps(prefs: SharedPreferences, apps: Set<String>) {
        prefs.edit().putStringSet(KEY_DISALLOWED_APPS, apps).apply()
    }

    private fun restartVpn() {
        try {
            // Use App's restartVPN method for consistency
            App.get().restartVPN()
        } catch (e: Exception) {
            TSLog.e(TAG, "Failed to restart VPN: $e")
        }
    }
}

/**
 * Utility object for managing disallowed apps from other parts of the app.
 * Uses the same SharedPreferences as App class for consistency.
 */
object DisallowedAppsManager {
    private const val TAG = "DisallowedAppsManager"
    // Use the same SharedPreferences as App class
    private const val PREFS_NAME = "unencrypted"
    private const val KEY_DISALLOWED_APPS = "disallowedApps"

    /**
     * Get the set of user-disallowed apps (does not include built-in or MDM disallowed apps).
     * For the complete list including built-in apps, use App.get().disallowedPackageNames()
     */
    fun getDisallowedApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet()) ?: emptySet()
    }

    /**
     * Set the user-disallowed apps and restart VPN.
     */
    fun setDisallowedApps(context: Context, apps: Set<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_DISALLOWED_APPS, apps).apply()
        try {
            App.get().restartVPN()
        } catch (e: Exception) {
            TSLog.e(TAG, "Failed to restart VPN after setting disallowed apps: $e")
        }
    }

    /**
     * Add a single app to the disallow list and restart VPN.
     */
    fun addDisallowedApp(context: Context, packageName: String) {
        val apps = getDisallowedApps(context).toMutableSet()
        apps.add(packageName)
        setDisallowedApps(context, apps)
    }

    /**
     * Remove a single app from the disallow list and restart VPN.
     */
    fun removeDisallowedApp(context: Context, packageName: String) {
        val apps = getDisallowedApps(context).toMutableSet()
        apps.remove(packageName)
        setDisallowedApps(context, apps)
    }

    /**
     * Clear all user-disallowed apps and restart VPN.
     */
    fun clearDisallowedApps(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_DISALLOWED_APPS).apply()
        try {
            App.get().restartVPN()
        } catch (e: Exception) {
            TSLog.e(TAG, "Failed to restart VPN after clearing disallowed apps: $e")
        }
    }
}