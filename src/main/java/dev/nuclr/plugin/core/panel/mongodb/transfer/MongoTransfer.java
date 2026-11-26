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
package dev.nuclr.plugin.core.panel.mongodb.transfer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.nuclr.plugin.core.panel.mongodb.connection.AuthType;
import dev.nuclr.plugin.core.panel.mongodb.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.CredentialStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnectionProfile;
import dev.nuclr.plugin.core.panel.mongodb.connection.ProxyType;
import dev.nuclr.plugin.core.panel.mongodb.connection.UriCredentials;
import lombok.extern.slf4j.Slf4j;

/**
 * This plugin's side of Commander's Configs - Export / Import, spoken over {@code act}
 * with plain maps (Commander's {@code TransferProtocol}; see
 * {@code commander/docs/settings-transfer-plan.md}, section 4). Saved connections are the
 * {@code connections} category.
 *
 * <p>Secrets never travel inside an ordinary field: a snapshot takes the credentials out of
 * the connection string (with {@link UriCredentials}) and drops an {@code AWS_SESSION_TOKEN}
 * from the mechanism properties; the password, client certificate password and SSH / proxy
 * password are only read on request. Apply strips both again - a crafted file cannot put a
 * password or token back into {@code settings.json}. Apply never writes the keychain; a
 * password it takes out of a connection string goes back to Commander as a recovered
 * secret, which Commander stores under the new id. A session token is never recovered: it
 * expires within hours.
 *
 * <p>Called off the EDT, on the instance Commander keeps for actions.
 */
@Slf4j
public final class MongoTransfer {

	public static final String Describe = "transfer.describe";
	public static final String Snapshot = "transfer.snapshot";
	public static final String ReadSecrets = "transfer.readSecrets";
	public static final String FindEmbeddedSecrets = "transfer.findEmbeddedSecrets";
	public static final String Apply = "transfer.apply";
	public static final String Remove = "transfer.remove";

	static final int ProtocolVersion = 1;
	static final String Type = "connections";
	static final int SectionVersion = 1;

	/** The short-lived AWS token a mechanism property may carry; never exported or imported. */
	static final String SessionTokenKey = "AWS_SESSION_TOKEN";

	// Field names, as they appear in the export file.
	static final String Name = "name";
	static final String ConnectionString = "connectionString";
	static final String AuthTypeField = "authType";
	static final String Username = "username";
	static final String AuthSource = "authSource";
	static final String AuthMechanismProperties = "authMechanismProperties";
	static final String Tls = "tls";
	static final String TlsCaFile = "tlsCaFile";
	static final String TlsCertificateFile = "tlsCertificateFile";
	static final String TlsAllowInvalidHostnames = "tlsAllowInvalidHostnames";
	static final String TlsAllowInvalidCertificates = "tlsAllowInvalidCertificates";
	static final String ProxyTypeField = "proxyType";
	static final String ProxyHost = "proxyHost";
	static final String ProxyPort = "proxyPort";
	static final String ProxyUsername = "proxyUsername";
	static final String SshKeyPath = "sshKeyPath";
	static final String Password = "password";
	static final String TlsPassword = "tlsPassword";
	static final String ProxyPassword = "proxyPassword";

	private final ConnectionProfileStore profiles;
	private final CredentialStore credentials;

	/** The rows of the last snapshot by row id, so an export reads secrets for exactly those. */
	private Map<String, MongoConnectionProfile> lastSnapshot = Map.of();

	public MongoTransfer(ConnectionProfileStore profiles, CredentialStore credentials) {
		this.profiles = profiles;
		this.credentials = credentials;
	}

	public static boolean handles(String action) {
		return switch (action) {
			case Describe, Snapshot, ReadSecrets, FindEmbeddedSecrets, Apply, Remove -> true;
			default -> false;
		};
	}

	/**
	 * Answer one transfer action into {@code data}. Throws on failure; Commander treats a
	 * throw as "the plugin failed" and, for apply, relies on the store being unchanged.
	 */
	public void handle(String action, Map<String, Object> data) {
		if (data == null || !(data.get("transfer.protocol") instanceof Integer version) || version != ProtocolVersion) {
			return; // a protocol this build does not speak: do not take part
		}
		if (!Describe.equals(action) && !Type.equals(data.get("type"))) {
			return;
		}
		switch (action) {
			case Describe -> data.put("transfer.providers", List.of(describe()));
			case Snapshot -> data.put("rows", snapshot());
			case ReadSecrets -> data.put("secrets", readSecrets(String.valueOf(data.get("rowId"))));
			case FindEmbeddedSecrets -> data.put("fieldNames", findEmbeddedSecrets(strings(data.get("fields"))));
			case Apply -> {
				Map<String, Object> recovered = apply(items(data.get("items")));
				if (!recovered.isEmpty()) {
					data.put("recoveredSecrets", recovered);
				}
			}
			case Remove -> remove(ids(data.get("ids")));
			default -> {
			}
		}
	}

