/*
 * Shared domain credentials (realm), mirroring windows/src-tauri/src/vpn/cred.rs.
 * Passwords live outside profile SharedPreferences under files/secrets/realm/.
 */

package net.openconnect_vpn.android.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.json.JSONObject;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

public final class CredentialStore {
	public static final String TAG = "OpenConnect";

	public static final class RealmSecret {
		public final String username;
		public final String password;

		public RealmSecret(String username, String password) {
			this.username = username != null ? username : "";
			this.password = password != null ? password : "";
		}

		public boolean isEmpty() {
			return password.isEmpty();
		}
	}

	public static final class DomainLogin {
		public final String domain;
		public final String username;

		public DomainLogin(String domain, String username) {
			this.domain = domain;
			this.username = username != null ? username : "";
		}
	}

	public static final class ResolvedSecret {
		public String username = "";
		public String password = "";

		public ResolvedSecret(String username, String password) {
			this.username = username != null ? username : "";
			this.password = password != null ? password : "";
		}
	}

	private CredentialStore() {
	}

	/** `server1.example.com` / `server2.example.com` → `example.com`. IPs: null. */
	public static String loginDomain(String server) {
		String host = hostOf(server);
		if (host == null) {
			return null;
		}
		if (isIpv4(host) || host.contains(":") || host.startsWith("[")) {
			return null;
		}
		host = host.replaceAll("\\.+$", "").toLowerCase(Locale.US);
		String[] labels = host.split("\\.");
		int count = 0;
		for (String label : labels) {
			if (!label.isEmpty()) {
				count++;
			}
		}
		if (count < 2) {
			return null;
		}
		String[] parts = new String[count];
		int i = 0;
		for (String label : labels) {
			if (!label.isEmpty()) {
				parts[i++] = label;
			}
		}
		return parts[count - 2] + "." + parts[count - 1];
	}

	public static boolean hasRealm(Context context, String domain) {
		RealmSecret secret = readRealm(context, domain);
		return secret != null && !secret.isEmpty();
	}

	public static String realmUsername(Context context, String domain) {
		RealmSecret secret = readRealm(context, domain);
		return secret != null ? secret.username : "";
	}

	public static boolean writeRealm(Context context, String domain, String username, String password) {
		if (password == null || password.isEmpty()) {
			deleteRealm(context, domain);
			return true;
		}
		try {
			File path = realmPath(context, domain);
			File parent = path.getParentFile();
			if (parent != null && !parent.exists() && !parent.mkdirs()) {
				return false;
			}
			JSONObject payload = new JSONObject();
			payload.put("username", username != null ? username : "");
			payload.put("password", password);
			byte[] raw = payload.toString().getBytes(StandardCharsets.UTF_8);
			File tmp = new File(path.getPath() + ".tmp");
			FileOutputStream out = new FileOutputStream(tmp);
			out.write(raw);
			out.close();
			if (path.exists() && !path.delete()) {
				tmp.delete();
				return false;
			}
			if (!tmp.renameTo(path)) {
				tmp.delete();
				return false;
			}
			path.setReadable(true, true);
			return true;
		} catch (Exception e) {
			Log.e(TAG, "writeRealm failed for " + domain, e);
			return false;
		}
	}

	public static void deleteRealm(Context context, String domain) {
		try {
			File path = realmPath(context, domain);
			if (path.isFile()) {
				path.delete();
			}
		} catch (Exception e) {
			Log.w(TAG, "deleteRealm: " + domain, e);
		}
	}

	public static RealmSecret readRealm(Context context, String domain) {
		try {
			File path = realmPath(context, domain);
			if (!path.isFile()) {
				return null;
			}
			FileInputStream in = new FileInputStream(path);
			byte[] buf = new byte[(int) path.length()];
			int read = in.read(buf);
			in.close();
			if (read <= 0) {
				return null;
			}
			JSONObject json = new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
			return new RealmSecret(json.optString("username", ""), json.optString("password", ""));
		} catch (Exception e) {
			return null;
		}
	}

