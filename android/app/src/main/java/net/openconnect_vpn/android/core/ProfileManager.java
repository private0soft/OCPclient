/*
 * Copyright (c) 2013, Kevin Cernekee
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * In addition, as a special exception, the copyright holders give
 * permission to link the code of portions of this program with the
 * OpenSSL library.
 */

package net.openconnect_vpn.android.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.openconnect_vpn.android.VpnProfile;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

public class ProfileManager {
	public static final String TAG = "OpenConnect";

	public static String fileSelectKeys[] =
		{ "ca_certificate", "user_certificate", "private_key", "custom_csd_wrapper" };

	private static final String PROFILE_PFX = "profile-";
	private static HashMap<String,VpnProfile> mProfiles;

	private static Context mContext;
	private static SharedPreferences mAppPrefs;

	private static final String ON_BOOT_PROFILE = "onBootProfile";
	private static final String RESTART_ON_BOOT = "restartvpnonboot" + "_FIXME"; // FIXME

	private static VpnProfile mLastConnectedVpn=null;
	private static boolean sLoaded;

	public static void init(Context context) {
		if (context != null) {
			Context app = context.getApplicationContext();
			mContext = app != null ? app : context;
			if (mAppPrefs == null) {
				mAppPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
			}
			if (mAppPrefs.contains("catalog_url")) {
				mAppPrefs.edit().remove("catalog_url").apply();
			}
		}
		if (sLoaded && mProfiles != null) {
			return;
		}
		mProfiles = new HashMap<String, VpnProfile>();

		File prefsdir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
	    if (prefsdir.exists() && prefsdir.isDirectory()) {
	    	String[] files = prefsdir.list();
	    	if (files != null) {
		    	for (String s : files) {
		    		if (s.startsWith(PROFILE_PFX) && s.endsWith(".xml")) {
		    			SharedPreferences p = context.getSharedPreferences(
		    					s.substring(0, s.length() - 4), Activity.MODE_PRIVATE);
		    			VpnProfile entry = new VpnProfile(p);
		    			if (!entry.isValid()) {
		    				Log.w(TAG, "removing bogus profile '" + s + "'");
		    			} else {
		    				dropCatalogMark(entry);
		    				mProfiles.put(entry.getUUIDString(), entry);
		    			}
		    		}
		    	}
	    	}
	    }
		for (VpnProfile profile : new ArrayList<VpnProfile>(mProfiles.values())) {
			maybeEnableSharedLogin(profile);
		}
		sLoaded = true;
	}

	/** Re-read profiles from disk after import or external edits. */
	public static void reload(Context context) {
		sLoaded = false;
		mProfiles = null;
		init(context);
	}

	public synchronized static Collection<VpnProfile> getProfiles() {
		init(mContext);
		return mProfiles.values();
	}

	public synchronized static VpnProfile get(String key) {
		return key == null ? null : mProfiles.get(key);
	}

	public static String getPrefsName(String uuid) {
		return PROFILE_PFX + uuid;
	}

	private static String capitalize(String in) {
		if (in.length() <= 4) {
			// These are almost always abbreviations
			return in.toUpperCase(Locale.getDefault());
		} else {
			// Longer names -> capitalize first letter only
			return Character.toUpperCase(in.charAt(0)) + in.substring(1);
		}
	}

	private static String makeProfName(String s, int index) {
		String orig = s;
		String suffix;

		if (index > 0) {
			suffix = " (" + index + ")";
		} else {
			suffix = "";
		}

		// leave IP addresses alone
		if ((s.matches("[0-9.]+") && s.matches(".*\\..*")) ||
			(s.matches("[0-9a-fA-F:]+") && s.matches(".*:.*"))) {
			return s + suffix;
		}

		// try to parse the hostname out of an URL
		if (s.matches(".*/.*")) {
			if (!s.matches("https://.*")) {
				s = "https://" + s;
			}

			s = Uri.parse(s).getHost();
			if (s == null || s.trim().equals("")) {
				// failed
				return orig + suffix;
			}
		}

		String ss[] = s.split("\\.");
		if (ss.length < 2) {
			// unqualified hostname (or junk)
			return capitalize(s) + suffix;
		}

		// Try to find the first private part of the FQDN.
		// This should probably use something like the Apache Public Suffix List, but it's not
		// worth the trouble right now.
		int i = ss.length - 1;
		if (ss[i].length() <= 2 && i > 1) {
			// if the TLD looks like a country code, check for a public SLD like .co
			String sld = ss[i - 1];
			if (sld.length() <= 2 || sld.equals("com")) {
				i--;
			}
		}

		s = ss[i - 1];
		if (s.length() < 2) {
			return orig + suffix;
		} else {
			return capitalize(s) + suffix;
		}
	}

