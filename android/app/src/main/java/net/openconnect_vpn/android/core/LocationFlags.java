/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Resolve a VPN profile name / hostname to an ISO country flag emoji.
 */

package net.openconnect_vpn.android.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.openconnect_vpn.android.VpnProfile;

public final class LocationFlags {

	private static final Map<String, String> ALIAS = new HashMap<String, String>();
	private static final Pattern TOKEN = Pattern.compile("[a-z]{2,}");

	static {
		put("us", "usa", "united states", "america", "آمریکا", "امریکا");
		put("gb", "uk", "united kingdom", "britain", "england", "london", "انگلیس", "بریتانیا");
		put("de", "germany", "deutschland", "frankfurt", "berlin", "آلمان");
		put("nl", "netherlands", "holland", "amsterdam", "هلند");
		put("fr", "france", "paris", "فرانسه");
		put("tr", "turkey", "turkiye", "istanbul", "ترکیه");
		put("ir", "iran", "tehran", "ایران", "تهران");
		put("ae", "uae", "dubai", "emirates", "امارات", "دبی");
		put("sg", "singapore", "سنگاپور");
		put("jp", "japan", "tokyo", "ژاپن", "توکیو");
		put("au", "australia", "sydney", "استرالیا");
		put("ca", "canada", "toronto", "کانادا");
		put("it", "italy", "milan", "rome", "ایتالیا");
		put("es", "spain", "madrid", "اسپانیا");
		put("se", "sweden", "stockholm", "سوئد");
		put("ch", "switzerland", "zurich", "سوئیس");
		put("at", "austria", "vienna", "اتریش");
		put("pl", "poland", "warsaw", "لهستان");
		put("ro", "romania", "bucharest", "رومانی");
		put("bg", "bulgaria", "sofia", "بلغارستان");
		put("fi", "finland", "helsinki", "فنلاند");
		put("no", "norway", "oslo", "نروژ");
		put("dk", "denmark", "copenhagen", "دانمارک");
		put("be", "belgium", "brussels", "بلژیک");
		put("ie", "ireland", "dublin", "ایرلند");
		put("pt", "portugal", "lisbon", "پرتغال");
		put("cz", "czechia", "czech", "prague", "چک");
		put("hu", "hungary", "budapest", "مجارستان");
		put("gr", "greece", "athens", "یونان");
		put("in", "india", "mumbai", "هند");
		put("hk", "hongkong", "hong kong", "هنگ کنگ");
		put("kr", "korea", "seoul", "کره");
		put("tw", "taiwan", "taipei", "تایوان");
		put("br", "brazil", "sao paulo", "برزیل");
		put("mx", "mexico", "مکزیک");
		put("za", "southafrica", "south africa", "آفریقای جنوبی");
		put("ru", "russia", "moscow", "روسیه");
		put("ua", "ukraine", "kyiv", "اوکراین");
		put("il", "israel", "telaviv", "اسرائیل");
		put("sa", "saudi", "riyadh", "عربستان");
		put("qa", "qatar", "doha", "قطر");
		put("cy", "cyprus", "قبرس");
		put("lv", "latvia", "لتونی");
		put("lt", "lithuania", "لیتوانی");
		put("ee", "estonia", "استونی");
		put("sk", "slovakia", "اسلواکی");
		put("si", "slovenia", "اسلوونی");
		put("hr", "croatia", "کرواسی");
		put("rs", "serbia", "صربستان");
		put("md", "moldova", "مولداوی");
		put("ge", "georgia", "گرجستان");
		put("am", "armenia", "ارمنستان");
		put("az", "azerbaijan", "آذربایجان");
		put("kz", "kazakhstan", "قزاقستان");
		put("nz", "newzealand", "new zealand", "نیوزیلند");
		put("my", "malaysia", "مالزی");
		put("th", "thailand", "تایلند");
		put("ph", "philippines", "فیلیپین");
		put("id", "indonesia", "اندونزی");
		put("vn", "vietnam", "ویتنام");
		put("cn", "china", "چین");
		put("ar", "argentina", "آرژانتین");
		put("cl", "chile", "شیلی");
		put("co", "colombia", "کلمبیا");
		put("eg", "egypt", "مصر");
		put("ng", "nigeria", "نیجریه");
		put("ke", "kenya", "کنیا");
		put("pk", "pakistan", "پاکستان");
		put("bd", "bangladesh", "بنگلادش");
		put("lu", "luxembourg", "لوکزامبورگ");
		put("is", "iceland", "ایسلند");
		put("mt", "malta", "مالت");
		put("al", "albania", "آلبانی");
		put("ba", "bosnia", "بوسنی");
		put("mk", "macedonia", "مقدونیه");
	}

	private LocationFlags() {
	}

	public static String emojiFor(VpnProfile profile) {
		if (profile == null) {
			return "";
		}
		String flag = emojiForText(profile.getName());
		if (flag.length() == 0 && profile.mPrefs != null) {
			String host;
			try {
				host = profile.mPrefs.getString("server_address", "");
			} catch (ClassCastException e) {
				host = "";
			}
			flag = emojiForText(host);
		}
		return flag;
	}

	public static String emojiForText(String text) {
		String iso = isoFromText(text);
		return iso == null ? "" : toEmoji(iso);
	}

	private static void put(String iso, String... aliases) {
		ALIAS.put(iso, iso);
		for (String alias : aliases) {
			ALIAS.put(normalize(alias), iso);
		}
	}

	private static String isoFromText(String raw) {
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		String n = normalize(raw);
		if (ALIAS.containsKey(n)) {
			return ALIAS.get(n);
		}
		Matcher m = TOKEN.matcher(n);
		while (m.find()) {
			String token = m.group();
			if (ALIAS.containsKey(token)) {
				return ALIAS.get(token);
			}
		}
		return null;
	}

	private static String normalize(String s) {
		return s.toLowerCase(Locale.US)
				.replace('ı', 'i')
				.replace('-', ' ')
				.replace('_', ' ')
				.replace('.', ' ')
				.trim();
	}

	private static String toEmoji(String iso) {
		iso = iso.toUpperCase(Locale.US);
		if (iso.length() != 2) {
			return "";
		}
		int a = 0x1F1E6 + (iso.charAt(0) - 'A');
		int b = 0x1F1E6 + (iso.charAt(1) - 'A');
		return new String(Character.toChars(a)) + new String(Character.toChars(b));
	}
}
