/*
 * Download an APK over HTTPS and launch the system package installer.
 */

package net.openconnect_vpn.android.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import net.openconnect_vpn.android.R;

public final class UpdateDownloader {

	public static final String TAG = "OpenConnect";

	private static final long MAX_APK = 250L * 1024L * 1024L;
	private static final int TIMEOUT_CONNECT_MS = 20000;
	private static final int TIMEOUT_READ_MS = 60000;

	public interface Progress {
		void onProgress(int percent, long received, long total);

		void onDone();

		void onError(String message);
	}

	private static volatile boolean sBusy = false;

	private UpdateDownloader() {
	}

	public static boolean isBusy() {
		return sBusy;
	}

	public static void downloadAndInstall(final Activity activity, final String url, final Progress progress) {
		if (activity == null || url == null || url.trim().length() == 0) {
			return;
		}
		if (sBusy) {
			notifyError(progress, activity.getString(R.string.update_download_busy));
			return;
		}
		sBusy = true;
		final Handler ui = new Handler(Looper.getMainLooper());
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					File apk = download(activity, url.trim(), progress, ui);
					ui.post(new Runnable() {
						@Override
						public void run() {
							sBusy = false;
							if (activity.isFinishing()) {
								return;
							}
							if (launchInstall(activity, apk)) {
								if (progress != null) {
									progress.onDone();
								}
							} else if (progress != null) {
								progress.onError(activity.getString(R.string.update_install_failed));
							}
						}
					});
				} catch (final Exception e) {
					Log.w(TAG, "update download failed", e);
					ui.post(new Runnable() {
						@Override
						public void run() {
							sBusy = false;
							String msg = activity.getString(R.string.update_download_failed);
							if (progress != null) {
								progress.onError(msg);
							} else {
								Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
							}
						}
					});
				}
			}
		}, "update-download").start();
	}

	private static File download(Activity activity, String url, final Progress progress, Handler ui)
			throws Exception {
		if (!url.toLowerCase().startsWith("https://")) {
			throw new IllegalArgumentException("https only");
		}
		HttpURLConnection conn = null;
		InputStream in = null;
		FileOutputStream out = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setInstanceFollowRedirects(true);
			conn.setConnectTimeout(TIMEOUT_CONNECT_MS);
			conn.setReadTimeout(TIMEOUT_READ_MS);
			conn.setRequestProperty("User-Agent", "OpenConnect-PlusP-Update");
			int code = conn.getResponseCode();
			if (code != 200) {
				throw new IllegalStateException("HTTP " + code);
			}
			long total = conn.getContentLengthLong();
			if (total > MAX_APK) {
				throw new IllegalStateException("too large");
			}
			File dir = new File(activity.getCacheDir(), "updates");
			if (!dir.exists() && !dir.mkdirs()) {
				throw new IllegalStateException("mkdir");
			}
			File tmp = new File(dir, "OpenConnect-P-update.apk.tmp");
			File dest = new File(dir, "OpenConnect-P-update.apk");
			out = new FileOutputStream(tmp);
			in = conn.getInputStream();
			byte[] buf = new byte[64 * 1024];
			long received = 0;
			long lastEmit = 0;
			int n;
			while ((n = in.read(buf)) >= 0) {
				received += n;
				if (received > MAX_APK) {
					throw new IllegalStateException("too large");
				}
				out.write(buf, 0, n);
				if (received - lastEmit >= 256 * 1024) {
					emitProgress(progress, ui, received, total);
					lastEmit = received;
				}
			}
			out.flush();
			out.close();
			out = null;
			in.close();
			in = null;
			if (!isZipApk(tmp)) {
				tmp.delete();
				throw new IllegalStateException("not apk");
			}
			if (dest.exists() && !dest.delete()) {
				tmp.delete();
				throw new IllegalStateException("replace");
			}
			if (!tmp.renameTo(dest)) {
				tmp.delete();
				throw new IllegalStateException("rename");
			}
			emitProgress(progress, ui, received, total > 0 ? total : received);
			return dest;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception ignored) {
				}
			}
			if (out != null) {
				try {
					out.close();
				} catch (Exception ignored) {
				}
			}
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	private static void emitProgress(final Progress progress, Handler ui, final long received, final long total) {
		if (progress == null) {
			return;
		}
		final int percent = total > 0 ? (int) Math.min(100, (received * 100L) / total) : 0;
		ui.post(new Runnable() {
			@Override
			public void run() {
				progress.onProgress(percent, received, total);
			}
		});
	}

	private static boolean isZipApk(File file) {
		byte[] magic = new byte[2];
		java.io.FileInputStream in = null;
		try {
			in = new java.io.FileInputStream(file);
			if (in.read(magic) != 2) {
				return false;
			}
			return magic[0] == 'P' && magic[1] == 'K';
		} catch (Exception e) {
			return false;
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	private static boolean launchInstall(Activity activity, File apk) {
		try {
			Uri uri;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				uri = FileProvider.getUriForFile(activity,
						activity.getPackageName() + ".apkfileprovider", apk);
			} else {
				uri = Uri.fromFile(apk);
			}
			Intent intent = new Intent(Intent.ACTION_VIEW);
			intent.setDataAndType(uri, "application/vnd.android.package-archive");
			intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
			intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
			activity.startActivity(intent);
			return true;
		} catch (Exception e) {
			Log.w(TAG, "install intent failed", e);
			return false;
		}
	}

	private static void notifyError(Progress progress, String message) {
		if (progress != null) {
			progress.onError(message);
		}
	}
}