	static Map<String, Object> describe() {
		var fields = new ArrayList<Map<String, Object>>();
		fields.add(field(Name, "PLAIN"));
		fields.add(field(ConnectionString, "ROUTING"));
		fields.add(field(AuthTypeField, "PLAIN"));
		fields.add(field(Username, "PLAIN"));
		fields.add(field(AuthSource, "PLAIN"));
		// SERVICE_HOST, ENVIRONMENT, TOKEN_RESOURCE...: where a ticket or token is fetched for.
		fields.add(field(AuthMechanismProperties, "ROUTING"));
		fields.add(field(Tls, "PLAIN"));
		fields.add(field(TlsCaFile, "PATH"));
		fields.add(field(TlsCertificateFile, "PRIVATE_KEY_PATH"));
		// Turning certificate checks off lets another server receive the credentials.
		fields.add(field(TlsAllowInvalidHostnames, "ROUTING"));
		fields.add(field(TlsAllowInvalidCertificates, "ROUTING"));
		fields.add(field(ProxyTypeField, "ROUTING"));
		fields.add(field(ProxyHost, "ROUTING"));
		fields.add(field(ProxyPort, "ROUTING"));
		fields.add(field(ProxyUsername, "PLAIN"));
		fields.add(field(SshKeyPath, "PRIVATE_KEY_PATH"));
		fields.add(secret(Password, ""));
		fields.add(secret(TlsPassword, ":tls"));
		fields.add(secret(ProxyPassword, ":proxy"));
		return Map.of("type", Type, "version", SectionVersion, "title", "MongoDB", "fields", fields);
	}

	List<Map<String, Object>> snapshot() {
		var rows = new ArrayList<Map<String, Object>>();
		var byRow = new HashMap<String, MongoConnectionProfile>();
		int index = 0;
		for (MongoConnectionProfile profile : profiles.loadAll()) {
			index++;
			// An entry saved without an id keeps a row of its own; it just has no stored secrets.
			String rowId = !isBlank(profile.getId()) ? profile.getId() : "#" + index;
			byRow.put(rowId, profile);
			var row = new HashMap<String, Object>();
			row.put("rowId", rowId);
			row.put("label", isBlank(profile.getName()) ? rowId : profile.getName());
			row.put("fields", fields(profile));
			row.put("hasSecrets", !secrets(profile).isEmpty());
			rows.add(row);
		}
		lastSnapshot = byRow;
		return rows;
	}

	Map<String, String> readSecrets(String rowId) {
		MongoConnectionProfile profile = lastSnapshot.get(rowId);
		if (profile == null) {
			profile = profiles.loadAll().stream().filter(p -> Objects.equals(p.getId(), rowId)).findFirst()
					.orElse(null);
		}
		return profile == null ? Map.of() : secrets(profile);
	}

	/** The fields whose value still carries a URI password or an AWS session token. */
	static List<String> findEmbeddedSecrets(Map<String, String> fields) {
		var found = new ArrayList<String>();
		for (String name : fieldNames()) {
			String value = fields.get(name);
			if (value == null) {
				continue;
			}
			boolean embedded = hasSessionToken(value);
			if (!embedded && ConnectionString.equals(name)) {
				UriCredentials.Extracted uri = UriCredentials.extract(value);
				embedded = uri.password() != null || hasSessionToken(uri.mechanismProperties());
			}
			if (embedded) {
				found.add(name);
			}
		}
		return found;
	}

	/** Add every item under its given id in one write; return the URI passwords stripped on the way. */
	Map<String, Object> apply(List<Map<String, Object>> items) {
		var added = new ArrayList<MongoConnectionProfile>();
		var recovered = new LinkedHashMap<String, Object>();
		for (Map<String, Object> item : items) {
			String id = item.get("newId") instanceof String text ? text : null;
			if (isBlank(id)) {
				throw new IllegalArgumentException("An imported connection has no id");
			}
			Map<String, String> fields = strings(item.get("fields"));
			Map<String, String> keyFiles = strings(item.get("keyFiles"));

			MongoConnectionProfile profile = MongoConnectionProfile.builder()
					.id(id)
					.name(blankTo(fields.get(Name), "Imported connection"))
					.connectionString(fields.get(ConnectionString))
					.authType(enumValue(AuthType.class, fields.get(AuthTypeField), AuthType.NONE))
					.username(fields.get(Username))
					.authSource(fields.get(AuthSource))
					.authMechanismProperties(fields.get(AuthMechanismProperties))
					.tls(Boolean.parseBoolean(fields.get(Tls)))
					.tlsCaFile(fields.get(TlsCaFile))
					.tlsCertificateFile(keyFiles.getOrDefault(TlsCertificateFile, fields.get(TlsCertificateFile)))
					.tlsAllowInvalidHostnames(Boolean.parseBoolean(fields.get(TlsAllowInvalidHostnames)))
					.tlsAllowInvalidCertificates(Boolean.parseBoolean(fields.get(TlsAllowInvalidCertificates)))
					.proxyType(enumValue(ProxyType.class, fields.get(ProxyTypeField), ProxyType.NONE))
					.proxyHost(fields.get(ProxyHost))
					.proxyPort(fields.get(ProxyPort))
					.proxyUsername(fields.get(ProxyUsername))
					.sshKeyPath(keyFiles.getOrDefault(SshKeyPath, fields.get(SshKeyPath)))
					.build();
			String password = strip(profile);
			if (password != null) {
				recovered.put(id, Map.of(Password, password));
			}
			added.add(profile);
		}
		profiles.addAll(added);
		return recovered;
	}

