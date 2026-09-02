/*
 * HTTPS-only version manifest + in-app APK download.
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
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import net.openconnect_vpn.android.R;

public final class UpdateCheck {

	public static final String TAG = "OpenConnect";

	/** Opt-in: when false, no automatic or manual update checks run. */
	public static final String PREF_ENABLED = "update_checks_enabled";
	public static final String PREF_LAST_MS = "update_last_check_ms";
	public static final String PREF_SNOOZE_CODE = "update_snooze_code";

	private static final long MIN_INTERVAL_MS = 24L * 60L * 60L * 1000L;
	private static final int MAX_BODY = 16 * 1024;
	private static final int TIMEOUT_MS = 8000;
	private static final Charset UTF8 = Charset.forName("UTF-8");

	private static final List<Listener> sListeners = new ArrayList<Listener>();
	private static Info sPending = null;
	private static boolean sChecking = false;

	public static final class Info {
		public boolean available;
		public boolean checked;
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
		if (sPending != null) {
			listener.onUpdateState(sPending, sChecking);
		}
	}

	public static void removeListener(Listener listener) {
		synchronized (sListeners) {
			sListeners.remove(listener);
		}
	}

	public static boolean isEnabled(Context context) {
		SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
		return getBoolPref(p, PREF_ENABLED, false);
	}

	public static String manifestUrl(Context context) {
		return resolveUrl(context);
	}

	/** Built-in manifest only — never a user-entered URL. */
	private static String resolveUrl(Context context) {
		if (!isEnabled(context)) {
			return "";
		}
		String url = UpdateDefaults.bakedManifestUrl();
		return url != null ? url.trim() : "";
	}

	public static void maybeCheck(final Activity activity) {
		if (activity == null || !isEnabled(activity) || resolveUrl(activity).length() == 0) {
			return;
		}
		SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(activity);
		long last = getLongPref(p, PREF_LAST_MS, 0);
		if (System.currentTimeMillis() - last < MIN_INTERVAL_MS) {
			if (sPending != null && sPending.available) {
				notifyListeners(sPending, false);
			}
			return;
		}
		check(activity, false, false);
	}

	public static void runNow(Activity activity) {
		if (activity == null) {
			return;
		}
		if (!isEnabled(activity)) {
			Toast.makeText(activity, R.string.update_checks_disabled, Toast.LENGTH_SHORT).show();
			return;
		}
		if (resolveUrl(activity).length() == 0) {
			Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
			return;
		}
		check(activity, true, true);
	}

	public static void checkAfterConnect(final Activity activity) {
		if (activity == null || !isEnabled(activity) || resolveUrl(activity).length() == 0) {
			return;
		}
		if (sPending != null && sPending.available) {
			return;
		}
		new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
			@Override
			public void run() {
				if (!activity.isFinishing() && isEnabled(activity)) {
					check(activity, false, false);
				}
			}
		}, 2500);
	}

	public static void startInstall(final Activity activity) {
		if (activity == null || sPending == null || !sPending.available) {
			return;
		}
		String href = sPending.pageUrl != null ? sPending.pageUrl.trim() : "";
		if (href.length() == 0) {
			Toast.makeText(activity, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
			return;
		}
		UpdateDownloader.downloadAndInstall(activity, href, new UpdateDownloader.Progress() {
			@Override
			public void onProgress(int percent, long received, long total) {
				notifyDownload(activity, percent, false, null);
			}

			@Override
			public void onDone() {
				notifyDownload(activity, 100, false, null);
			}

			@Override
			public void onError(String message) {
				notifyDownload(activity, 0, true, message);
			}
		});
	}

	private static void notifyDownload(Activity activity, int percent, boolean error, String message) {
		for (Listener listener : snapshotListeners()) {
			if (listener instanceof DownloadListener) {
				((DownloadListener) listener).onDownloadProgress(percent, error, message);
			}
		}
	}

	public interface DownloadListener extends Listener {
		void onDownloadProgress(int percent, boolean error, String message);
	}

	private static void check(final Activity activity, final boolean manual, final boolean showDialog) {
		if (sChecking) {
			return;
		}
		sChecking = true;
		notifyListeners(sPending, true);
		final Handler ui = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				final Object out = fetch(activity, manual);
				ui.post(new Runnable() {
					@Override
					public void run() {
						sChecking = false;
						if (activity.isFinishing()) {
							return;
						}
						if (out instanceof String) {
							Info none = noneInfo((String) out, true);
							sPending = none;
							notifyListeners(none, false);
							if (manual) {
								Toast.makeText(activity, (String) out, Toast.LENGTH_SHORT).show();
							}
							return;
						}
						if (out instanceof Info) {
							Info info = (Info) out;
							sPending = info;
							notifyListeners(info, false);
							if (info.available) {
								if (showDialog || manual) {
									showOffer(activity, info, manual);
								}
							} else if (manual) {
								Toast.makeText(activity,
										info.message.length() > 0 ? info.message
												: activity.getString(R.string.update_up_to_date),
										Toast.LENGTH_SHORT).show();
							}
						}
					}
				});
			}
		}, "update-check").start();
	}

	private static Object fetch(Context context, boolean manual) {
		String rawUrl = resolveUrl(context);
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
			PreferenceManager.getDefaultSharedPreferences(context)
					.edit()
					.putLong(PREF_LAST_MS, System.currentTimeMillis())
					.apply();

			int installed = installedCode(context);
			if (info.versionCode <= installed) {
				info.available = false;
				info.checked = true;
				info.message = manual ? context.getString(R.string.update_up_to_date) : "";
				return info;
			}
			SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
			int snooze = getIntPref(p, PREF_SNOOZE_CODE, 0);
			if (!manual && snooze == info.versionCode) {
				info.available = false;
				info.checked = true;
				info.message = "";
				return info;
			}
			info.available = true;
			info.checked = true;
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

	private static Info noneInfo(String message, boolean checked) {
		Info info = new Info();
		info.available = false;
		info.checked = checked;
		info.message = message != null ? message : "";
		return info;
	}

	private static void showOffer(final Activity activity, final Info info, boolean manual) {
		String label = info.versionName.length() > 0 ? info.versionName
				: Integer.toString(info.versionCode);
		String msg = activity.getString(R.string.update_available_message, label);
		if (info.notes.length() > 0) {
			msg = msg + "\n\n" + info.notes;
		}

		AlertDialog.Builder b = new AlertDialog.Builder(activity)
				.setTitle(R.string.update_available_title)
				.setMessage(msg)
				.setNegativeButton(R.string.update_later, new DialogInterface.OnClickListener() {
					@Override
					public void onClick(DialogInterface dialog, int which) {
						PreferenceManager.getDefaultSharedPreferences(activity)
								.edit()
								.putInt(PREF_SNOOZE_CODE, info.versionCode)
								.apply();
					}
				});
		if (info.pageUrl.length() > 0) {
			b.setPositiveButton(R.string.update_install, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					startInstall(activity);
				}
			});
		}
		b.show();
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

	private static boolean getBoolPref(SharedPreferences p, String key, boolean def) {
		try {
			return p.getBoolean(key, def);
		} catch (ClassCastException e) {
			return def;
		}
	}

	private static int getIntPref(SharedPreferences p, String key, int def) {
		try {
			return p.getInt(key, def);
		} catch (ClassCastException e) {
			return def;
		}
	}

	private static long getLongPref(SharedPreferences p, String key, long def) {
		try {
			return p.getLong(key, def);
		} catch (ClassCastException e) {
			return def;
		}
	}
}
