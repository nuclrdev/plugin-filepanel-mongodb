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

import static dev.nuclr.plugin.core.panel.mongodb.Messages.tr;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

import dev.nuclr.plugin.core.panel.mongodb.client.DriverSession;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns a saved profile and its secrets into a live {@link MongoSession}: the connection
 * string as the driver reads it, then the credential, TLS and proxy from the form on top.
 *
 * <p>The connection string carries the topology (a single host, a replica set's seed
 * list, mongos routers, {@code mongodb+srv}, {@code directConnection},
 * {@code loadBalanced}) and every other option; the form carries what a string cannot
 * hold safely or at all: secrets, certificate files, an SSH tunnel.
 */
@Slf4j
public final class MongoConnector {

	/** The profile's three possible secrets; any may be {@code null}. */
	public record Secrets(String main, String tls, String proxy) {

		public static final Secrets NONE = new Secrets(null, null, null);
	}

	/** Shorter than the driver's 30 s: a wrong host should not hang the panel that long. */
	static final int DEFAULT_SERVER_SELECTION_SECONDS = 15;
	static final int DEFAULT_CONNECT_SECONDS = 15;
	static final String APP_NAME = "Nuclr Commander";

	private MongoConnector() {
	}

	/** Connect: open the SSH tunnel first when there is one, then the client. Nothing is checked yet. */
	public static MongoSession open(MongoConnectionProfile profile, Secrets secrets) throws Exception {
		Secrets s = secrets == null ? Secrets.NONE : secrets;
		// Built once before the tunnel opens, so a bad string or certificate fails before
		// any SSH host-key or password prompt.
		settings(profile, s, null);
		SshTunnel tunnel = profile.getEffectiveProxyType() == ProxyType.SSH ? SshTunnel.open(profile, s.proxy()) : null;
		try {
			MongoClientSettings settings = settings(profile, s, tunnel == null ? null : tunnel.localPort());
			MongoClient client = MongoClients.create(settings);
			return new DriverSession(client, new ConnectionString(connectionString(profile)).getDatabase(), tunnel);
		} catch (Exception | Error e) {
			if (tunnel != null) {
				tunnel.close();
			}
			throw e;
		}
	}

	/**
	 * The driver settings for a profile.
	 *
	 * @param sshSocksPort the local port of the SSH tunnel's SOCKS proxy, or {@code null}
	 */
	public static MongoClientSettings settings(MongoConnectionProfile profile, Secrets secrets, Integer sshSocksPort)
			throws Exception {
		String uri = connectionString(profile);
		ConnectionString cs;
		try {
			cs = new ConnectionString(uri);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(tr("connect.error.uri", e.getMessage()), e);
		}
		var builder = MongoClientSettings.builder().applyConnectionString(cs);
		if (cs.getApplicationName() == null) {
			builder.applicationName(APP_NAME);
		}
		if (cs.getServerSelectionTimeout() == null) {
			builder.applyToClusterSettings(c -> c.serverSelectionTimeout(DEFAULT_SERVER_SELECTION_SECONDS, TimeUnit.SECONDS));
		}
		if (cs.getConnectTimeout() == null) {
			builder.applyToSocketSettings(c -> c.connectTimeout(DEFAULT_CONNECT_SECONDS, TimeUnit.SECONDS));
		}

		MongoCredential credential = credential(profile, secrets.main(), cs);
		if (credential != null) {
			builder.credential(credential);
		}

		boolean tlsOn = profile.isTls() || Boolean.TRUE.equals(cs.getSslEnabled()) || cs.isSrvProtocol()
				|| profile.hasClientCertificate() || profile.getEffectiveAuthType() == AuthType.X509;
		if (tlsOn) {
			SSLContext context = TlsContexts.needed(profile) ? TlsContexts.build(profile, secrets.tls()) : null;
			boolean allowInvalidHosts = profile.isTlsAllowInvalidHostnames() || profile.isTlsAllowInvalidCertificates()
					|| Boolean.TRUE.equals(cs.getSslInvalidHostnameAllowed());
			builder.applyToSslSettings(ssl -> {
				ssl.enabled(true).invalidHostNameAllowed(allowInvalidHosts);
				if (context != null) {
					ssl.context(context);
				}
			});
		}

		switch (profile.getEffectiveProxyType()) {
			case SSH -> {
				if (sshSocksPort != null) {
					int port = sshSocksPort;
					builder.applyToSocketSettings(socket -> socket.applyToProxySettings(
							proxy -> proxy.host("127.0.0.1").port(port)));
				}
			}
			case SOCKS5 -> {
				String host = trimToNull(profile.getProxyHost());
				if (host == null) {
					throw new IllegalArgumentException(tr("proxy.error.no.host"));
				}
				int port = SshTunnel.parsePort(profile.getProxyPort(), 1080);
				String user = trimToNull(profile.getProxyUsername());
				String password = user == null ? null : secrets.proxy() == null ? "" : secrets.proxy();
				builder.applyToSocketSettings(socket -> socket.applyToProxySettings(proxy -> {
					proxy.host(host).port(port);
					if (user != null) {
						proxy.username(user).password(password);
					}
				}));
			}
			case NONE -> {
				// whatever proxyHost the connection string names, if any
			}
		}
		return builder.build();
	}

