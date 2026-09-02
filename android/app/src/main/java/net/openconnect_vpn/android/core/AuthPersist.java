/*
 * Persist VPN secrets only after the server accepted them.
 * Mirrors windows/src-tauri/src/vpn/persist.rs.
 */

package net.openconnect_vpn.android.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import net.openconnect_vpn.android.VpnProfile;

public final class AuthPersist {
	public static final String TAG = "OpenConnect";

	private static String pendingUsername = "";
	private static String pendingPassword = "";
	private static boolean pendingSaveForDomain = false;
	private static boolean pendingSavePassword = false;
	private static boolean pendingUsedShared = false;

	private AuthPersist() {
	}

	public static void reset() {
		pendingUsername = "";
		pendingPassword = "";
		pendingSaveForDomain = false;
		pendingSavePassword = false;
		pendingUsedShared = false;
	}

	public static void markUsedShared() {
		pendingUsedShared = true;
	}

	public static void capture(String username, String password, boolean saveForDomain, boolean savePassword) {
		if (username != null && !username.trim().isEmpty()) {
			pendingUsername = username.trim();
		}
		if (password != null && !password.isEmpty()) {
			pendingPassword = password;
		}
		pendingSaveForDomain = saveForDomain;
		pendingSavePassword = savePassword;
	}

	public static void afterAuthOk(Context context, VpnProfile profile) {
		if (profile == null || profile.mPrefs == null) {
			reset();
			return;
		}
		if (pendingPassword.isEmpty() && !pendingSaveForDomain && !pendingUsedShared) {
			reset();
			return;
		}
		SharedPreferences prefs = profile.mPrefs;
		String server = prefs.getString("server_address", "");
		String profileId = profile.getUUIDString();
		boolean shared = pendingUsedShared;

		if (pendingSaveForDomain && !pendingPassword.isEmpty()) {
			String domain = CredentialStore.loginDomain(server);
			if (domain != null && CredentialStore.writeRealm(context, domain, pendingUsername, pendingPassword)) {
				prefs.edit().remove("login_password").apply();
				ProfileManager.setSharedForDomain(domain, true);
				shared = true;
			} else if (domain == null) {
				Log.w(TAG, "realm save: no login domain for " + server);
			}
		}

		/* Local profile password only when not using / saving domain credentials. */
		if (!shared && pendingSavePassword && !pendingPassword.isEmpty()) {
			prefs.edit().putString("login_password", pendingPassword).apply();
		}

		if (!pendingUsername.isEmpty() || shared) {
			ProfileManager.rememberLogin(profileId, pendingUsername, shared);
		}
		reset();
	}

	public static void afterAuthFail(Context context, VpnProfile profile) {
		if (profile == null || profile.mPrefs == null) {
			reset();
			return;
		}
		SharedPreferences prefs = profile.mPrefs;
		prefs.edit().remove("login_password").apply();

		/*
		 * Wipe the shared realm only when the user just tried to (re)save domain
		 * credentials that the server rejected — not on every reused shared login
		 * failure (network / 2FA / group errors would otherwise erase a good password).
		 */
		if (pendingSaveForDomain && !pendingPassword.isEmpty()) {
			String domain = CredentialStore.loginDomain(prefs.getString("server_address", ""));
			if (domain != null) {
				CredentialStore.deleteRealm(context, domain);
				ProfileManager.setSharedForDomain(domain, false);
			}
		}
		reset();
	}
}
