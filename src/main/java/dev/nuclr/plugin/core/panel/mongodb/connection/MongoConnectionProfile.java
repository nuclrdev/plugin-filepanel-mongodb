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

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A saved MongoDB connection's non-secret metadata, persisted as JSON by
 * {@link ConnectionProfileStore}. No password, key or token is ever a field here (see
 * {@link CredentialStore}), and the connection string is saved without one: the editor
 * moves a pasted {@code user:password@} into the form (see {@link UriCredentials}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MongoConnectionProfile implements Serializable {

	private static final long serialVersionUID = 1L;

	private String id;

	private String name;

	/**
	 * A {@code mongodb://} or {@code mongodb+srv://} connection string: one host, a
	 * replica set's seed list, mongos routers or an Atlas SRV name, plus any option the
	 * driver knows ({@code replicaSet}, {@code readPreference}, {@code directConnection},
	 * {@code tls}, {@code compressors}...).
	 */
	private String connectionString;

	@Builder.Default
	private AuthType authType = AuthType.NONE;

	/** The user; for AWS the access key id, for X.509 the certificate subject (optional). */
	private String username;

	/** Database the user is defined in; blank means admin, or $external for external mechanisms. */
	private String authSource;

	/** {@code KEY:value,KEY:value}, as {@code authMechanismProperties} in a connection string. */
	private String authMechanismProperties;

	/** Force TLS on; off leaves it to the connection string ({@code mongodb+srv} is always TLS). */
	private boolean tls;

	/** A PEM file with the CA certificates to trust instead of the system ones. */
	private String tlsCaFile;

	/** The client certificate: a PEM file with the certificate and its key, or a .p12 / .pfx file. */
	private String tlsCertificateFile;

	/** Accept a certificate issued to another host name. */
	private boolean tlsAllowInvalidHostnames;

	/** Accept any certificate at all. For a throwaway local server only. */
	private boolean tlsAllowInvalidCertificates;

	@Builder.Default
	private ProxyType proxyType = ProxyType.NONE;

	/** The SSH server or the SOCKS5 proxy. */
	private String proxyHost;

	/** Blank means 22 for SSH and 1080 for SOCKS5. */
	private String proxyPort;

	/** SSH user (blank: the local user name, as ssh does) or SOCKS5 user (blank: no login). */
	private String proxyUsername;

	/** SSH private key file; blank to log in with the SSH password alone. */
	private String sshKeyPath;

	/** Shown in the explorer root's Hosts column: the hosts, never options or credentials. */
	@JsonIgnore
	public String getDisplayTarget() {
		String hosts = UriCredentials.hosts(connectionString);
		String via = proxyHost == null || proxyHost.isBlank() ? "" : proxyHost.trim();
		return switch (proxyType == null ? ProxyType.NONE : proxyType) {
			case SSH -> via.isEmpty() ? hosts : hosts + " via ssh " + via;
			case SOCKS5 -> via.isEmpty() ? hosts : hosts + " via socks " + via;
			case NONE -> hosts;
		};
	}

	@JsonIgnore
	public AuthType getEffectiveAuthType() {
		return authType == null ? AuthType.NONE : authType;
	}

	@JsonIgnore
	public ProxyType getEffectiveProxyType() {
		return proxyType == null ? ProxyType.NONE : proxyType;
	}

	/** Whether connecting should ask for the main secret when none is stored. */
	@JsonIgnore
	public boolean needsSecret() {
		AuthType type = getEffectiveAuthType();
		return type.passwordRequired() || type == AuthType.AWS && !isBlank(username);
	}

	/**
	 * Whether connecting should ask for an SSH or proxy password when none is stored. A
	 * key file logs in to SSH on its own (an encrypted key's passphrase is entered in the
	 * editor), and a SOCKS5 proxy without a user needs none.
	 */
	@JsonIgnore
	public boolean needsProxySecret() {
		return switch (getEffectiveProxyType()) {
			case SSH -> isBlank(sshKeyPath);
			case SOCKS5 -> !isBlank(proxyUsername);
			case NONE -> false;
		};
	}

	@JsonIgnore
	public boolean hasClientCertificate() {
		return !isBlank(tlsCertificateFile);
	}

	static boolean isBlank(String text) {
		return text == null || text.isBlank();
	}
}
