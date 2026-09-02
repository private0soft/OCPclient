/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Look up the VPN's public IPv4, then resolve country for that address.
 * Never take country from a dual-stack "who am I" endpoint: Android may
 * reach it over IPv6 on the uplink while IPv4 already uses the tunnel.
 */

package net.openconnect_vpn.android.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Locale;

import org.json.JSONObject;

import android.content.SharedPreferences;
import android.util.Log;

public final class GeoLookup {

	public static final String TAG = "OpenConnect";

	public static final String PREF_ISO = "geo_iso";
	public static final String PREF_COUNTRY = "geo_country";
	public static final String PREF_IP4 = "geo_ip4";
	public static final String PREF_IP6 = "geo_ip6";
	public static final String PREF_ENABLED = "lookup_public_ip";

	private static final Charset UTF8 = Charset.forName("UTF-8");
	private static final String UA =
			"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

	public static final class Result {
		public String iso = "";
		public String country = "";
		public String ip4 = "";
		public String ip6 = "";
	}

	private GeoLookup() {
	}

	public static Result fetch(boolean wantV6) {
		try {
			String ip4 = fetchIpv4();
			if (ip4.length() == 0) {
				return null;
			}
			Result r = countryFor(ip4);
			if (r == null) {
				r = new Result();
			}
			r.ip4 = ip4;
			if (wantV6) {
				String v6 = fetchIpify("https://api64.ipify.org?format=json");
				if (v6.indexOf(':') >= 0) {
					r.ip6 = v6;
				}
			} else {
				r.ip6 = "";
			}
			return r;
		} catch (Exception e) {
			Log.w(TAG, "geo lookup failed", e);
			return null;
		}
	}

	public static void save(SharedPreferences prefs, Result r) {
		if (prefs == null || r == null) {
			return;
		}
		SharedPreferences.Editor ed = prefs.edit();
		if (r.iso != null && r.iso.trim().length() == 2) {
			ed.putString(PREF_ISO, r.iso.trim().toLowerCase(Locale.US));
		}
		if (r.country != null && r.country.trim().length() > 0) {
			ed.putString(PREF_COUNTRY, r.country.trim());
		}
		if (r.ip4 != null && r.ip4.length() > 0) {
			ed.putString(PREF_IP4, r.ip4);
		}
		if (r.ip6 != null && r.ip6.length() > 0) {
			ed.putString(PREF_IP6, r.ip6);
		} else {
			ed.remove(PREF_IP6);
		}
		ed.commit();
	}

	public static void clear(SharedPreferences prefs) {
		if (prefs == null) {
			return;
		}
		/* Keep ISO/country so the widget and status orb keep the last flag
		 * until the new lookup overwrites them. */
		prefs.edit()
				.remove(PREF_IP4)
				.remove(PREF_IP6)
				.commit();
	}

	public static String isoOf(SharedPreferences prefs) {
		return prefString(prefs, PREF_ISO).trim().toLowerCase(Locale.US);
	}

	public static String prefString(SharedPreferences prefs, String key) {
		if (prefs == null) {
			return "";
		}
		try {
			String v = prefs.getString(key, "");
			return v != null ? v : "";
		} catch (ClassCastException e) {
			return "";
		}
	}

	private static String fetchIpv4() {
		String ip = fetchIpify("https://api.ipify.org?format=json");
		if (isIpv4(ip)) {
			return ip;
		}
		ip = fetchPlain("https://ipv4.icanhazip.com/");
		if (isIpv4(ip)) {
			return ip;
		}
		return "";
	}

	private static Result countryFor(String ip4) {
		try {
			String body = httpGet("https://ipwho.is/" + ip4, 8000, "application/json");
			if (body == null) {
				return null;
			}
			JSONObject json = new JSONObject(body);
			if (!json.optBoolean("success", false)) {
				Log.w(TAG, "geo lookup unsuccessful: " + json.optString("message"));
				return null;
			}
			Result r = new Result();
			r.iso = json.optString("country_code", "").trim().toLowerCase(Locale.US);
			r.country = json.optString("country", "").trim();
			if (r.iso.length() != 2) {
				r.iso = "";
				r.country = "";
			}
			return r;
		} catch (Exception e) {
			Log.w(TAG, "country lookup failed", e);
			return null;
		}
	}

	private static boolean isIpv4(String ip) {
		if (ip == null || ip.length() == 0 || ip.indexOf(':') >= 0) {
			return false;
		}
		try {
			InetAddress addr = InetAddress.getByName(ip);
			byte[] raw = addr.getAddress();
			return raw != null && raw.length == 4;
		} catch (Exception e) {
			return false;
		}
	}

	private static String fetchIpify(String url) {
		try {
			String body = httpGet(url, 6000, "application/json");
			if (body == null) {
				return "";
			}
			return new JSONObject(body).optString("ip", "").trim();
		} catch (Exception e) {
			return "";
		}
	}

	private static String fetchPlain(String url) {
		try {
			String body = httpGet(url, 6000, null);
			return body != null ? body.trim() : "";
		} catch (Exception e) {
			return "";
		}
	}

	private static String httpGet(String url, int timeoutMs, String accept) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		try {
			conn.setConnectTimeout(timeoutMs);
			conn.setReadTimeout(timeoutMs);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("User-Agent", UA);
			if (accept != null) {
				conn.setRequestProperty("Accept", accept);
			}
			int code = conn.getResponseCode();
			InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
			if (in == null || code >= 400) {
				return null;
			}
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) >= 0) {
				bos.write(buf, 0, n);
			}
			in.close();
			return new String(bos.toByteArray(), UTF8);
		} finally {
			conn.disconnect();
		}
	}
}
