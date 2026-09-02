/*
 * Copyright (c) 2026 MyOCApp contributors
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Home-screen widget: tap to connect the last profile or disconnect.
 */

package net.openconnect_vpn.android;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.view.View;
import android.widget.RemoteViews;
import net.openconnect_vpn.android.core.FlagStore;
import net.openconnect_vpn.android.core.GeoLookup;
import net.openconnect_vpn.android.core.OpenConnectManagementThread;
import net.openconnect_vpn.android.core.VpnQuick;

public class VpnWidget extends AppWidgetProvider {

	private static final int COLOR_ON = 0xFF2EE6A6;
	private static final int COLOR_TEXT = 0xFFF4F7FB;

	@Override
	public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
		RemoteViews views = build(context);
		for (int i = 0; i < ids.length; i++) {
			manager.updateAppWidget(ids[i], views);
		}
	}

	public static void refresh(Context context) {
		Context app = context.getApplicationContext();
		AppWidgetManager manager = AppWidgetManager.getInstance(app);
		int[] ids = manager.getAppWidgetIds(new ComponentName(app, VpnWidget.class));
		if (ids == null || ids.length == 0) {
			requestTileListen(app);
			return;
		}
		RemoteViews views = build(app);
		for (int i = 0; i < ids.length; i++) {
			manager.updateAppWidget(ids[i], views);
		}
		requestTileListen(app);
	}

	private static void requestTileListen(Context context) {
		if (Build.VERSION.SDK_INT < 24) {
			return;
		}
		try {
			TileService.requestListeningState(context,
					new ComponentName(context, QSTileService.class));
		} catch (Exception ignored) {
		}
	}

	private static RemoteViews build(Context context) {
		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_vpn);
		int state = VpnQuick.connectionState(context);
		VpnProfile profile = VpnQuick.lastProfile(context);
		String name = profile != null ? profile.getName() : "";
		String country = "";
		String iso = "";
		if (profile != null) {
			country = GeoLookup.prefString(profile.mPrefs, GeoLookup.PREF_COUNTRY);
			iso = GeoLookup.isoOf(profile.mPrefs);
		}

		int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
		PendingIntent tap = PendingIntent.getActivity(context, 1, VpnQuick.toggleIntent(context), piFlags);
		views.setOnClickPendingIntent(R.id.widget_root, tap);

		int card;
		int ring;
		int face;
		int icon;
		String statusText;
		int statusColor;
		String subtitle;

		if (state == OpenConnectManagementThread.STATE_CONNECTED) {
			card = R.drawable.bg_widget_card_on;
			ring = R.drawable.bg_widget_ring_on;
			face = R.drawable.bg_widget_on;
			icon = R.drawable.ic_widget_vpn_busy;
			statusText = context.getString(R.string.widget_connected);
			statusColor = COLOR_ON;
			subtitle = firstNonEmpty(country, name);
		} else if (state != OpenConnectManagementThread.STATE_DISCONNECTED && state != 0) {
			card = R.drawable.bg_widget_card_busy;
			ring = R.drawable.bg_widget_ring_busy;
			face = R.drawable.bg_widget_busy;
			icon = R.drawable.ic_widget_vpn_busy;
			statusText = context.getString(R.string.widget_connecting);
			statusColor = COLOR_TEXT;
			subtitle = firstNonEmpty(name, country);
		} else {
			card = R.drawable.bg_widget_card_off;
			ring = R.drawable.bg_widget_ring_off;
			face = R.drawable.bg_widget_off;
			icon = R.drawable.ic_widget_vpn_off;
			statusText = context.getString(R.string.widget_connect);
			statusColor = COLOR_TEXT;
			subtitle = firstNonEmpty(name, country);
		}

		views.setInt(R.id.widget_root, "setBackgroundResource", card);
		views.setInt(R.id.widget_ring, "setBackgroundResource", ring);
		views.setInt(R.id.widget_face, "setBackgroundResource", face);
		views.setImageViewResource(R.id.widget_icon, icon);
		views.setTextViewText(R.id.widget_status, statusText);
		views.setTextColor(R.id.widget_status, statusColor);
		views.setTextViewText(R.id.widget_name, subtitle);
		views.setViewVisibility(R.id.widget_name,
				subtitle.length() > 0 ? View.VISIBLE : View.GONE);
		views.setContentDescription(R.id.widget_root, statusText);

		Bitmap flag = circleCrop(FlagStore.bitmap(context, iso),
				Math.round(48f * context.getResources().getDisplayMetrics().density));
		if (flag != null) {
			views.setImageViewBitmap(R.id.widget_flag, flag);
			views.setViewVisibility(R.id.widget_flag, View.VISIBLE);
			views.setViewVisibility(R.id.widget_icon, View.GONE);
		} else {
			views.setViewVisibility(R.id.widget_flag, View.INVISIBLE);
			views.setViewVisibility(R.id.widget_icon, View.VISIBLE);
		}
		return views;
	}

	private static String firstNonEmpty(String a, String b) {
		if (a != null && a.length() > 0) {
			return a;
		}
		return b != null ? b : "";
	}

	private static Bitmap circleCrop(Bitmap src, int size) {
		if (src == null || src.isRecycled() || size <= 0) {
			return null;
		}
		if (size > 128) {
			size = 128;
		}
		try {
			Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(out);
			float scale = Math.max((float) size / src.getWidth(), (float) size / src.getHeight());
			Matrix matrix = new Matrix();
			matrix.setScale(scale, scale);
			matrix.postTranslate(
					(size - src.getWidth() * scale) * 0.5f,
					(size - src.getHeight() * scale) * 0.5f);
			Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
			BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
			shader.setLocalMatrix(matrix);
			paint.setShader(shader);
			canvas.drawOval(0f, 0f, size, size, paint);
			return out;
		} catch (OutOfMemoryError e) {
			return null;
		}
	}
}
