/*
 * HTTPS-only version manifest. Automatic checks are silent.
 * Update APK is fetched over HTTPS (GitHub raw) and installed with the
 * Android APK MIME type. Opening raw.githubusercontent.com in a browser
 * stores application/octet-stream and the installer reports an invalid package.
 *
 * Expected JSON:
 * {
 *   "versionCode": 42,
 *   "versionName": "1.0.5",
 *   "notes": "Optional short notes",
 *   "url": "https://host/path/OpenConnect-P_latest.apk"
 * }
 */

package net.openconnect_vpn.android.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import net.openconnect_vpn.android.R;

public final class UpdateCheck {

	public static final String TAG = "OpenConnect";

	private static final int MAX_BODY = 16 * 1024;
	private static final int TIMEOUT_MS = 8000;
	private static final Charset UTF8 = Charset.forName("UTF-8");

	private static final List<Listener> sListeners = new ArrayList<Listener>();
	private static Info sPending = null;
	private static Context sApp = null;
	private static boolean sChecking = false;
	private static boolean sLaunchTried = false;
	private static boolean sConnectTried = false;
	private static boolean sConnectScheduled = false;
	private static boolean sWantConnectCheck = false;
	private static boolean sHaveResult = false;

	public static final class Info {
		public boolean available;
		public boolean checked;
		public boolean failed;
		public int versionCode;
		public String versionName = "";
		public String notes = "";
		public String pageUrl = "";
		public String message = "";
	}

	public interface Listener {
		void onUpdateState(Info info, boolean checking);
	}

	private UpdateCheck() {
	}

	public static void addListener(Listener listener) {
		if (listener == null) {
			return;
		}
		synchronized (sListeners) {
			if (!sListeners.contains(listener)) {
				sListeners.add(listener);
			}
		}
		listener.onUpdateState(sPending, sChecking);
	}

	public static void removeListener(Listener listener) {
		synchronized (sListeners) {
			sListeners.remove(listener);
		}
	}

	public static Info current() {
		return sPending;
	}

	public static boolean isChecking() {
		return sChecking;
	}

	public static String installedVersionName(Context context) {
		try {
			PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return pi.versionName != null ? pi.versionName : "";
		} catch (Exception e) {
			return "";
		}
	}

	/** Silent check when the app is opened. Does not require VPN. Failure is ignored. */
	public static void maybeCheck(Activity activity) {
		if (activity == null || sLaunchTried || sChecking || sHaveResult) {
			return;
		}
		rememberApp(activity);
		sLaunchTried = true;
		check(sApp, true);
	}

	/** Silent check once after the first VPN connect in this process. */
	public static void checkAfterConnect(Activity activity) {
		if (activity == null) {
			return;
		}
		rememberApp(activity);
		sWantConnectCheck = true;
		tryConnectCheck(true);
	}

	/**
	 * Settings button. If an update is already known, opens the download URL.
	 * If the app is already current, does nothing (avoids repeat requests).
	 */
	public static void runNow(Activity activity) {
		if (activity == null || sChecking) {
			return;
		}
		if (sHaveResult && sPending != null) {
			if (sPending.available) {
				startInstall(activity);
			}
			return;
		}
		rememberApp(activity);
		check(sApp, false);
	}

