/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.mongodb.connection;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Takes the credentials out of a connection string, so a string pasted from Atlas or a
 * config file ({@code mongodb+srv://app:s3cret@cluster0.x.mongodb.net/?authSource=admin})
 * can be saved without its password: user, password, {@code authMechanism},
 * {@code authSource} and {@code authMechanismProperties} go to the form, the rest of the
 * string stays as it was.
 *
 * <p>Plain string work rather than the driver's parser: a half-typed string must still
 * come apart, and nothing the user wrote other than the credentials may change.
 */
public final class UriCredentials {

	/** What {@link #extract} found; a field is {@code null} when the string did not have it. */
	public record Extracted(String connectionString, String username, String password, String mechanism,
			String authSource, String mechanismProperties) {

		public boolean found() {
			return username != null || password != null || mechanism != null || authSource != null
					|| mechanismProperties != null;
		}
	}

	private UriCredentials() {
	}

	public static Extracted extract(String uri) {
		String text = uri == null ? "" : uri.trim();
		int schemeEnd = text.indexOf("://");
		if (schemeEnd < 0) {
			return new Extracted(text, null, null, null, null, null);
		}
		int authorityStart = schemeEnd + 3;
		int authorityEnd = firstOf(text, authorityStart, '/', '?');
		String authority = text.substring(authorityStart, authorityEnd);
		String rest = text.substring(authorityEnd);

		String username = null;
		String password = null;
		int at = authority.lastIndexOf('@');
		if (at >= 0) {
			String userInfo = authority.substring(0, at);
			authority = authority.substring(at + 1);
			int colon = userInfo.indexOf(':');
			username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
			password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
			if (username != null && username.isEmpty()) {
				username = null;
			}
			if (password != null && password.isEmpty()) {
				password = null;
			}
		}

		String mechanism = null;
		String authSource = null;
		String properties = null;
		int question = rest.indexOf('?');
		if (question >= 0) {
			String path = rest.substring(0, question);
			var kept = new ArrayList<String>();
			for (String option : rest.substring(question + 1).split("[&;]")) {
				if (option.isEmpty()) {
					continue;
				}
				int eq = option.indexOf('=');
				String key = (eq < 0 ? option : option.substring(0, eq)).toLowerCase(Locale.ROOT);
				String value = eq < 0 ? "" : decode(option.substring(eq + 1));
				switch (key) {
					case "authmechanism" -> mechanism = value;
					case "authsource" -> authSource = value;
					case "authmechanismproperties" -> properties = value;
					default -> kept.add(option);
				}
			}
			rest = kept.isEmpty() ? path : path + "?" + String.join("&", kept);
		}
		return new Extracted(text.substring(0, authorityStart) + authority + rest, username, password,
				blankToNull(mechanism), blankToNull(authSource), blankToNull(properties));
	}

	/** The scheme and hosts of a connection string, without credentials, database or options. */
	public static String hosts(String uri) {
		String text = uri == null ? "" : uri.trim();
		int schemeEnd = text.indexOf("://");
		if (schemeEnd < 0) {
			return text;
		}
		int authorityStart = schemeEnd + 3;
		String authority = text.substring(authorityStart, firstOf(text, authorityStart, '/', '?'));
		return text.substring(0, authorityStart) + authority.substring(authority.lastIndexOf('@') + 1);
	}

	/** The host names of a connection string, without ports. */
	public static List<String> hostNames(String uri) {
		String hosts = hosts(uri);
		int schemeEnd = hosts.indexOf("://");
		var names = new ArrayList<String>();
		for (String host : hosts.substring(schemeEnd < 0 ? 0 : schemeEnd + 3).split(",")) {
			String name = host.trim();
			if (name.startsWith("[")) {
				int close = name.indexOf(']');
				name = close < 0 ? name.substring(1) : name.substring(1, close);
			} else if (name.indexOf(':') >= 0) {
				name = name.substring(0, name.indexOf(':'));
			}
			if (!name.isEmpty()) {
				names.add(name);
			}
		}
		return names;
	}

	private static int firstOf(String text, int from, char a, char b) {
		for (int i = from; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == a || c == b) {
				return i;
			}
		}
		return text.length();
	}

	/** Percent-decoding as in a URI: a {@code +} stays a plus. */
	private static String decode(String text) {
		try {
			return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException malformed) {
			return text;
		}
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text;
	}
}
