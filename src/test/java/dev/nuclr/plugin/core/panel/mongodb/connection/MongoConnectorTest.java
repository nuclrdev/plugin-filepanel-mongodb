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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.mongodb.AuthenticationMechanism;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.connection.ClusterConnectionMode;

class MongoConnectorTest {

	private static MongoConnectionProfile.MongoConnectionProfileBuilder profile(String uri) {
		return MongoConnectionProfile.builder().id("p1").name("Test").connectionString(uri);
	}

	private static MongoClientSettings settings(MongoConnectionProfile profile, String secret) throws Exception {
		return MongoConnector.settings(profile, new MongoConnector.Secrets(secret, null, null), null);
	}

	@Test
	void noAuthKeepsTheStringAndSetsShortTimeouts() throws Exception {
		var s = settings(profile("mongodb://h1:27017,h2:27017/?replicaSet=rs0&readPreference=secondary").build(), null);
		assertNull(s.getCredential());
		assertEquals("rs0", s.getClusterSettings().getRequiredReplicaSetName());
		assertEquals(2, s.getClusterSettings().getHosts().size());
		assertEquals("secondary", s.getReadPreference().getName());
		assertEquals(15, s.getClusterSettings().getServerSelectionTimeout(TimeUnit.SECONDS));
		assertEquals("Nuclr Commander", s.getApplicationName());
		assertFalse(s.getSslSettings().isEnabled());
	}

	@Test
	void timeoutsAndAppNameInTheStringWin() throws Exception {
		var s = settings(profile("mongodb://h/?serverSelectionTimeoutMS=2000&appName=mine").build(), null);
		assertEquals(2, s.getClusterSettings().getServerSelectionTimeout(TimeUnit.SECONDS));
		assertEquals("mine", s.getApplicationName());
	}

	@Test
	void directConnectionComesFromTheString() throws Exception {
		var s = settings(profile("mongodb://h:27017/?directConnection=true").build(), null);
		assertEquals(ClusterConnectionMode.SINGLE, s.getClusterSettings().getMode());
	}

	@Test
	void srvTurnsTlsOn() throws Exception {
		var s = settings(profile("mongodb+srv://cluster0.example.net/").build(), null);
		assertTrue(s.getSslSettings().isEnabled());
	}

	@Test
	void passwordAuthDefaultsTheSourceToTheStringsDatabaseThenAdmin() throws Exception {
		MongoCredential c = settings(profile("mongodb://h/shop").authType(AuthType.DEFAULT).username("app").build(),
				"pw").getCredential();
		assertEquals("app", c.getUserName());
		assertEquals("shop", c.getSource());
		assertArrayEquals("pw".toCharArray(), c.getPassword());
		assertNull(c.getAuthenticationMechanism());

		c = settings(profile("mongodb://h").authType(AuthType.SCRAM_SHA_256).username("app").build(), "pw").getCredential();
		assertEquals("admin", c.getSource());
		assertEquals(AuthenticationMechanism.SCRAM_SHA_256, c.getAuthenticationMechanism());

		c = settings(profile("mongodb://h").authType(AuthType.SCRAM_SHA_1).username("app").authSource("users").build(), "pw")
				.getCredential();
		assertEquals("users", c.getSource());
		assertEquals(AuthenticationMechanism.SCRAM_SHA_1, c.getAuthenticationMechanism());
	}

	@Test
	void externalMechanismsUseExternal() throws Exception {
		MongoCredential ldap = settings(profile("mongodb://h").authType(AuthType.PLAIN).username("u").build(), "pw")
				.getCredential();
		assertEquals("$external", ldap.getSource());
		assertEquals(AuthenticationMechanism.PLAIN, ldap.getAuthenticationMechanism());

		MongoCredential kerberos = settings(profile("mongodb://h").authType(AuthType.GSSAPI).username("u@REALM")
				.authMechanismProperties("SERVICE_NAME:mongo, CANONICALIZE_HOST_NAME:true").build(), null).getCredential();
		assertEquals(AuthenticationMechanism.GSSAPI, kerberos.getAuthenticationMechanism());
		assertEquals("mongo", kerberos.getMechanismProperty("SERVICE_NAME", null));
		assertEquals(Boolean.TRUE, kerberos.getMechanismProperty("CANONICALIZE_HOST_NAME", null));
	}

	@Test
	void x509TurnsTlsOnAndNeedsNoUser() throws Exception {
		var s = settings(profile("mongodb://h").authType(AuthType.X509).build(), null);
		assertEquals(AuthenticationMechanism.MONGODB_X509, s.getCredential().getAuthenticationMechanism());
		assertNull(s.getCredential().getUserName());
		assertTrue(s.getSslSettings().isEnabled());
	}

	@Test
	void awsWithKeysOrFromTheEnvironment() throws Exception {
		MongoCredential keys = settings(profile("mongodb://h").authType(AuthType.AWS).username("AKIA1")
				.authMechanismProperties("AWS_SESSION_TOKEN:tok").build(), "secret").getCredential();
		assertEquals("AKIA1", keys.getUserName());
		assertArrayEquals("secret".toCharArray(), keys.getPassword());
		assertEquals("tok", keys.getMechanismProperty("AWS_SESSION_TOKEN", null));

		MongoCredential environment = settings(profile("mongodb://h").authType(AuthType.AWS).build(), null).getCredential();
		assertEquals(AuthenticationMechanism.MONGODB_AWS, environment.getAuthenticationMechanism());
		assertNull(environment.getUserName());
	}