	void remove(List<String> ids) {
		profiles.removeAll(new HashSet<>(ids));
	}

	/** A profile's non-secret fields, from a stripped copy: no URI password, no session token. */
	static Map<String, Object> fields(MongoConnectionProfile saved) {
		MongoConnectionProfile profile = copy(saved);
		strip(profile);
		var fields = new LinkedHashMap<String, Object>();
		put(fields, Name, profile.getName());
		put(fields, ConnectionString, profile.getConnectionString());
		fields.put(AuthTypeField, profile.getEffectiveAuthType().name());
		put(fields, Username, profile.getUsername());
		put(fields, AuthSource, profile.getAuthSource());
		put(fields, AuthMechanismProperties, profile.getAuthMechanismProperties());
		fields.put(Tls, String.valueOf(profile.isTls()));
		put(fields, TlsCaFile, profile.getTlsCaFile());
		put(fields, TlsCertificateFile, profile.getTlsCertificateFile());
		fields.put(TlsAllowInvalidHostnames, String.valueOf(profile.isTlsAllowInvalidHostnames()));
		fields.put(TlsAllowInvalidCertificates, String.valueOf(profile.isTlsAllowInvalidCertificates()));
		fields.put(ProxyTypeField, profile.getEffectiveProxyType().name());
		put(fields, ProxyHost, profile.getProxyHost());
		put(fields, ProxyPort, profile.getProxyPort());
		put(fields, ProxyUsername, profile.getProxyUsername());
		put(fields, SshKeyPath, profile.getSshKeyPath());
		return fields;
	}

	/**
	 * Take the credentials out of the profile's connection string, as the connection editor
	 * does when it saves: the user, auth source, mechanism and its properties move to their
	 * fields where those are empty, and the string keeps the rest. Then drop any
	 * {@code AWS_SESSION_TOKEN} from the mechanism properties.
	 *
	 * @return the password the string carried, or {@code null}
	 */
	static String strip(MongoConnectionProfile profile) {
		String password = null;
		String uri = profile.getConnectionString();
		if (uri != null) {
			UriCredentials.Extracted found = UriCredentials.extract(uri);
			if (found.found()) {
				profile.setConnectionString(found.connectionString());
				password = found.password();
				if (profile.getEffectiveAuthType() == AuthType.NONE) {
					AuthType mechanism = AuthType.ofMechanism(found.mechanism());
					if (mechanism != null) {
						profile.setAuthType(mechanism);
					} else if (found.username() != null) {
						profile.setAuthType(AuthType.DEFAULT);
					}
				}
				if (isBlank(profile.getUsername())) {
					profile.setUsername(found.username());
				}
				if (isBlank(profile.getAuthSource())) {
					profile.setAuthSource(found.authSource());
				}
				profile.setAuthMechanismProperties(
						mergeProperties(profile.getAuthMechanismProperties(), found.mechanismProperties()));
			}
		}
		profile.setAuthMechanismProperties(withoutSessionToken(profile.getAuthMechanismProperties()));
		return password;
	}

	/** {@code KEY:value} pairs of {@code own}, then those of {@code more} whose key {@code own} lacks. */
	static String mergeProperties(String own, String more) {
		if (isBlank(more)) {
			return own;
		}
		if (isBlank(own)) {
			return more;
		}
		var keys = new HashSet<String>();
		var pairs = new ArrayList<String>();
		for (String pair : own.split(",")) {
			if (!pair.isBlank()) {
				keys.add(propertyKey(pair));
				pairs.add(pair.trim());
			}
		}
		for (String pair : more.split(",")) {
			if (!pair.isBlank() && keys.add(propertyKey(pair))) {
				pairs.add(pair.trim());
			}
		}
		return String.join(",", pairs);
	}

