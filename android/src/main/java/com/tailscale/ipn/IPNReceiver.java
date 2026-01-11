// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.tailscale.ipn.ui.notifier.Notifier;

import java.util.Objects;
import java.util.Set;

/**
 * IPNReceiver allows external applications to control the VPN.
 *
 * Supported intents:
 * - com.tailscale.ipn.CONNECT_VPN: Start the VPN connection
 * - com.tailscale.ipn.DISCONNECT_VPN: Stop the VPN connection
 * - com.tailscale.ipn.USE_EXIT_NODE: Set exit node (extras: exitNode, allowLanAccess)
 * - com.tailscale.ipn.GET_EXIT_NODE: Get currently selected exit node (logs result)
 * - com.tailscale.ipn.DISALLOW_APP: Add app to VPN bypass list (extras: packageName)
 * - com.tailscale.ipn.ALLOW_APP: Remove app from VPN bypass list (extras: packageName)
 * - com.tailscale.ipn.GET_DISALLOWED_APPS: Get list of disallowed apps (logs result)
 *
 * Example usage:
 *
 * # Connect/Disconnect VPN
 * adb shell am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.CONNECT_VPN
 * adb shell am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.DISCONNECT_VPN
 *
 * # Set exit node
 * adb shell am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.USE_EXIT_NODE --es exitNode "us-nyc-1"
 *
 * # Get current exit node (check logcat -s TailscaleExitNode for result)
 * adb shell am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.GET_EXIT_NODE
 *
 * # Add app to bypass list (app traffic will NOT go through VPN)
 * adb shell am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.DISALLOW_APP --es packageName "com.example.app"
 *
 * # Remove app from bypass list (app traffic will go through VPN)
 * adb shell am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.ALLOW_APP --es packageName "com.example.app"
 *
 * # Get all disallowed apps (check logcat -s TailscaleDisallowedApps for result)
 * adb shell am broadcast -n com.tailscale.ipn/.IPNReceiver -a com.tailscale.ipn.GET_DISALLOWED_APPS
 */
public class IPNReceiver extends BroadcastReceiver {

    private static final String TAG = "IPNReceiver";

    public static final String INTENT_CONNECT_VPN = "com.tailscale.ipn.CONNECT_VPN";
    public static final String INTENT_DISCONNECT_VPN = "com.tailscale.ipn.DISCONNECT_VPN";
    public static final String INTENT_USE_EXIT_NODE = "com.tailscale.ipn.USE_EXIT_NODE";
    public static final String INTENT_GET_EXIT_NODE = "com.tailscale.ipn.GET_EXIT_NODE";
    public static final String INTENT_DISALLOW_APP = "com.tailscale.ipn.DISALLOW_APP";
    public static final String INTENT_ALLOW_APP = "com.tailscale.ipn.ALLOW_APP";
    public static final String INTENT_GET_DISALLOWED_APPS = "com.tailscale.ipn.GET_DISALLOWED_APPS";

    // SharedPreferences constants (same as App class)
    private static final String PREFS_NAME = "unencrypted";
    private static final String KEY_DISALLOWED_APPS = "disallowedApps";

