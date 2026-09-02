/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Minimal JSON backup of VPN profiles (prefs + optional cert files).
 */

package net.openconnect_vpn.android.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.openconnect_vpn.android.VpnProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public final class ProfileBackup {

	public static final String TAG = "OpenConnect";
	public static final String FORMAT = "myoc-profiles";
	public static final int FORMAT_VERSION = 3;

	private static final Charset UTF8 = Charset.forName("UTF-8");

	private static final String[] BOOLEAN_KEYS = {
		"use_dtls",
		"disable_username_caching",
		"disable_ipv6",
		"pass_tos",
		"reported_user_agent_override",
		"disable_xml_post",
		"require_pfs",
		"dpd_override"
	};

	private static final String[] LONG_KEYS = {
		"attempt", "connect", "cancel"
	};

	public static final class ImportResult {
		public int imported;
		public int skipped;
	}

	private ProfileBackup() {
	}

	public static ImportResult importAll(Context context, String jsonText) throws JSONException {
		ProfileManager.init(context);
		JSONObject root = new JSONObject(stripBom(jsonText));
		if (!FORMAT.equals(root.optString("format"))) {
			throw new JSONException("not a MyOC profile backup");
		}
		HashSet<String> seenServers = existingServers();
		JSONArray profiles = root.getJSONArray("profiles");
		ImportResult result = new ImportResult();
		for (int i = 0; i < profiles.length(); i++) {
			try {
				if (importOne(context, profiles.getJSONObject(i), seenServers)) {
					result.imported++;
				} else {
					result.skipped++;
				}
			} catch (Exception e) {
				Log.e(TAG, "skip broken profile at index " + i, e);
				result.skipped++;
			}
		}
		ProfileManager.reload(context);
		return result;
	}

	private static HashSet<String> existingServers() {
		HashSet<String> seen = new HashSet<String>();
		for (VpnProfile profile : ProfileManager.getProfiles()) {
			String host = "";
			try {
				host = profile.mPrefs.getString("server_address", "");
			} catch (ClassCastException e) {
				host = "";
			}
			String key = normalizeServer(host);
			if (key.length() > 0) {
				seen.add(key);
			}
		}
		return seen;
	}

	private static String normalizeServer(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim().toLowerCase(Locale.US);
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

	private static boolean importOne(Context context, JSONObject item,
			Set<String> seenServers) throws JSONException {
		JSONObject prefsJson;
		if (item.has("prefs")) {
			prefsJson = item.getJSONObject("prefs");
		} else {
			prefsJson = flatPrefs(item);
		}
		String server = unwrapString(prefsJson, "server_address", "");
		if (server == null || server.trim().isEmpty()) {
			return false;
		}
		String serverKey = ProfileManager.normalizeServer(server);
		if (serverKey.length() > 0 && seenServers.contains(serverKey)) {
			Log.i(TAG, "skip duplicate server " + serverKey);
			return false;
		}
		String name = unwrapString(prefsJson, "profile_name", "Imported");
		if (name == null || name.trim().isEmpty()) {
			name = server.trim();
		}
		name = uniqueName(name.trim());
		ProfileManager.create(server.trim(), name, null, null);
		if (serverKey.length() > 0) {
			seenServers.add(serverKey);
		}
		Log.i(TAG, "imported profile '" + name + "'");
		return true;
	}

	private static JSONObject flatPrefs(JSONObject item) throws JSONException {
		JSONObject prefs = new JSONObject();
		String server = firstString(item, new String[] {
				"server", "server_address", "host", "hostname", "address"
		});
		String name = firstString(item, new String[] {
				"name", "profile_name", "title", "label"
		});
		prefs.put("server_address", wrapString(server));
		prefs.put("profile_name", wrapString(name));
		return prefs;
	}

	private static String firstString(JSONObject item, String[] keys) {
		for (String key : keys) {
			String value = item.optString(key, "").trim();
			if (value.length() > 0) {
				return value;
			}
		}
		return "";
	}

	private static JSONObject wrapString(String value) throws JSONException {
		JSONObject wrapped = new JSONObject();
		wrapped.put("t", "s");
		wrapped.put("v", value != null ? value : "");
		return wrapped;
	}

	private static void putPref(SharedPreferences.Editor ed, String key, Object value) {
		if (value == null || value == JSONObject.NULL) {
			return;
		}
		if (value instanceof JSONObject) {
			JSONObject wrapped = (JSONObject) value;
			String type = wrapped.optString("t", "");
			Object inner = wrapped.opt("v");
			if (type.length() > 0) {
				putTyped(ed, key, type, inner);
				return;
			}
		}
		putLegacy(ed, key, value);
	}

	private static void putTyped(SharedPreferences.Editor ed, String key, String type, Object value) {
		if (value == null || value == JSONObject.NULL) {
			return;
		}
		if ("b".equals(type)) {
			ed.putBoolean(key, toBoolean(value, false));
		} else if ("i".equals(type) || "l".equals(type)) {
			ed.putLong(key, toLong(value, 0));
		} else if ("f".equals(type)) {
			ed.putFloat(key, toFloat(value, 0f));
		} else if ("ss".equals(type)) {
			ed.putStringSet(key, toStringSet(value));
		} else {
			ed.putString(key, String.valueOf(value));
		}
	}

	private static void putLegacy(SharedPreferences.Editor ed, String key, Object value) {
		if (value instanceof JSONArray || isStringSetKey(key)) {
			ed.putStringSet(key, toStringSet(value));
			return;
		}
		if (isBooleanKey(key)) {
			ed.putBoolean(key, toBoolean(value, false));
			return;
		}
		if (isLongKey(key)) {
			ed.putLong(key, toLong(value, 0));
			return;
		}
		if (value instanceof Boolean) {
			ed.putBoolean(key, (Boolean) value);
		} else if (value instanceof Number) {
			/* Stats are Long; storing Integer here crashes getLong() later. */
			ed.putLong(key, ((Number) value).longValue());
		} else {
			String s = String.valueOf(value);
			if (isBooleanKey(key)) {
				ed.putBoolean(key, toBoolean(s, false));
			} else {
				ed.putString(key, s);
			}
		}
	}

	private static boolean isBooleanKey(String key) {
		for (String k : BOOLEAN_KEYS) {
			if (k.equals(key)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isLongKey(String key) {
		if (key.endsWith("_first") || key.endsWith("_prev")) {
			return true;
		}
		for (String k : LONG_KEYS) {
			if (k.equals(key)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isStringSetKey(String key) {
		return PerAppVpn.PREF_PACKAGES.equals(key)
				|| PerAppVpn.PREF_GLOBAL_PACKAGES.equals(key);
	}

	private static boolean toBoolean(Object value, boolean def) {
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof Number) {
			return ((Number) value).intValue() != 0;
		}
		String s = String.valueOf(value).trim().toLowerCase(Locale.US);
		if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) {
			return true;
		}
		if ("false".equals(s) || "0".equals(s) || "no".equals(s) || s.isEmpty()) {
			return false;
		}
		return def;
	}

	private static long toLong(Object value, long def) {
		if (value instanceof Number) {
			return ((Number) value).longValue();
		}
		try {
			return Long.parseLong(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static float toFloat(Object value, float def) {
		if (value instanceof Number) {
			return ((Number) value).floatValue();
		}
		try {
			return Float.parseFloat(String.valueOf(value).trim());
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static Set<String> toStringSet(Object value) {
		HashSet<String> set = new HashSet<String>();
		if (value instanceof JSONArray) {
			JSONArray arr = (JSONArray) value;
			for (int j = 0; j < arr.length(); j++) {
				Object item = arr.opt(j);
				if (item != null && item != JSONObject.NULL) {
					set.add(String.valueOf(item));
				}
			}
		} else if (value != null && value != JSONObject.NULL) {
			String s = String.valueOf(value).trim();
			if (!s.isEmpty()) {
				set.add(s);
			}
		}
		return set;
	}

	public static String unwrapString(JSONObject prefsJson, String key, String def) {
		Object value = prefsJson.opt(key);
		if (value == null || value == JSONObject.NULL) {
			return def;
		}
		if (value instanceof JSONObject) {
			Object inner = ((JSONObject) value).opt("v");
			if (inner != null && inner != JSONObject.NULL) {
				return String.valueOf(inner);
			}
		}
		String s = String.valueOf(value);
		return s.isEmpty() ? def : s;
	}

	private static String uniqueName(String base) {
		if (ProfileManager.getProfileByName(base) == null) {
			return base;
		}
		for (int i = 2; ; i++) {
			String candidate = base + " (" + i + ")";
			if (ProfileManager.getProfileByName(candidate) == null) {
				return candidate;
			}
		}
	}

	private static String stripBom(String text) {
		if (text != null && text.length() > 0 && text.charAt(0) == '\uFEFF') {
			return text.substring(1);
		}
		return text;
	}

	public static String readUtf8(InputStream in) throws java.io.IOException {
		return new String(readFully(in), UTF8);
	}

	private static byte[] readFully(InputStream in) throws java.io.IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) >= 0) {
			bos.write(buf, 0, n);
		}
		in.close();
		return bos.toByteArray();
	}
}