	public synchronized static VpnProfile create(String hostname) {
		return create(hostname, null, null, null);
	}

	public synchronized static VpnProfile create(String hostname, String profileName,
			String username, String password) {
		String profName;

		if (profileName != null) {
			profileName = profileName.trim();
		}
		if (profileName != null && !profileName.isEmpty()) {
			profName = uniqueName(profileName);
		} else {
			// generate a non-conflicting name if necessary
			for (int i = 0; ; i++) {
				profName = makeProfName(hostname, i);
				if (getProfileByName(profName) == null) {
					break;
				}
			}
		}

		String uuid = UUID.randomUUID().toString();
		SharedPreferences p = mContext.getSharedPreferences(getPrefsName(uuid), Activity.MODE_PRIVATE);
		SharedPreferences.Editor ed = p.edit()
				.putString("server_address", hostname)
				.putString("batch_mode", "empty_only")
				.putString("vpn_protocol", "anyconnect")
				.putString("reported_os", "android")
				.putString("compression_mode", "none")
				.putBoolean("use_dtls", false)
				.putBoolean("disable_ipv6", true);
		if (username != null && !username.trim().isEmpty()) {
			ed.putString("login_username", username.trim());
		}
		if (password != null && !password.isEmpty()) {
			ed.putString("login_password", password);
		}
		ed.apply();

		VpnProfile profile = new VpnProfile(p, uuid, profName);
		mProfiles.put(uuid, profile);
		maybeEnableSharedLogin(profile);
		return profile;
	}

	private static String uniqueName(String desired) {
		if (desired == null || desired.trim().isEmpty()) {
			desired = "Profile";
		} else {
			desired = desired.trim();
		}
		if (getProfileByName(desired) == null) {
			return desired;
		}
		for (int i = 2; ; i++) {
			String candidate = desired + " (" + i + ")";
			if (getProfileByName(candidate) == null) {
				return candidate;
			}
		}
	}

	@SuppressWarnings("unchecked")
	private static void copyPrefs(SharedPreferences from, SharedPreferences.Editor to) {
		Map<String, ?> all = from.getAll();
		if (all == null) {
			return;
		}
		for (Map.Entry<String, ?> e : all.entrySet()) {
			Object v = e.getValue();
			if (v instanceof String) {
				to.putString(e.getKey(), (String) v);
			} else if (v instanceof Boolean) {
				to.putBoolean(e.getKey(), (Boolean) v);
			} else if (v instanceof Integer) {
				to.putInt(e.getKey(), (Integer) v);
			} else if (v instanceof Long) {
				to.putLong(e.getKey(), (Long) v);
			} else if (v instanceof Float) {
				to.putFloat(e.getKey(), (Float) v);
			} else if (v instanceof Set) {
				to.putStringSet(e.getKey(), new HashSet<String>((Set<String>) v));
			}
		}
	}

	private static boolean copyFile(File from, File to) {
		FileInputStream in = null;
		FileOutputStream out = null;
		try {
			in = new FileInputStream(from);
			out = new FileOutputStream(to);
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
			to.setReadable(true, true);
			return true;
		} catch (IOException e) {
			Log.e(TAG, "error copying " + from + " -> " + to, e);
			return false;
		} finally {
			try {
				if (in != null) {
					in.close();
				}
			} catch (IOException ignored) {
			}
			try {
				if (out != null) {
					out.close();
				}
			} catch (IOException ignored) {
			}
		}
	}

