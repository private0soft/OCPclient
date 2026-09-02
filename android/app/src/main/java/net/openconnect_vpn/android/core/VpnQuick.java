/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Shared connect/disconnect for the home widget and Quick Settings tile.
 */

package net.openconnect_vpn.android.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import net.openconnect_vpn.android.VpnProfile;
import net.openconnect_vpn.android.VpnToggleActivity;

public final class VpnQuick {

	public static final String PREF_CONN_STATE = "service_conn_state";
	public static final String PREF_UUID = "service_mUUID";
	public static final String PREF_LAST_PROFILE = "onBootProfile";

	private VpnQuick() {
	}

	public static SharedPreferences prefs(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
	}

	public static int connectionState(Context context) {
		try {
			return prefs(context).getInt(PREF_CONN_STATE,
					OpenConnectManagementThread.STATE_DISCONNECTED);
		} catch (ClassCastException e) {
			return OpenConnectManagementThread.STATE_DISCONNECTED;
		}
	}

	public static boolean isSessionActive(Context context) {
		int state = connectionState(context);
		return state != OpenConnectManagementThread.STATE_DISCONNECTED && state != 0;
	}

	public static boolean isConnected(Context context) {
		return connectionState(context) == OpenConnectManagementThread.STATE_CONNECTED;
	}

	public static VpnProfile lastProfile(Context context) {
		ProfileManager.init(context.getApplicationContext());
		SharedPreferences p = prefs(context);
		VpnProfile found = ProfileManager.get(p.getString(PREF_UUID, null));
		if (found == null) {
			found = ProfileManager.get(p.getString(PREF_LAST_PROFILE, null));
		}
		if (found != null && found.isValid()) {
			return found;
		}
		Collection<VpnProfile> all = ProfileManager.getProfiles();
		if (all == null || all.isEmpty()) {
			return null;
		}
		ArrayList<VpnProfile> list = new ArrayList<VpnProfile>(all);
		Collections.sort(list);
		return list.get(0);
	}

	public static Intent toggleIntent(Context context) {
		Intent intent = new Intent(context, VpnToggleActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
				| Intent.FLAG_ACTIVITY_NO_ANIMATION
				| Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
		return intent;
	}

	public static Intent disconnectIntent(Context context) {
		Intent intent = new Intent(context, OpenVpnService.class);
		intent.setAction(OpenVpnService.ACTION_DISCONNECT);
		return intent;
	}

	public static Intent connectIntent(Context context, String uuid) {
		Intent intent = new Intent(context, OpenVpnService.class);
		intent.putExtra(OpenVpnService.EXTRA_UUID, uuid);
		return intent;
	}
}