	/** The saved string, or the local default when blank. */
	static String connectionString(MongoConnectionProfile profile) {
		String uri = trimToNull(profile.getConnectionString());
		return uri == null ? "mongodb://localhost:27017" : uri;
	}

	/**
	 * The credential the form describes, or {@code null} to keep the connection
	 * string's own (with {@link AuthType#NONE}, a string with {@code authMechanism} still
	 * authenticates).
	 */
	static MongoCredential credential(MongoConnectionProfile profile, String secret, ConnectionString cs)
			throws IOException {
		AuthType type = profile.getEffectiveAuthType();
		String user = trimToNull(profile.getUsername());
		String source = trimToNull(profile.getAuthSource());
		char[] password = secret == null ? null : secret.toCharArray();
		if (type.passwordRequired()) {
			if (user == null) {
				throw new IllegalArgumentException(tr("connect.error.no.user"));
			}
			if (password == null) {
				password = new char[0];
			}
		}
		if (source == null) {
			source = type.external() ? "$external" : cs.getDatabase() != null ? cs.getDatabase() : "admin";
		}
		MongoCredential credential = switch (type) {
			case NONE -> null;
			case DEFAULT -> MongoCredential.createCredential(user, source, password);
			case SCRAM_SHA_256 -> MongoCredential.createScramSha256Credential(user, source, password);
			case SCRAM_SHA_1 -> MongoCredential.createScramSha1Credential(user, source, password);
			case PLAIN -> MongoCredential.createPlainCredential(user, source, password);
			case GSSAPI -> {
				if (user == null) {
					throw new IllegalArgumentException(tr("connect.error.no.user"));
				}
				yield MongoCredential.createGSSAPICredential(user);
			}
			case X509 -> user == null ? MongoCredential.createMongoX509Credential()
					: MongoCredential.createMongoX509Credential(user);
			case AWS -> user == null ? MongoCredential.createAwsCredential(null, null)
					: MongoCredential.createAwsCredential(user, password == null ? new char[0] : password);
			case OIDC -> {
				MongoCredential oidc = MongoCredential.createOidcCredential(user);
				if (secret != null && !secret.isBlank()) {
					String token = secret.trim();
					oidc = oidc.withMechanismProperty(MongoCredential.OIDC_CALLBACK_KEY,
							(MongoCredential.OidcCallback) context -> new MongoCredential.OidcCallbackResult(token));
				}
				yield oidc;
			}
		};
		if (credential == null) {
			return null;
		}
		for (Map.Entry<String, Object> property : mechanismProperties(profile.getAuthMechanismProperties()).entrySet()) {
			credential = credential.withMechanismProperty(property.getKey(), property.getValue());
		}
		return credential;
	}

	/**
	 * {@code SERVICE_NAME:mongodb,CANONICALIZE_HOST_NAME:true} as the driver takes it:
	 * {@code true} / {@code false} become booleans, everything else stays text.
	 */
	static Map<String, Object> mechanismProperties(String text) {
		var properties = new LinkedHashMap<String, Object>();
		if (text == null || text.isBlank()) {
			return properties;
		}
		for (String pair : text.split(",")) {
			int colon = pair.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String key = pair.substring(0, colon).trim();
			String value = pair.substring(colon + 1).trim();
			String lower = value.toLowerCase(Locale.ROOT);
			properties.put(key, "true".equals(lower) ? Boolean.TRUE : "false".equals(lower) ? Boolean.FALSE : value);
		}
		return properties;
	}

	private static String trimToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}
}