    @Override
    public void onReceive(Context context, Intent intent) {
        WorkManager workManager = WorkManager.getInstance(context);
        String action = intent.getAction();

        if (Objects.equals(action, INTENT_CONNECT_VPN)) {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StartVPNWorker.class).build());

        } else if (Objects.equals(action, INTENT_DISCONNECT_VPN)) {
            workManager.enqueue(new OneTimeWorkRequest.Builder(StopVPNWorker.class).build());

        } else if (Objects.equals(action, INTENT_USE_EXIT_NODE)) {
            String exitNode = intent.getStringExtra("exitNode");
            boolean allowLanAccess = intent.getBooleanExtra("allowLanAccess", false);
            Data.Builder workData = new Data.Builder();
            workData.putString(UseExitNodeWorker.EXIT_NODE_NAME, exitNode);
            workData.putBoolean(UseExitNodeWorker.ALLOW_LAN_ACCESS, allowLanAccess);
            workManager.enqueue(new OneTimeWorkRequest.Builder(UseExitNodeWorker.class).setInputData(workData.build()).build());

        } else if (Objects.equals(action, INTENT_GET_EXIT_NODE)) {
            handleGetExitNode(context);

        } else if (Objects.equals(action, INTENT_DISALLOW_APP)) {
            String packageName = intent.getStringExtra("packageName");
            if (packageName == null || packageName.isEmpty()) {
                android.util.Log.e(TAG, "DISALLOW_APP requires 'packageName' extra");
                return;
            }
            Data.Builder workData = new Data.Builder();
            workData.putString(DisallowAppWorker.PACKAGE_NAME, packageName);
            workData.putString(DisallowAppWorker.ACTION, DisallowAppWorker.ACTION_ADD);
            workManager.enqueue(new OneTimeWorkRequest.Builder(DisallowAppWorker.class).setInputData(workData.build()).build());

        } else if (Objects.equals(action, INTENT_ALLOW_APP)) {
            String packageName = intent.getStringExtra("packageName");
            if (packageName == null || packageName.isEmpty()) {
                android.util.Log.e(TAG, "ALLOW_APP requires 'packageName' extra");
                return;
            }
            Data.Builder workData = new Data.Builder();
            workData.putString(DisallowAppWorker.PACKAGE_NAME, packageName);
            workData.putString(DisallowAppWorker.ACTION, DisallowAppWorker.ACTION_REMOVE);
            workManager.enqueue(new OneTimeWorkRequest.Builder(DisallowAppWorker.class).setInputData(workData.build()).build());

        } else if (Objects.equals(action, INTENT_GET_DISALLOWED_APPS)) {
            handleGetDisallowedApps(context);
        }
    }

    /**
     * Handles GET_EXIT_NODE intent - retrieves and logs the current exit node.
     * The result is logged with tag "TailscaleExitNode" for easy filtering.
     */
    private void handleGetExitNode(Context context) {
        try {
            String exitNodeId = null;
            String exitNodeName = null;
            boolean allowLanAccess = false;

            // Get current prefs from Notifier
            var prefs = Notifier.INSTANCE.getPrefs().getValue();
            var netmap = Notifier.INSTANCE.getNetmap().getValue();

            if (prefs != null) {
                exitNodeId = prefs.getExitNodeID();
                allowLanAccess = prefs.getExitNodeAllowLANAccess();

                // Try to get the exit node name from netmap
                if (netmap != null && exitNodeId != null && !exitNodeId.isEmpty()) {
                    exitNodeName = UninitializedApp.Companion.getExitNodeName(prefs, netmap);
                }
            }

            String result;
            if (exitNodeId == null || exitNodeId.isEmpty()) {
                result = "EXIT_NODE: none";
            } else {
                result = String.format("EXIT_NODE: id=%s, name=%s, allowLanAccess=%b",
                        exitNodeId,
                        exitNodeName != null ? exitNodeName : "unknown",
                        allowLanAccess);
            }

            // Log with specific tag for easy filtering: adb logcat -s TailscaleExitNode
            android.util.Log.i("TailscaleExitNode", result);
            android.util.Log.i(TAG, result);

        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to get exit node: " + e.getMessage());
            android.util.Log.i("TailscaleExitNode", "EXIT_NODE: error - " + e.getMessage());
        }
    }

    /**
     * Handles GET_DISALLOWED_APPS intent - retrieves and logs all disallowed apps.
     * The result is logged with tag "TailscaleDisallowedApps" for easy filtering.
     */
    private void handleGetDisallowedApps(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Set<String> disallowedApps = prefs.getStringSet(KEY_DISALLOWED_APPS, null);

            String result;
            if (disallowedApps == null || disallowedApps.isEmpty()) {
                result = "DISALLOWED_APPS: none";
            } else {
                result = "DISALLOWED_APPS: " + String.join(", ", disallowedApps);
            }

            // Log with specific tag for easy filtering: adb logcat -s TailscaleDisallowedApps
            android.util.Log.i("TailscaleDisallowedApps", result);
            android.util.Log.i(TAG, result);

            // Also log count
            int count = disallowedApps != null ? disallowedApps.size() : 0;
            android.util.Log.i("TailscaleDisallowedApps", "DISALLOWED_APPS_COUNT: " + count);

        } catch (Exception e) {
            android.util.Log.e(TAG, "Failed to get disallowed apps: " + e.getMessage());
            android.util.Log.i("TailscaleDisallowedApps", "DISALLOWED_APPS: error - " + e.getMessage());
        }
    }
}