package net.openconnect_vpn.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import net.openconnect_vpn.android.core.ThemeManager;

public class ThemedActivity extends Activity {

	@Override
	protected void attachBaseContext(Context newBase) {
		super.attachBaseContext(ThemeManager.wrap(newBase));
	}

	@Override
	public void applyOverrideConfiguration(Configuration overrideConfiguration) {
		if (overrideConfiguration != null) {
			ThemeManager.applyTo(overrideConfiguration, this);
		}
		super.applyOverrideConfiguration(overrideConfiguration);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ThemeManager.applySystemBars(this);
	}

	@Override
	protected void onResume() {
		super.onResume();
		ThemeManager.applySystemBars(this);
	}
}