	public synchronized static VpnProfile duplicate(VpnProfile source) {
		if (source == null || source.mPrefs == null || !source.isValid()) {
			return null;
		}
		String profName = uniqueName(source.getName() + " copy");
		String uuid = UUID.randomUUID().toString();
		SharedPreferences dest = mContext.getSharedPreferences(getPrefsName(uuid),
				Activity.MODE_PRIVATE);
		SharedPreferences.Editor ed = dest.edit();
		copyPrefs(source.mPrefs, ed);
		ed.putString("profile_uuid", uuid);
		ed.putString("profile_name", profName);

		for (String key : fileSelectKeys) {
			String stored;
			try {
				stored = source.mPrefs.getString(key, null);
			} catch (ClassCastException e) {
				stored = null;
			}
			if (stored == null || stored.length() == 0) {
				continue;
			}
			String oldName = getCertFilename(source, key);
			if (!oldName.equals(stored)) {
				continue;
			}
			String newName = "cert." + uuid + "." + key;
			File from = new File(getCertPath() + oldName);
			File to = new File(getCertPath() + newName);
			if (from.isFile() && copyFile(from, to)) {
				ed.putString(key, newName);
			}
		}
		ed.apply();

		VpnProfile profile = new VpnProfile(dest);
		if (!profile.isValid()) {
			Log.e(TAG, "duplicated profile is invalid");
			return null;
		}
		mProfiles.put(uuid, profile);
		Log.i(TAG, "duplicated profile " + source.getUUIDString() + " -> " + uuid);
		return profile;
	}

	public synchronized static VpnProfile getProfileByName(String name) {
		if (name == null || mProfiles == null) {
			return null;
		}
		String lower = name.toLowerCase(Locale.getDefault());
		for (VpnProfile vpnp : mProfiles.values()) {
			String vname = vpnp.getName();
			if (vname != null && vname.toLowerCase(Locale.getDefault()).equals(lower)) {
				return vpnp;
			}
		}
		return null;
	}

	private static String getCertFilename(VpnProfile profile, String key) {
		return 	"cert." + profile.getUUIDString() + "." + key;
	}

	public static String getCertPath() {
		return mContext.getFilesDir().getPath() + File.separator;
	}

	public synchronized static void deleteFilePref(VpnProfile profile, String key) {
		String oldVal = profile.mPrefs.getString(key, null);
		if (getCertFilename(profile, key).equals(oldVal)) {
			File f = new File(getCertPath() + oldVal);
			if (!f.delete()) {
				Log.w(TAG, "error deleting " + oldVal);
			}
		}
	}

