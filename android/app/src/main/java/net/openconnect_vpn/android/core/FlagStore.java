/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Download and cache circular-cropped country flag PNGs.
 */

package net.openconnect_vpn.android.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.util.Log;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import net.openconnect_vpn.android.R;

public final class FlagStore {

	public static final String TAG = "OpenConnect";

	private static final HashMap<String, Bitmap> CACHE = new HashMap<String, Bitmap>();

	private FlagStore() {
	}

	public static File fileFor(Context context, String iso) {
		iso = normalize(iso);
		return new File(new File(context.getFilesDir(), "flags"), iso + ".png");
	}

	public static boolean ensure(Context context, String iso) {
		iso = normalize(iso);
		if (iso.length() != 2) {
			return false;
		}
		File out = fileFor(context, iso);
		if (out.isFile() && out.length() > 0) {
			return true;
		}
		File dir = out.getParentFile();
		if (dir != null && !dir.exists()) {
			dir.mkdirs();
		}
		HttpURLConnection conn = null;
		try {
			URL url = new URL("https://flagcdn.com/w320/" + iso + ".png");
			conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(8000);
			conn.setReadTimeout(8000);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("User-Agent", "Mozilla/5.0");
			if (conn.getResponseCode() != 200) {
				Log.w(TAG, "flag download HTTP " + conn.getResponseCode() + " for " + iso);
				return false;
			}
			InputStream in = conn.getInputStream();
			File tmp = new File(out.getAbsolutePath() + ".tmp");
			FileOutputStream fos = new FileOutputStream(tmp);
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) >= 0) {
				fos.write(buf, 0, n);
			}
			fos.close();
			in.close();
			if (!tmp.renameTo(out)) {
				out.delete();
				tmp.renameTo(out);
			}
			synchronized (CACHE) {
				CACHE.remove(iso);
			}
			return out.isFile();
		} catch (Exception e) {
			Log.w(TAG, "flag download failed for " + iso, e);
			return false;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	public static Bitmap bitmap(Context context, String iso) {
		iso = normalize(iso);
		if (iso.length() != 2) {
			return null;
		}
		synchronized (CACHE) {
			Bitmap cached = CACHE.get(iso);
			if (cached != null && !cached.isRecycled()) {
				return cached;
			}
		}
		File f = fileFor(context, iso);
		if (!f.isFile()) {
			return null;
		}
		Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
		if (bmp != null) {
			synchronized (CACHE) {
				CACHE.put(iso, bmp);
			}
		}
		return bmp;
	}

	public static boolean bind(ImageView view, String iso) {
		if (view == null) {
			return false;
		}
		Bitmap bmp = bitmap(view.getContext(), iso);
		if (bmp == null) {
			view.setImageBitmap(null);
			view.setVisibility(View.GONE);
			return false;
		}
		if (view.getTag(R.id.tag_flag_clipped) == null) {
			clipCircle(view);
			view.setTag(R.id.tag_flag_clipped, Boolean.TRUE);
		}
		view.setImageBitmap(bmp);
		view.setVisibility(View.VISIBLE);
		return true;
	}

	public static void clipCircle(final View view) {
		view.setClipToOutline(true);
		view.setOutlineProvider(new ViewOutlineProvider() {
			@Override
			public void getOutline(View v, Outline outline) {
				int w = v.getWidth();
				int h = v.getHeight();
				if (w <= 0 || h <= 0) {
					outline.setEmpty();
					return;
				}
				outline.setOval(0, 0, w, h);
			}
		});
	}

	private static String normalize(String iso) {
		if (iso == null) {
			return "";
		}
		return iso.trim().toLowerCase(Locale.US);
	}
}
