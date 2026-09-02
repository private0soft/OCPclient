/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * App-wide VPN log gate. Native and Java logs stay at ERROR unless the
 * user turns logging on in Settings and picks a level.
 */

package net.openconnect_vpn.android.core;

import android.content.SharedPreferences;

public final class AppLog {

	public static final String PREF_ENABLED = "log_enabled";
	public static final String PREF_LEVEL = "log_level";

	public static final String LEVEL_ERR = "err";
	public static final String LEVEL_INFO = "info";
	public static final String LEVEL_DEBUG = "debug";
	public static final String LEVEL_TRACE = "trace";

	private AppLog() {
	}

	public static boolean isEnabled(SharedPreferences prefs) {
		if (prefs == null) {
			return false;
		}
		try {
			return prefs.getBoolean(PREF_ENABLED, false);
		} catch (ClassCastException e) {
			return false;
		}
	}

	public static int minLevel(SharedPreferences prefs) {
		if (!isEnabled(prefs)) {
			return -1;
		}
		String value = LEVEL_INFO;
		try {
			value = prefs.getString(PREF_LEVEL, LEVEL_INFO);
		} catch (ClassCastException e) {
			return VPNLog.LEVEL_INFO;
		}
		if (LEVEL_TRACE.equals(value)) {
			return VPNLog.LEVEL_TRACE;
		}
		if (LEVEL_DEBUG.equals(value)) {
			return VPNLog.LEVEL_DEBUG;
		}
		if (LEVEL_ERR.equals(value)) {
			return VPNLog.LEVEL_ERR;
		}
		return VPNLog.LEVEL_INFO;
	}

	public static int nativeLevel(SharedPreferences prefs) {
		int level = minLevel(prefs);
		if (level < VPNLog.LEVEL_ERR) {
			return VPNLog.LEVEL_ERR;
		}
		return level;
	}

	public static boolean allows(SharedPreferences prefs, int level) {
		return level <= minLevel(prefs);
	}
}