	@Test
	void oidcWithATokenOrAnEnvironment() throws Exception {
		MongoCredential token = settings(profile("mongodb://h").authType(AuthType.OIDC).build(), "eyJ.token").getCredential();
		assertNotNull(token.getMechanismProperty(MongoCredential.OIDC_CALLBACK_KEY, null));
		var callback = token.<MongoCredential.OidcCallback>getMechanismProperty(MongoCredential.OIDC_CALLBACK_KEY, null);
		assertEquals("eyJ.token", callback.onRequest(null).getAccessToken());

		MongoCredential azure = settings(profile("mongodb://h").authType(AuthType.OIDC)
				.authMechanismProperties("ENVIRONMENT:azure,TOKEN_RESOURCE:api://x").build(), null).getCredential();
		assertEquals("azure", azure.getMechanismProperty("ENVIRONMENT", null));
	}

	@Test
	void passwordMechanismsWithoutAUserAreRefused() {
		assertThrows(IllegalArgumentException.class,
				() -> settings(profile("mongodb://h").authType(AuthType.DEFAULT).build(), "pw"));
	}

	@Test
	void aBadStringSaysSo() {
		var e = assertThrows(IllegalArgumentException.class, () -> settings(profile("mongodb://").build(), null));
		assertTrue(e.getMessage().startsWith("The connection string is not valid"), e.getMessage());
	}

	@Test
	void socks5ProxyWithAndWithoutLogin() throws Exception {
		var s = MongoConnector.settings(profile("mongodb://h").proxyType(ProxyType.SOCKS5).proxyHost("proxy").build(),
				MongoConnector.Secrets.NONE, null);
		assertEquals("proxy", s.getSocketSettings().getProxySettings().getHost());
		assertEquals(1080, s.getSocketSettings().getProxySettings().getPort());
		assertNull(s.getSocketSettings().getProxySettings().getUsername());

		s = MongoConnector.settings(profile("mongodb://h").proxyType(ProxyType.SOCKS5).proxyHost("proxy")
				.proxyPort("9050").proxyUsername("me").build(), new MongoConnector.Secrets(null, null, "pp"), null);
		assertEquals(9050, s.getSocketSettings().getProxySettings().getPort());
		assertEquals("me", s.getSocketSettings().getProxySettings().getUsername());
		assertEquals("pp", s.getSocketSettings().getProxySettings().getPassword());
	}

	@Test
	void sshPointsTheDriverAtTheTunnelsSocksPort() throws Exception {
		var s = MongoConnector.settings(profile("mongodb://h1,h2/?replicaSet=rs0").proxyType(ProxyType.SSH)
				.proxyHost("bastion").build(), MongoConnector.Secrets.NONE, 40123);
		assertEquals("127.0.0.1", s.getSocketSettings().getProxySettings().getHost());
		assertEquals(40123, s.getSocketSettings().getProxySettings().getPort());
	}

	@Test
	void trustingAnyCertificateTurnsHostNameChecksOff() throws Exception {
		var s = settings(profile("mongodb://h/?tls=true").tlsAllowInvalidCertificates(true).build(), null);
		assertTrue(s.getSslSettings().isEnabled());
		assertTrue(s.getSslSettings().isInvalidHostNameAllowed());
		assertNotNull(s.getSslSettings().getContext());
	}

	@Test
	void mechanismPropertiesParse() {
		assertEquals(List.of("A", "B"), List.copyOf(MongoConnector.mechanismProperties("A:1, B:false").keySet()));
		assertEquals(Boolean.FALSE, MongoConnector.mechanismProperties("A:1, B:false").get("B"));
		assertTrue(MongoConnector.mechanismProperties("  ").isEmpty());
	}

	@Test
	void profileSaysWhichSecretsItNeeds() {
		assertTrue(profile("mongodb://h").authType(AuthType.DEFAULT).username("u").build().needsSecret());
		assertFalse(profile("mongodb://h").authType(AuthType.AWS).build().needsSecret());
		assertTrue(profile("mongodb://h").authType(AuthType.AWS).username("AKIA").build().needsSecret());
		assertFalse(profile("mongodb://h").authType(AuthType.X509).build().needsSecret());
		assertTrue(profile("mongodb://h").proxyType(ProxyType.SSH).proxyHost("b").build().needsProxySecret());
		assertFalse(profile("mongodb://h").proxyType(ProxyType.SSH).proxyHost("b").sshKeyPath("~/.ssh/id").build()
				.needsProxySecret());
		assertFalse(profile("mongodb://h").proxyType(ProxyType.SOCKS5).proxyHost("p").build().needsProxySecret());
		assertEquals("mongodb://h1,h2 via ssh bastion", profile("mongodb://u@h1,h2/db").proxyType(ProxyType.SSH)
				.proxyHost("bastion").build().getDisplayTarget());
	}
}
