/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Resolves global vs per-profile app routing for Android VpnService.
 */

package net.openconnect_vpn.android.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * Per-app VPN routing.
 *
 * Global defaults live in the default SharedPreferences.
 * Each profile can either inherit those defaults or define its own mode + package list.
 *
 * Android allows either an allowlist or a denylist on VpnService.Builder, never both:
 * https://developer.android.com/reference/android/net/VpnService.Builder
 */
public final class PerAppVpn {

	public static final String TAG = "OpenConnect";

	public static final String MODE_ALL = "all";
	public static final String MODE_ALLOWLIST = "allowlist";
	public static final String MODE_DENYLIST = "denylist";

	public static final String SOURCE_GLOBAL = "global";
	public static final String SOURCE_CUSTOM = "custom";

	public static final String PREF_GLOBAL_MODE = "global_per_app_mode";
	public static final String PREF_GLOBAL_PACKAGES = "global_per_app_packages";

	public static final String PREF_SOURCE = "per_app_source";
	public static final String PREF_MODE = "per_app_mode";
	public static final String PREF_PACKAGES = "per_app_packages";

	public static final class Config {
		public final String mode;
		public final Set<String> packages;
		public final boolean usingGlobal;

		public Config(String mode, Set<String> packages, boolean usingGlobal) {
			this.mode = mode != null ? mode : MODE_ALL;
			this.packages = packages != null
					? Collections.unmodifiableSet(new HashSet<String>(packages))
					: Collections.<String>emptySet();
			this.usingGlobal = usingGlobal;
		}
	}

	private PerAppVpn() {
	}

	public static SharedPreferences globalPrefs(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context);
	}

	public static SharedPreferences profilePrefs(Context context, String uuid) {
		return context.getSharedPreferences(ProfileManager.getPrefsName(uuid), Context.MODE_PRIVATE);
	}

	public static String getMode(SharedPreferences prefs, String key, String defaultMode) {
		String mode;
		try {
			mode = prefs.getString(key, defaultMode);
		} catch (ClassCastException e) {
			return defaultMode;
		}
		if (MODE_ALLOWLIST.equals(mode) || MODE_DENYLIST.equals(mode) || MODE_ALL.equals(mode)) {
			return mode;
		}
		return defaultMode;
	}

	public static Set<String> getPackages(SharedPreferences prefs, String key) {
		Set<String> stored;
		try {
			stored = prefs.getStringSet(key, null);
		} catch (ClassCastException e) {
			return new HashSet<String>();
		}
		if (stored == null || stored.isEmpty()) {
			return new HashSet<String>();
		}
		return new HashSet<String>(stored);
	}

	public static void setPackages(SharedPreferences prefs, String key, Set<String> packages) {
		prefs.edit().putStringSet(key, new HashSet<String>(packages)).apply();
	}

	/**
	 * Resolve effective routing for a profile: inherit global rules, or use profile custom rules.
	 */
	public static Config resolve(Context context, SharedPreferences profilePrefs) {
		String source;
		try {
			source = profilePrefs.getString(PREF_SOURCE, SOURCE_GLOBAL);
		} catch (ClassCastException e) {
			source = SOURCE_GLOBAL;
		}
		if (SOURCE_CUSTOM.equals(source)) {
			return new Config(
					getMode(profilePrefs, PREF_MODE, MODE_ALL),
					getPackages(profilePrefs, PREF_PACKAGES),
					false);
		}

		SharedPreferences global = globalPrefs(context);
		return new Config(
				getMode(global, PREF_GLOBAL_MODE, MODE_ALL),
				getPackages(global, PREF_GLOBAL_PACKAGES),
				true);
	}

	public static Config resolveForProfile(Context context, String uuid) {
		return resolve(context, profilePrefs(context, uuid));
	}

	/**
	 * Apply resolved config to the VPN builder. Safe to call when mode is "all".
	 * Uninstalled packages are skipped. Empty allowlist behaves like "all apps".
	 * This app always stays on the tunnel so geo/IP lookup sees the VPN address.
	 */
	public static void apply(VpnService.Builder builder, Context context, Config config) {
		if (config == null || MODE_ALL.equals(config.mode)) {
			Log.i(TAG, "Per-app VPN: all apps");
			return;
		}

		PackageManager pm = context.getPackageManager();
		String self = context.getPackageName();
		int applied = 0;

		if (MODE_ALLOWLIST.equals(config.mode)) {
			if (config.packages.isEmpty()) {
				Log.i(TAG, "Per-app VPN: allowlist empty → all apps");
				return;
			}
			if (allowApp(builder, self)) {
				applied++;
			}
			for (String pkg : config.packages) {
				if (self.equals(pkg)) {
					continue;
				}
				if (allowApp(builder, pkg)) {
					applied++;
				}
			}
			Log.i(TAG, "Per-app VPN: allowlist applied=" + applied
					+ " (self always included) source="
					+ (config.usingGlobal ? "global" : "profile"));
			return;
		}

		if (MODE_DENYLIST.equals(config.mode)) {
			for (String pkg : config.packages) {
				if (self.equals(pkg)) {
					Log.i(TAG, "Per-app VPN: never exclude self from tunnel");
					continue;
				}
				try {
					builder.addDisallowedApplication(pkg);
					applied++;
				} catch (PackageManager.NameNotFoundException e) {
					Log.w(TAG, "Per-app VPN: skip missing denylist app " + pkg);
				} catch (Exception e) {
					Log.w(TAG, "Per-app VPN: failed denylist " + pkg + ": " + e.getMessage());
				}
			}
			Log.i(TAG, "Per-app VPN: denylist applied=" + applied
					+ " source=" + (config.usingGlobal ? "global" : "profile"));
		}
	}

	private static boolean allowApp(VpnService.Builder builder, String pkg) {
		try {
			builder.addAllowedApplication(pkg);
			return true;
		} catch (PackageManager.NameNotFoundException e) {
			Log.w(TAG, "Per-app VPN: skip missing allowlist app " + pkg);
			return false;
		} catch (Exception e) {
			Log.w(TAG, "Per-app VPN: failed allowlist " + pkg + ": " + e.getMessage());
			return false;
		}
	}

	public static String summarize(Context context, Config config) {
		if (config == null || MODE_ALL.equals(config.mode)) {
			return context.getString(net.openconnect_vpn.android.R.string.per_app_mode_all);
		}
		int n = config.packages.size();
		if (MODE_ALLOWLIST.equals(config.mode)) {
			return context.getString(net.openconnect_vpn.android.R.string.per_app_summary_allowlist, n);
		}
		return context.getString(net.openconnect_vpn.android.R.string.per_app_summary_denylist, n);
	}
}