	public static List<DomainLogin> listRealms(Context context) {
		List<DomainLogin> out = new ArrayList<DomainLogin>();
		File dir = realmDir(context);
		if (!dir.isDirectory()) {
			return out;
		}
		File[] files = dir.listFiles();
		if (files == null) {
			return out;
		}
		for (File file : files) {
			if (!file.isFile() || !file.getName().endsWith(".bin")) {
				continue;
			}
			String stem = file.getName();
			stem = stem.substring(0, stem.length() - 4);
			RealmSecret secret = readRealm(context, stem);
			if (secret == null || secret.isEmpty()) {
				continue;
			}
			out.add(new DomainLogin(stem, secret.username));
		}
		Collections.sort(out, new Comparator<DomainLogin>() {
			@Override
			public int compare(DomainLogin a, DomainLogin b) {
				return a.domain.compareTo(b.domain);
			}
		});
		return out;
	}

	/** Profile password first. Shared domain login only if the profile opted in. */
	public static ResolvedSecret resolve(Context context, String profilePassword, String profileUsername,
			String server, boolean useShared) {
		String username = profileUsername != null ? profileUsername.trim() : "";
		if (profilePassword != null && !profilePassword.isEmpty()) {
			return new ResolvedSecret(username, profilePassword);
		}
		if (useShared) {
			String domain = loginDomain(server);
			if (domain != null) {
				RealmSecret realm = readRealm(context, domain);
				if (realm != null && !realm.isEmpty()) {
					if (username.isEmpty()) {
						username = realm.username;
					}
					return new ResolvedSecret(username, realm.password);
				}
			}
		}
		return new ResolvedSecret(username, "");
	}

	private static File realmDir(Context context) {
		return new File(context.getFilesDir(), "secrets/realm");
	}

	private static File realmPath(Context context, String domain) throws IllegalArgumentException {
		String safe = domain.replaceAll("[^a-zA-Z0-9.-]", "");
		if (safe.isEmpty() || !safe.contains(".")) {
			throw new IllegalArgumentException("invalid domain");
		}
		return new File(realmDir(context), safe + ".bin");
	}

	private static String hostOf(String server) {
		if (server == null) {
			return null;
		}
		String s = server.trim();
		if (s.startsWith("https://")) {
			s = s.substring(8);
		} else if (s.startsWith("http://")) {
			s = s.substring(7);
		}
		int slash = s.indexOf('/');
		if (slash >= 0) {
			s = s.substring(0, slash);
		}
		int at = s.lastIndexOf('@');
		if (at >= 0) {
			s = s.substring(at + 1);
		}
		if (s.startsWith("[")) {
			int end = s.indexOf(']');
			if (end > 0) {
				return s.substring(1, end);
			}
		}
		int colon = s.lastIndexOf(':');
		if (colon > 0) {
			String port = s.substring(colon + 1);
			if (port.matches("[0-9]+")) {
				s = s.substring(0, colon);
			}
		}
		s = s.trim();
		return s.isEmpty() ? null : s;
	}

	private static boolean isIpv4(String host) {
		String[] parts = host.split("\\.");
		if (parts.length != 4) {
			return false;
		}
		for (String part : parts) {
			if (part.isEmpty() || part.length() > 3) {
				return false;
			}
			for (int i = 0; i < part.length(); i++) {
				if (!Character.isDigit(part.charAt(i))) {
					return false;
				}
			}
			int n = Integer.parseInt(part);
			if (n < 0 || n > 255) {
				return false;
			}
		}
		return true;
	}

	/** Parse hostname from a profile server field (also handles URLs). */
	public static String hostFromServer(String server) {
		String host = hostOf(server);
		if (host != null) {
			return host;
		}
		if (server != null && server.contains("/")) {
			String withScheme = server.matches("https?://.*") ? server : "https://" + server;
			String parsed = Uri.parse(withScheme).getHost();
			if (parsed != null && !parsed.trim().isEmpty()) {
				return parsed.trim();
			}
		}
		return server != null ? server.trim() : "";
	}
}
