// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * TailscaleProvider allows external applications to query and control the VPN.
 *
 * Authority: com.tailscale.ipn.provider
 *
 * === QUERY URIs ===
 *
 * content://com.tailscale.ipn.provider/exit_node
 *   Returns: Cursor with columns [id, name, allow_lan_access]
 *
 * content://com.tailscale.ipn.provider/disallowed_apps
 *   Returns: Cursor with columns [package_name]
 *
 * === CALL METHODS ===
 *
 * Use contentResolver.call(uri, method, arg, extras) to execute actions:
 *
 * Method: "connect_vpn"
 *   Returns: Bundle with "success" (boolean)
 *
 * Method: "disconnect_vpn"
 *   Returns: Bundle with "success" (boolean)
 *
 * Method: "use_exit_node"
 *   Arg: exit node name/id (String), or empty/omit to remove exit node
 *   Extras (optional): "allow_lan_access" (boolean)
 *   Returns: Bundle with "success" (boolean)
 *
 * Method: "disallow_app"
 *   Arg: package name (String)
 *   Returns: Bundle with "success" (boolean)
 *
 * Method: "allow_app"
 *   Arg: package name (String)
 *   Returns: Bundle with "success" (boolean)
 *
 * === EXAMPLE USAGE (ADB) ===
 *
 * # Query exit node
 * adb shell content query --uri content://com.tailscale.ipn.provider/exit_node
 *
 * # Query disallowed apps
 * adb shell content query --uri content://com.tailscale.ipn.provider/disallowed_apps
 *
 * # Call methods (connect/disconnect)
 * adb shell content call --uri content://com.tailscale.ipn.provider --method connect_vpn
 * adb shell content call --uri content://com.tailscale.ipn.provider --method disconnect_vpn
 *
 * # Set exit node
 * adb shell content call --uri content://com.tailscale.ipn.provider --method use_exit_node --arg us-nyc-1
 *
 * # Set exit node with LAN access
 * adb shell content call --uri content://com.tailscale.ipn.provider --method use_exit_node --arg us-nyc-1 --extra allow_lan_access:b:true
 *
 * # Remove exit node (no --arg or empty)
 * adb shell content call --uri content://com.tailscale.ipn.provider --method use_exit_node
 *
 * # Disallow/Allow app
 * adb shell content call --uri content://com.tailscale.ipn.provider --method disallow_app --arg com.example.app
 * adb shell content call --uri content://com.tailscale.ipn.provider --method allow_app --arg com.example.app
 *
 * === EXAMPLE USAGE (Android Code) ===
 *
 * // Query exit node
 * val cursor = contentResolver.query(
 *     Uri.parse("content://com.tailscale.ipn.provider/exit_node"),
 *     null, null, null, null
 * )
 *
 * // Connect VPN
 * val result = contentResolver.call(
 *     Uri.parse("content://com.tailscale.ipn.provider"),
 *     "connect_vpn", null, null
 * )
 * val success = result?.getBoolean("success") ?: false
 *
 * // Use exit node
 * val result = contentResolver.call(
 *     Uri.parse("content://com.tailscale.ipn.provider"),
 *     "use_exit_node", "us-nyc-1", null
 * )
 *
 * // Use exit node with LAN access
 * val extras = Bundle().apply { putBoolean("allow_lan_access", true) }
 * val result = contentResolver.call(
 *     Uri.parse("content://com.tailscale.ipn.provider"),
 *     "use_exit_node", "us-nyc-1", extras
 * )
 */
class TailscaleProvider : ContentProvider() {

