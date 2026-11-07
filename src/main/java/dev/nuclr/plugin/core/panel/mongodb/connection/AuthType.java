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

/**
 * How a connection authenticates: every mechanism the MongoDB Java driver speaks. The
 * secret itself lives in {@link CredentialStore}.
 */
public enum AuthType {

	/** No credential, or whatever the connection string itself says. */
	NONE("", false),

	/** Username and password; the server picks SCRAM-SHA-256 or SCRAM-SHA-1. */
	DEFAULT("", true),

	SCRAM_SHA_256("SCRAM-SHA-256", true),

	SCRAM_SHA_1("SCRAM-SHA-1", true),

	/** LDAP proxy authentication (Enterprise and Atlas), password sent as SASL PLAIN. */
	PLAIN("PLAIN", true),

	/** Kerberos, with the ticket from the OS or a JAAS login; no password here. */
	GSSAPI("GSSAPI", false),

	/** The client certificate on the TLS tab is the identity. */
	X509("MONGODB-X509", false),

	/**
	 * AWS IAM: an access key id and secret key, or, with no access key id, the
	 * credentials of the environment (AWS_* variables, ECS task role, EC2 instance role).
	 */
	AWS("MONGODB-AWS", false),

	/**
	 * OpenID Connect workload identity: an access token pasted here, or one the driver
	 * fetches itself with ENVIRONMENT:azure / gcp / k8s in the mechanism properties.
	 */
	OIDC("MONGODB-OIDC", false);

	private final String mechanism;
	private final boolean passwordRequired;

	AuthType(String mechanism, boolean passwordRequired) {
		this.mechanism = mechanism;
		this.passwordRequired = passwordRequired;
	}

	/** The {@code authMechanism} name in a connection string; blank for NONE and DEFAULT. */
	public String mechanism() {
		return mechanism;
	}

	/** Whether connecting cannot go on without a secret. */
	public boolean passwordRequired() {
		return passwordRequired;
	}

	/** Whether the form offers a secret field at all. */
	public boolean takesSecret() {
		return passwordRequired || this == AWS || this == OIDC;
	}

	/** Whether the credential lives outside the database ({@code $external}). */
	public boolean external() {
		return this == PLAIN || this == GSSAPI || this == X509 || this == AWS || this == OIDC;
	}

	/** The type for an {@code authMechanism} value, ignoring case; {@code null} when unknown. */
	public static AuthType ofMechanism(String mechanism) {
		if (mechanism == null || mechanism.isBlank()) {
			return null;
		}
		for (AuthType type : values()) {
			if (!type.mechanism.isEmpty() && type.mechanism.equalsIgnoreCase(mechanism.trim())) {
				return type;
			}
		}
		return null;
	}
}