	/** The properties without an {@code AWS_SESSION_TOKEN} pair; {@code null} when none is left. */
	static String withoutSessionToken(String properties) {
		if (properties == null || !hasSessionToken(properties)) {
			return properties;
		}
		var kept = new ArrayList<String>();
		for (String pair : properties.split(",")) {
			if (!pair.isBlank() && !hasSessionToken(pair)) {
				kept.add(pair.trim());
			}
		}
		return kept.isEmpty() ? null : String.join(",", kept);
	}

	private static String propertyKey(String pair) {
		int colon = pair.indexOf(':');
		return (colon < 0 ? pair : pair.substring(0, colon)).trim().toUpperCase(Locale.ROOT);
	}

	private static boolean hasSessionToken(String text) {
		return text != null && text.toUpperCase(Locale.ROOT).contains(SessionTokenKey);
	}

	/**
	 * Everything secret about a profile: the stored password (or, for a string saved before
	 * the editor took it out, the password still in the connection string), the client
	 * certificate password and the SSH or proxy password.
	 */
	private Map<String, String> secrets(MongoConnectionProfile profile) {
		var secrets = new LinkedHashMap<String, String>();
		String id = profile.getId();
		boolean keyed = !isBlank(id);
		String stored = keyed ? credentials.loadPassword(id) : null;
		putSecret(secrets, Password, !isBlank(stored) ? stored : strip(copy(profile)));
		putSecret(secrets, TlsPassword, keyed ? credentials.loadPassword(CredentialStore.tlsKey(id)) : null);
		putSecret(secrets, ProxyPassword, keyed ? credentials.loadPassword(CredentialStore.proxyKey(id)) : null);
		return secrets;
	}

	private static MongoConnectionProfile copy(MongoConnectionProfile p) {
		return MongoConnectionProfile.builder()
				.id(p.getId())
				.name(p.getName())
				.connectionString(p.getConnectionString())
				.authType(p.getAuthType())
				.username(p.getUsername())
				.authSource(p.getAuthSource())
				.authMechanismProperties(p.getAuthMechanismProperties())
				.tls(p.isTls())
				.tlsCaFile(p.getTlsCaFile())
				.tlsCertificateFile(p.getTlsCertificateFile())
				.tlsAllowInvalidHostnames(p.isTlsAllowInvalidHostnames())
				.tlsAllowInvalidCertificates(p.isTlsAllowInvalidCertificates())
				.proxyType(p.getProxyType())
				.proxyHost(p.getProxyHost())
				.proxyPort(p.getProxyPort())
				.proxyUsername(p.getProxyUsername())
				.sshKeyPath(p.getSshKeyPath())
				.build();
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, String name, E fallback) {
		if (isBlank(name)) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			log.warn("Imported MongoDB connection has an unknown {} {}; using {}", type.getSimpleName(), name, fallback);
			return fallback;
		}
	}

	private static Map<String, Object> field(String name, String kind) {
		return Map.of("name", name, "kind", kind);
	}

	private static Map<String, Object> secret(String name, String suffix) {
		return Map.of("name", name, "kind", "SECRET", "credentialSuffix", suffix);
	}

	private static void put(Map<String, Object> fields, String name, String value) {
		if (!isBlank(value)) {
			fields.put(name, value);
		}
	}

	private static void putSecret(Map<String, String> secrets, String name, String value) {
		if (!isBlank(value)) {
			secrets.put(name, value);
		}
	}

	private static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}

	private static String blankTo(String value, String fallback) {
		return isBlank(value) ? fallback : value;
	}

	private static Map<String, String> strings(Object raw) {
		var map = new HashMap<String, String>();
		if (raw instanceof Map<?, ?> values) {
			values.forEach((key, value) -> {
				if (key instanceof String name && value instanceof String text) {
					map.put(name, text);
				}
			});
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Object raw) {
		var items = new ArrayList<Map<String, Object>>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					items.add((Map<String, Object>) map);
				}
			}
		}
		return items;
	}

	private static List<String> ids(Object raw) {
		var ids = new ArrayList<String>();
		if (raw instanceof List<?> list) {
			for (Object id : list) {
				if (id instanceof String text) {
					ids.add(text);
				}
			}
		}
		return ids;
	}

	/** Declared field names, in declaration order. */
	static List<String> fieldNames() {
		var names = new ArrayList<String>();
		for (Object field : (List<?>) describe().get("fields")) {
			names.add((String) ((Map<?, ?>) field).get("name"));
		}
		return names;
	}

	/** Declared non-secret field names, for tests. */
	static Set<String> plainFieldNames() {
		var names = new HashSet<String>();
		for (Object field : (List<?>) describe().get("fields")) {
			var map = (Map<?, ?>) field;
			if (!"SECRET".equals(map.get("kind"))) {
				names.add((String) map.get("name"));
			}
		}
		return names;
	}
}