	public synchronized static String storeFilePref(VpnProfile profile, String key, Uri fromPath) {
		String filename = getCertFilename(profile, key);
		String toPath = getCertPath() + filename;

		try {
			InputStream in = mContext.getContentResolver().openInputStream(fromPath);
			File outFile = new File(toPath);
			FileOutputStream out = new FileOutputStream(outFile);
			byte buffer[] = new byte[65536];
			int len;
			while ((len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
			}

			in.close();
			out.close();
			outFile.setReadable(true, true);

			return filename;
		} catch (Exception e) {
			Log.e(TAG, "error copying " + fromPath + " -> " + toPath, e);

			try {
				new File(toPath).delete();
			} catch (Exception ee) {
			}

			return null;
		}
	}

	public synchronized static boolean delete(String uuid) {
		VpnProfile profile = get(uuid);
		if (profile == null) {
			Log.w(TAG, "error looking up profile " + uuid);
			return false;
		}

		for (String key : fileSelectKeys) {
			deleteFilePref(profile, key);
		}

		mProfiles.remove(uuid);

		if (mLastConnectedVpn != null && uuid.equals(mLastConnectedVpn.getUUIDString())) {
			setConnectedVpnProfileDisconnected();
		} else if (uuid.equals(mAppPrefs.getString(ON_BOOT_PROFILE, null))) {
			setConnectedVpnProfileDisconnected();
		}

		File f = new File(mContext.getApplicationInfo().dataDir + File.separator +
				"shared_prefs" + File.separator + PROFILE_PFX + uuid + ".xml");

		if (f.delete()) {
			Log.i(TAG, "deleted profile " + uuid);
			return true;
		} else {
			Log.w(TAG, "error deleting profile " + uuid);
			return false;
		}
	}

	public synchronized static void setConnectedVpnProfileDisconnected() {
		mLastConnectedVpn = null;
		mAppPrefs.edit()
			.remove(ON_BOOT_PROFILE)
			.apply();
	}

	public synchronized static void setConnectedVpnProfile(VpnProfile connectedProfile) {
		mLastConnectedVpn = connectedProfile;
		mAppPrefs.edit()
			.putString(ON_BOOT_PROFILE, connectedProfile.getUUIDString())
			.apply();
	}

	public synchronized static VpnProfile getOnBootProfile() {
		if (!mAppPrefs.getBoolean(RESTART_ON_BOOT, false)) {
			return null;
		}
		return get(mAppPrefs.getString(ON_BOOT_PROFILE, null));
	}

	public static VpnProfile getLastConnectedVpn() {
		return mLastConnectedVpn;
	}

	public static Context getAppContext() {
		return mContext;
	}

	public static String getServerAddress(VpnProfile profile) {
		if (profile == null || profile.mPrefs == null) {
			return "";
		}
		try {
			String s = profile.mPrefs.getString("server_address", "");
			return s != null ? s : "";
		} catch (ClassCastException e) {
			return "";
		}
	}

	public static String normalizeServer(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim().toLowerCase(Locale.getDefault());
		if (s.startsWith("https://")) {
			s = s.substring(8);
		} else if (s.startsWith("http://")) {
			s = s.substring(7);
		}
		while (s.endsWith("/")) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	/** Leftover online-list marker from older builds — treat as a normal profile. */
	private static void dropCatalogMark(VpnProfile profile) {
		if (profile == null || profile.mPrefs == null) {
			return;
		}
		try {
			if ("catalog".equals(profile.mPrefs.getString("profile_source", ""))) {
				profile.mPrefs.edit().remove("profile_source").apply();
			}
		} catch (ClassCastException ignored) {
		}
	}

	/** Enable shared login on every profile whose server maps to this domain. */
	public synchronized static void setSharedForDomain(String domain, boolean enable) {
		if (domain == null || domain.isEmpty() || mProfiles == null) {
			return;
		}
		for (VpnProfile profile : mProfiles.values()) {
			if (profile.mPrefs == null) {
				continue;
			}
			String server = profile.mPrefs.getString("server_address", "");
			String profileDomain = CredentialStore.loginDomain(server);
			if (domain.equals(profileDomain)) {
				SharedPreferences.Editor ed = profile.mPrefs.edit()
						.putBoolean("use_shared_login", enable);
				if (enable) {
					/* Prefer the shared realm over any stale per-profile password. */
					ed.putBoolean("save_password", true)
							.remove("login_password");
				}
				ed.apply();
			}
		}
	}

	/** If this host already has a shared realm, opt the profile in. */
	public static void maybeEnableSharedLogin(VpnProfile profile) {
		if (profile == null || profile.mPrefs == null || mContext == null) {
			return;
		}
		try {
			if (profile.mPrefs.getBoolean("use_shared_login", false)) {
				return;
			}
		} catch (ClassCastException ignored) {
		}
		String domain = CredentialStore.loginDomain(getServerAddress(profile));
		if (domain == null || domain.isEmpty()) {
			return;
		}
		if (CredentialStore.hasRealm(mContext, domain)) {
			profile.mPrefs.edit()
					.putBoolean("use_shared_login", true)
					.putBoolean("save_password", true)
					.remove("login_password")
					.apply();
		}
	}

	/** Keep username; only turn shared ON, never clear it here. */
	public synchronized static void rememberLogin(String uuid, String username, boolean shared) {
		VpnProfile profile = get(uuid);
		if (profile == null || profile.mPrefs == null) {
			return;
		}
		SharedPreferences.Editor ed = profile.mPrefs.edit()
				.putBoolean("save_password", true);
		if (shared) {
			ed.putBoolean("use_shared_login", true);
		}
		boolean noCache = profile.mPrefs.getBoolean("disable_username_caching", false);
		if (!noCache && username != null && !username.trim().isEmpty()) {
			ed.putString("login_username", username.trim());
		}
		ed.apply();
	}

}

