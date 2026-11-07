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
package dev.nuclr.plugin.core.panel.mongodb.client;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;

/** A driver failure as one readable line or two, for a dialog or an agent. */
public final class ErrorText {

	private static final int MAX = 600;

	private ErrorText() {
	}

	public static String describe(Throwable e) {
		if (e instanceof MongoSecurityException security) {
			Throwable cause = security.getCause();
			String reason = cause instanceof MongoCommandException command ? command.getErrorMessage()
					: cause != null && cause.getMessage() != null ? cause.getMessage() : security.getMessage();
			return clip("Authentication failed: " + reason);
		}
		if (e instanceof MongoTimeoutException) {
			return clip(serversUnreachable(e.getMessage()));
		}
		if (e instanceof MongoCommandException command) {
			return clip(command.getErrorCodeName() + " (" + command.getErrorCode() + "): " + command.getErrorMessage());
		}
		String message = e.getMessage();
		return clip(message == null || message.isBlank() ? e.getClass().getSimpleName() : message);
	}

	/**
	 * A server selection timeout, which the driver words as its whole view of the
	 * cluster, cut down to each server and the innermost exception it got there: the
	 * real reason (unknown host, refused, untrusted certificate) instead of the wrapper.
	 */
	static String serversUnreachable(String message) {
		String text = message == null ? "" : message;
		var reasons = new ArrayList<String>();
		Matcher server = SERVER.matcher(text);
		var starts = new ArrayList<Integer>();
		var addresses = new ArrayList<String>();
		while (server.find()) {
			starts.add(server.start());
			addresses.add(server.group(1));
		}
		for (int i = 0; i < starts.size(); i++) {
			String segment = text.substring(starts.get(i), i + 1 < starts.size() ? starts.get(i + 1) : text.length());
			Matcher exception = EXCEPTION.matcher(segment);
			String last = null;
			while (exception.find()) {
				String found = exception.group(1);
				int colon = found.indexOf(':');
				String type = colon < 0 ? found : found.substring(0, colon);
				last = type.substring(type.lastIndexOf('.') + 1) + (colon < 0 ? "" : found.substring(colon));
			}
			reasons.add(addresses.get(i) + ": " + (last == null ? "no answer yet" : last.strip()) + hint(last));
		}
		if (reasons.isEmpty()) {
			return "No server could be reached: " + text;
		}
		return "No server could be reached. " + String.join("; ", reasons);
	}

	private static String hint(String reason) {
		if (reason == null) {
			return "";
		}
		if (reason.contains("certification path") || reason.contains("PKIX")) {
			return " (the server's certificate is not trusted: set its CA certificate on the TLS tab)";
		}
		if (reason.startsWith("UnknownHostException")) {
			return " (unknown host: check the name, or connect through the SSH tunnel that can see it)";
		}
		return "";
	}

	private static final Pattern SERVER = Pattern.compile("\\{address=([^,}]+)");
	/** {@code {pkg.SomeException: message}} with no nested braces. */
	private static final Pattern EXCEPTION = Pattern.compile("\\{([\\w.$]+(?:Exception|Error)(?::[^{}]*)?)\\}");

	/** Whether the server refused the credentials, so a stored secret may be wrong. */
	public static boolean isAuthenticationFailure(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof MongoSecurityException) {
				return true;
			}
		}
		return false;
	}

	private static String clip(String text) {
		return text.length() <= MAX ? text : text.substring(0, MAX) + "...";
	}
}