	public static void startInstall(final Activity activity) {
		if (activity == null || sPending == null || !sPending.available) {
			return;
		}
		final String href = sPending.pageUrl != null ? sPending.pageUrl.trim() : "";
		if (href.length() == 0) {
			Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
			return;
		}
		if (Build.VERSION.SDK_INT >= 26
				&& !activity.getPackageManager().canRequestPackageInstalls()) {
			try {
				activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
						Uri.parse("package:" + activity.getPackageName())));
			} catch (Exception e) {
				Log.w(TAG, "unknown sources settings failed", e);
			}
			Toast.makeText(activity, R.string.update_allow_unknown, Toast.LENGTH_LONG).show();
			return;
		}
		if (UpdateDownloader.isBusy()) {
			Toast.makeText(activity, R.string.update_download_busy, Toast.LENGTH_SHORT).show();
			return;
		}
		final AlertDialog wait = new AlertDialog.Builder(activity)
				.setMessage(R.string.update_downloading)
				.setCancelable(false)
				.show();
		UpdateDownloader.downloadAndInstall(activity, href, new UpdateDownloader.Progress() {
			@Override
			public void onProgress(int percent, long received, long total) {
				if (wait.isShowing()) {
					wait.setMessage(activity.getString(R.string.update_downloading_pct, percent));
				}
			}

			@Override
			public void onDone() {
				if (wait.isShowing()) {
					wait.dismiss();
				}
			}

			@Override
			public void onError(String message) {
				if (wait.isShowing()) {
					wait.dismiss();
				}
				Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
			}
		});
	}

	private static void rememberApp(Context context) {
		if (sApp == null && context != null) {
			sApp = context.getApplicationContext();
		}
	}

	private static void tryConnectCheck(boolean delay) {
		if (!sWantConnectCheck || sHaveResult || sConnectTried || sApp == null) {
			return;
		}
		if (sChecking) {
			return;
		}
		if (delay) {
			if (sConnectScheduled) {
				return;
			}
			sConnectScheduled = true;
			new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
				@Override
				public void run() {
					tryConnectCheck(false);
				}
			}, 2500);
			return;
		}
		sConnectTried = true;
		check(sApp, true);
	}

	private static void check(final Context context, final boolean silent) {
		if (sChecking || context == null) {
			return;
		}
		String url = bakedUrl();
		if (url.length() == 0) {
			applyResult(failedInfo(context.getString(R.string.update_check_failed)), silent);
			return;
		}
		sChecking = true;
		notifyListeners(sPending, true);
		final Handler ui = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final Object out = fetch(context);
				ui.post(new Runnable() {
					@Override
					public void run() {
						sChecking = false;
						if (out instanceof Info) {
							applyResult((Info) out, silent);
						} else {
							applyResult(failedInfo(out instanceof String
									? (String) out
									: context.getString(R.string.update_check_failed)), silent);
						}
					}
				});
			}
		}, "update-check").start();
	}

	private static void applyResult(Info info, boolean silent) {
		if (info != null && info.failed && silent) {
			notifyListeners(sPending, false);
			tryConnectCheck(false);
			return;
		}
		sPending = info;
		sHaveResult = info != null && info.checked && !info.failed;
		notifyListeners(sPending, false);
		if (!sHaveResult) {
			tryConnectCheck(false);
		}
	}

	private static Object fetch(Context context) {
		String rawUrl = bakedUrl();
		if (rawUrl.length() == 0) {
			return context.getString(R.string.update_check_failed);
		}
		URI manifest;
		try {
			manifest = httpsUri(rawUrl);
		} catch (Exception e) {
			return context.getString(R.string.update_check_failed);
		}

		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(manifest.toString()).openConnection();
			conn.setInstanceFollowRedirects(true);
			conn.setConnectTimeout(TIMEOUT_MS);
			conn.setReadTimeout(TIMEOUT_MS);
			conn.setRequestProperty("Accept", "application/json");
			conn.setRequestProperty("User-Agent", "OpenConnect-PlusP-Update");
			int code = conn.getResponseCode();
			if (code != 200) {
				Log.w(TAG, "update check HTTP " + code);
				return context.getString(R.string.update_check_failed);
			}
			String body = readLimited(conn.getInputStream());
			if (body == null) {
				return context.getString(R.string.update_check_failed);
			}
			JSONObject json = new JSONObject(body);
			Info info = new Info();
			info.versionCode = json.optInt("versionCode", 0);
			info.versionName = json.optString("versionName", "").trim();
			info.notes = clip(json.optString("notes", "").trim(), 280);
			String page = json.optString("url", "").trim();
			if (page.length() == 0) {
				page = json.optString("download", "").trim();
			}
			if (page.length() > 0) {
				info.pageUrl = httpsUri(page).toString();
			}
			if (info.versionCode <= 0) {
				return context.getString(R.string.update_check_failed);
			}

			int installed = installedCode(context);
			info.checked = true;
			info.failed = false;
			if (info.versionCode <= installed) {
				info.available = false;
				info.message = context.getString(R.string.update_up_to_date);
				return info;
			}
			info.available = true;
			info.message = context.getString(R.string.update_available_title);
			return info;
		} catch (Exception e) {
			Log.w(TAG, "update check failed", e);
			return context.getString(R.string.update_check_failed);
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static String bakedUrl() {
		String url = UpdateDefaults.bakedManifestUrl();
		return url != null ? url.trim() : "";
	}

	private static Info failedInfo(String message) {
		Info info = new Info();
		info.available = false;
		info.checked = true;
		info.failed = true;
		info.message = message != null ? message : "";
		return info;
	}

	private static void notifyListeners(Info info, boolean checking) {
		for (Listener listener : snapshotListeners()) {
			listener.onUpdateState(info, checking);
		}
	}

	private static List<Listener> snapshotListeners() {
		synchronized (sListeners) {
			return new ArrayList<Listener>(sListeners);
		}
	}

	private static int installedCode(Context context) {
		try {
			PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return pi.versionCode;
		} catch (Exception e) {
			return 0;
		}
	}

	private static URI httpsUri(String raw) throws Exception {
		if (raw == null) {
			throw new IllegalArgumentException("empty");
		}
		URI uri = new URI(raw.trim());
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalArgumentException("https only");
		}
		if (uri.getHost() == null || uri.getHost().length() == 0) {
			throw new IllegalArgumentException("host");
		}
		if (uri.getUserInfo() != null) {
			throw new IllegalArgumentException("userinfo");
		}
		if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
			throw new IllegalArgumentException("port");
		}
		return uri;
	}

	private static String clip(String s, int max) {
		if (s == null) {
			return "";
		}
		if (s.length() <= max) {
			return s;
		}
		return s.substring(0, max - 1) + "…";
	}

	private static String readLimited(InputStream in) throws Exception {
		if (in == null) {
			return null;
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[1024];
		int n;
		int total = 0;
		while ((n = in.read(buf)) >= 0) {
			total += n;
			if (total > MAX_BODY) {
				return null;
			}
			bos.write(buf, 0, n);
		}
		in.close();
		return new String(bos.toByteArray(), UTF8);
	}
}
