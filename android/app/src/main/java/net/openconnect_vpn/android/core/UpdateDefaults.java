/*
 * Built-in update manifest URL. Not stored as plaintext.
 * ./build.sh XOR-bakes the real URL into ENC (key 0xA5) before release builds.
 * BuildConfig.BUILT_IN_UPDATE_URL may override when set via OCP_UPDATE_URL.
 */

package net.openconnect_vpn.android.core;

import net.openconnect_vpn.android.BuildConfig;

public final class UpdateDefaults {

	private static final byte KEY = (byte) 0xA5;
	private static final byte[] ENC = {
			(byte) 205, (byte) 209, (byte) 209, (byte) 213, (byte) 214, (byte) 159, (byte) 138, (byte) 138,
			(byte) 215, (byte) 196, (byte) 210, (byte) 139, (byte) 194, (byte) 204, (byte) 209, (byte) 205,
			(byte) 208, (byte) 199, (byte) 208, (byte) 214, (byte) 192, (byte) 215, (byte) 198, (byte) 202,
			(byte) 203, (byte) 209, (byte) 192, (byte) 203, (byte) 209, (byte) 139, (byte) 198, (byte) 202,
			(byte) 200, (byte) 138, (byte) 213, (byte) 215, (byte) 204, (byte) 211, (byte) 196, (byte) 209,
			(byte) 192, (byte) 149, (byte) 214, (byte) 202, (byte) 195, (byte) 209, (byte) 138, (byte) 234,
			(byte) 230, (byte) 245, (byte) 198, (byte) 201, (byte) 204, (byte) 192, (byte) 203, (byte) 209,
			(byte) 138, (byte) 215, (byte) 192, (byte) 195, (byte) 214, (byte) 138, (byte) 205, (byte) 192,
			(byte) 196, (byte) 193, (byte) 214, (byte) 138, (byte) 200, (byte) 196, (byte) 204, (byte) 203,
			(byte) 138, (byte) 196, (byte) 203, (byte) 193, (byte) 215, (byte) 202, (byte) 204, (byte) 193,
			(byte) 138, (byte) 196, (byte) 213, (byte) 139, (byte) 207, (byte) 214, (byte) 202, (byte) 203
	};

	private UpdateDefaults() {
	}

	public static String bakedManifestUrl() {
		String fromBuild = BuildConfig.BUILT_IN_UPDATE_URL;
		if (fromBuild != null && fromBuild.trim().length() > 0) {
			return fromBuild.trim();
		}
		return decode(ENC);
	}

	private static String decode(byte[] enc) {
		byte[] out = new byte[enc.length];
		for (int i = 0; i < enc.length; i++) {
			out[i] = (byte) (enc[i] ^ KEY);
		}
		return new String(out);
	}
}