    companion object {
        private const val TAG = "TailscaleProvider"
        const val AUTHORITY = "com.tailscale.ipn.provider"

        // URI paths
        private const val PATH_EXIT_NODE = "exit_node"
        private const val PATH_DISALLOWED_APPS = "disallowed_apps"

        // URI matcher codes
        private const val CODE_EXIT_NODE = 1
        private const val CODE_DISALLOWED_APPS = 2

        // Call method names
        const val METHOD_CONNECT_VPN = "connect_vpn"
        const val METHOD_DISCONNECT_VPN = "disconnect_vpn"
        const val METHOD_USE_EXIT_NODE = "use_exit_node"
        const val METHOD_DISALLOW_APP = "disallow_app"
        const val METHOD_ALLOW_APP = "allow_app"

        // Bundle keys
        const val KEY_SUCCESS = "success"
        const val KEY_ERROR = "error"
        const val KEY_EXIT_NODE = "exit_node"
        const val KEY_ALLOW_LAN_ACCESS = "allow_lan_access"

        // SharedPreferences constants (same as App class)
        private const val PREFS_NAME = "unencrypted"
        private const val KEY_DISALLOWED_APPS = "disallowedApps"

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_EXIT_NODE, CODE_EXIT_NODE)
            addURI(AUTHORITY, PATH_DISALLOWED_APPS, CODE_DISALLOWED_APPS)
        }
    }

    override fun onCreate(): Boolean {
        TSLog.d(TAG, "TailscaleProvider created")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            CODE_EXIT_NODE -> queryExitNode()
            CODE_DISALLOWED_APPS -> queryDisallowedApps()
            else -> {
                TSLog.w(TAG, "Unknown URI: $uri")
                null
            }
        }
    }

    private fun queryExitNode(): Cursor {
        val cursor = MatrixCursor(arrayOf("id", "name", "allow_lan_access"))

        try {
            val prefs = Notifier.prefs.value
            val netmap = Notifier.netmap.value

            if (prefs != null) {
                val exitNodeId = prefs.ExitNodeID ?: ""
                val allowLanAccess = prefs.ExitNodeAllowLANAccess

                var exitNodeName: String? = null
                if (netmap != null && exitNodeId.isNotEmpty()) {
                    exitNodeName = UninitializedApp.getExitNodeName(prefs, netmap)
                }

                if (exitNodeId.isNotEmpty()) {
                    cursor.addRow(arrayOf(
                        exitNodeId,
                        exitNodeName ?: "unknown",
                        if (allowLanAccess) "true" else "false"
                    ))
                }
            }
        } catch (e: Exception) {
            TSLog.e(TAG, "Failed to query exit node: $e")
        }

        return cursor
    }

    private fun queryDisallowedApps(): Cursor {
        val cursor = MatrixCursor(arrayOf("package_name"))

        try {
            val ctx = context ?: return cursor
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val disallowedApps = prefs.getStringSet(KEY_DISALLOWED_APPS, emptySet()) ?: emptySet()

            for (packageName in disallowedApps) {
                cursor.addRow(arrayOf(packageName))
            }
        } catch (e: Exception) {
            TSLog.e(TAG, "Failed to query disallowed apps: $e")
        }

        return cursor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val result = Bundle()

        try {
            when (method) {
                METHOD_CONNECT_VPN -> {
                    enqueueWork(StartVPNWorker::class.java)
                    result.putBoolean(KEY_SUCCESS, true)
                }

                METHOD_DISCONNECT_VPN -> {
                    enqueueWork(StopVPNWorker::class.java)
                    result.putBoolean(KEY_SUCCESS, true)
                }

                METHOD_USE_EXIT_NODE -> {
                    // Exit node is passed via arg
                    // Empty/null arg means "remove exit node" (same as broadcast receiver)
                    val exitNode = arg ?: ""
                    val allowLanAccess = extras?.getBoolean(KEY_ALLOW_LAN_ACCESS, false) ?: false
                    val workData = Data.Builder()
                        .putString(UseExitNodeWorker.EXIT_NODE_NAME, exitNode)
                        .putBoolean(UseExitNodeWorker.ALLOW_LAN_ACCESS, allowLanAccess)
                        .build()
                    enqueueWork(UseExitNodeWorker::class.java, workData)
                    result.putBoolean(KEY_SUCCESS, true)
                }

                METHOD_DISALLOW_APP -> {
                    val packageName = arg
                    if (packageName.isNullOrEmpty()) {
                        result.putBoolean(KEY_SUCCESS, false)
                        result.putString(KEY_ERROR, "package name is required")
                    } else {
                        val workData = Data.Builder()
                            .putString(DisallowAppWorker.PACKAGE_NAME, packageName)
                            .putString(DisallowAppWorker.ACTION, DisallowAppWorker.ACTION_ADD)
                            .build()
                        enqueueWork(DisallowAppWorker::class.java, workData)
                        result.putBoolean(KEY_SUCCESS, true)
                    }
                }

                METHOD_ALLOW_APP -> {
                    val packageName = arg
                    if (packageName.isNullOrEmpty()) {
                        result.putBoolean(KEY_SUCCESS, false)
                        result.putString(KEY_ERROR, "package name is required")
                    } else {
                        val workData = Data.Builder()
                            .putString(DisallowAppWorker.PACKAGE_NAME, packageName)
                            .putString(DisallowAppWorker.ACTION, DisallowAppWorker.ACTION_REMOVE)
                            .build()
                        enqueueWork(DisallowAppWorker::class.java, workData)
                        result.putBoolean(KEY_SUCCESS, true)
                    }
                }

                else -> {
                    result.putBoolean(KEY_SUCCESS, false)
                    result.putString(KEY_ERROR, "Unknown method: $method")
                }
            }
        } catch (e: Exception) {
            TSLog.e(TAG, "Error executing method $method: $e")
            result.putBoolean(KEY_SUCCESS, false)
            result.putString(KEY_ERROR, e.message)
        }

        return result
    }

    private fun <T : androidx.work.ListenableWorker> enqueueWork(workerClass: Class<T>, inputData: Data? = null) {
        val ctx = context ?: return
        val workManager = WorkManager.getInstance(ctx)
        val requestBuilder = OneTimeWorkRequest.Builder(workerClass)
        if (inputData != null) {
            requestBuilder.setInputData(inputData)
        }
        workManager.enqueue(requestBuilder.build())
    }

    override fun getType(uri: Uri): String? {
        return when (uriMatcher.match(uri)) {
            CODE_EXIT_NODE -> "vnd.android.cursor.item/vnd.$AUTHORITY.exit_node"
            CODE_DISALLOWED_APPS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.disallowed_apps"
            else -> null
        }
    }

    // Not supported - read-only provider for queries, use call() for modifications
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
