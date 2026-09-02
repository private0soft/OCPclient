package net.openconnect_vpn.android.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.preference.PreferenceManager;
import android.view.View;

public final class ThemeManager {

	public static final String PREF_THEME = "app_theme";
	public static final String MODE_SYSTEM = "system";
	public static final String MODE_LIGHT = "light";
	public static final String MODE_DARK = "dark";

	private ThemeManager() {
	}

	/** Force night/day on a Configuration so values / values-night stay in sync with widgets. */
	public static void applyTo(Configuration config, Context prefsContext) {
		if (config == null) {
			return;
		}
		String mode = getMode(prefsContext);
		int night;
		if (MODE_DARK.equals(mode)) {
			night = Configuration.UI_MODE_NIGHT_YES;
		} else if (MODE_LIGHT.equals(mode)) {
			night = Configuration.UI_MODE_NIGHT_NO;
		} else {
			return;
		}
		config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
	}

	public static Context wrap(Context context) {
		if (context == null) {
			return context;
		}
		String mode = getMode(context);
		if (MODE_SYSTEM.equals(mode)) {
			return context;
		}
		Configuration config = new Configuration(context.getResources().getConfiguration());
		applyTo(config, context);
		return context.createConfigurationContext(config);
	}

	public static String getMode(Context context) {
		if (context == null) {
			return MODE_SYSTEM;
		}
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
		try {
			String mode = sp.getString(PREF_THEME, MODE_SYSTEM);
			return mode != null ? mode : MODE_SYSTEM;
		} catch (ClassCastException e) {
			return MODE_SYSTEM;
		}
	}

	public static boolean isDark(Context context) {
		int night = context.getResources().getConfiguration().uiMode
				& Configuration.UI_MODE_NIGHT_MASK;
		return night == Configuration.UI_MODE_NIGHT_YES;
	}

	public static void applyThemeChange(Activity activity) {
		if (activity == null) {
			return;
		}
		Intent intent = new Intent(activity, net.openconnect_vpn.android.MainActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
				| Intent.FLAG_ACTIVITY_CLEAR_TASK
				| Intent.FLAG_ACTIVITY_CLEAR_TOP);
		activity.startActivity(intent);
		activity.finish();
		activity.overridePendingTransition(0, 0);
	}

	public static void applySystemBars(Activity activity) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
			return;
		}
		boolean light = !isDark(activity);
		View decor = activity.getWindow().getDecorView();
		int flags = decor.getSystemUiVisibility();
		if (light) {
			flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
		} else {
			flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			if (light) {
				flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
			} else {
				flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
			}
		}
		decor.setSystemUiVisibility(flags);
	}
}
