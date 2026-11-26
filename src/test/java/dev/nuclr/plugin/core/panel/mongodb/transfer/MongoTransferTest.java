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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.mongodb.MongoFilePanelPlugin;
import dev.nuclr.plugin.core.panel.mongodb.PluginIds;
import dev.nuclr.plugin.core.panel.mongodb.connection.AuthType;
import dev.nuclr.plugin.core.panel.mongodb.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnectionProfile;
import dev.nuclr.plugin.core.panel.mongodb.connection.ProxyType;
import dev.nuclr.plugin.core.panel.mongodb.support.FakeContext;

class MongoTransferTest {

	private final FakeContext context = new FakeContext();
	private final ConnectionProfileStore store = new ConnectionProfileStore(context.settings);
	private final MongoFilePanelPlugin plugin = new MongoFilePanelPlugin();

	MongoTransferTest() {
		plugin.preinit(context);
	}

	/** One action through the plugin's act, as Commander sends it. */
	private Map<String, Object> act(String action, Map<String, Object> input) {
		var data = new HashMap<String, Object>(input);
		data.put("transfer.protocol", 1);
		if (!action.equals(MongoTransfer.Describe)) {
			data.putIfAbsent("type", "connections");
		}
		plugin.act(null, action, List.of(), null, data, null);
		return data;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> rows() {
		return (List<Map<String, Object>>) act(MongoTransfer.Snapshot, Map.of()).get("rows");
	}

	@Test
	void describeDeclaresTheConnectionsCategoryWithEveryFieldTagged() {
		var data = act(MongoTransfer.Describe, Map.of());

		@SuppressWarnings("unchecked")
		var providers = (List<Map<String, Object>>) data.get("transfer.providers");
		assertEquals(1, providers.size());
		var provider = providers.getFirst();
		assertEquals("connections", provider.get("type"));
		assertEquals(1, provider.get("version"));
		assertEquals("MongoDB", provider.get("title"));
		var kinds = new HashMap<String, Object>();
		var suffixes = new HashMap<String, Object>();
		for (Object field : (List<?>) provider.get("fields")) {
			var map = (Map<?, ?>) field;
			String name = (String) map.get("name");
			assertTrue(name.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}"), name);
			kinds.put(name, map.get("kind"));
			suffixes.put(name, map.get("credentialSuffix"));
		}
		assertEquals("ROUTING", kinds.get("connectionString"));
		assertEquals("ROUTING", kinds.get("proxyType"));
		assertEquals("ROUTING", kinds.get("proxyHost"));
		assertEquals("ROUTING", kinds.get("proxyPort"));
		assertEquals("PRIVATE_KEY_PATH", kinds.get("sshKeyPath"));
		assertEquals("PRIVATE_KEY_PATH", kinds.get("tlsCertificateFile"));
		assertEquals("PATH", kinds.get("tlsCaFile"));
		assertEquals("PLAIN", kinds.get("username"));
		assertEquals("SECRET", kinds.get("password"));
		assertEquals("", suffixes.get("password"));
		assertEquals(":tls", suffixes.get("tlsPassword"));
		assertEquals(":proxy", suffixes.get("proxyPassword"));
		for (var entry : kinds.entrySet()) {
			assertEquals("SECRET".equals(entry.getValue()), suffixes.get(entry.getKey()) != null, entry.getKey());
		}
	}

	@Test
	void aSnapshotNeverCarriesASecretNorASessionToken() {
		store.add(MongoConnectionProfile.builder().id("a").name("atlas")
				.connectionString("mongodb+srv://app:hunter2@cluster0.x.mongodb.net/shop?retryWrites=true")
				.authType(AuthType.AWS).username("AKIA1")
				.authMechanismProperties("AWS_SESSION_TOKEN:FwoGZXIvYXdz,SERVICE_NAME:mongodb")
				.proxyType(ProxyType.SSH).proxyHost("bastion").proxyPort("2222").sshKeyPath("/keys/id")
				.tlsCertificateFile("/keys/client.pem").tlsCaFile("/keys/ca.pem").build());
		context.credentials.secrets.put("a", "aws-secret");

		var row = rows().getFirst();

		assertEquals("a", row.get("rowId"));
		assertEquals("atlas", row.get("label"));
		assertEquals(true, row.get("hasSecrets"));
		var fields = (Map<?, ?>) row.get("fields");
		assertEquals("mongodb+srv://cluster0.x.mongodb.net/shop?retryWrites=true", fields.get("connectionString"));
		assertEquals("SERVICE_NAME:mongodb", fields.get("authMechanismProperties"));
		assertEquals("AWS", fields.get("authType"));
		assertEquals("SSH", fields.get("proxyType"));
		assertEquals("bastion", fields.get("proxyHost"));
		assertEquals("2222", fields.get("proxyPort"));
		assertEquals("false", fields.get("tls"));
		String all = fields.toString();
		assertFalse(all.contains("hunter2"));
		assertFalse(all.contains("aws-secret"));
		assertFalse(all.contains("FwoGZXIvYXdz"));
		assertFalse(all.contains("AWS_SESSION_TOKEN"));
		for (Object value : fields.values()) {
			assertTrue(value instanceof String, "every value is a string");
		}
	}

	@Test
	void anOldUriWithCredentialsComesOutAsTheEditorWouldSaveIt() {
		store.add(MongoConnectionProfile.builder().id("a").name("legacy")
				.connectionString("mongodb://bob:pw%40x@db1:27017/?authSource=admin&authMechanismProperties=AWS_SESSION_TOKEN:tok")
				.build());

		var fields = (Map<?, ?>) rows().getFirst().get("fields");

		assertEquals("mongodb://db1:27017/", fields.get("connectionString"));
		assertEquals("bob", fields.get("username"));
		assertEquals("admin", fields.get("authSource"));
		assertEquals("DEFAULT", fields.get("authType"));
		assertNull(fields.get("authMechanismProperties"), "only the session token was in there");
		// The password still in the string is the row's secret, not a field.
		assertEquals(Map.of("password", "pw@x"), act(MongoTransfer.ReadSecrets, Map.of("rowId", "a")).get("secrets"));
	}

	@Test
	void readSecretsGivesEveryStoredSecretUnderItsField() {
		store.add(MongoConnectionProfile.builder().id("a").name("shop").connectionString("mongodb://db").build());
		context.credentials.secrets.put("a", "db-password");
		context.credentials.secrets.put("a:tls", "cert-password");
		context.credentials.secrets.put("a:proxy", "ssh-password");
		rows();

		var secrets = (Map<?, ?>) act(MongoTransfer.ReadSecrets, Map.of("rowId", "a")).get("secrets");

		assertEquals(Map.of("password", "db-password", "tlsPassword", "cert-password", "proxyPassword", "ssh-password"),
				secrets);
		assertEquals(Map.of(), act(MongoTransfer.ReadSecrets, Map.of("rowId", "unknown")).get("secrets"));
	}

	@Test
	void anEntryWithoutAnIdIsStillExportable() {
		context.settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "[{\"name\":\"legacy\"}]");

		var row = rows().getFirst();

		assertEquals("#1", row.get("rowId"));
		assertEquals(false, row.get("hasSecrets"));
		assertEquals(Map.of(), act(MongoTransfer.ReadSecrets, Map.of("rowId", "#1")).get("secrets"));
	}

	@Test
	void findEmbeddedSecretsNamesAUriPasswordAndASessionToken() {
		assertEquals(List.of("connectionString"), embedded(Map.of("connectionString", "mongodb://u:p@h/")));
		assertEquals(List.of("connectionString"),
				embedded(Map.of("connectionString", "mongodb://h/?authMechanismProperties=AWS_SESSION_TOKEN%3Ax")));
		assertEquals(List.of("authMechanismProperties"),
				embedded(Map.of("authMechanismProperties", "SERVICE_NAME:m,aws_session_token:x")));
		assertEquals(List.of(), embedded(Map.of("connectionString", "mongodb://u@h/?tls=true",
				"authMechanismProperties", "SERVICE_NAME:mongodb")));
	}

	private List<?> embedded(Map<String, String> fields) {
		return (List<?>) act(MongoTransfer.FindEmbeddedSecrets, Map.of("fields", fields)).get("fieldNames");
	}

	@Test
	void applyAppendsUnderTheGivenIdsKeepsTheRestAndHandsBackWhatItStripped() {
		store.add(MongoConnectionProfile.builder().id("existing").name("local").connectionString("mongodb://localhost")
				.build());

		var crafted = new HashMap<String, String>();
		crafted.put("name", "atlas");
		crafted.put("connectionString", "mongodb+srv://app:crafted@cluster0.x.mongodb.net/?w=majority");
		crafted.put("authMechanismProperties", "AWS_SESSION_TOKEN:tok,SERVICE_NAME:m");
		crafted.put("tls", "true");
		crafted.put("tlsAllowInvalidHostnames", "true");
		crafted.put("proxyType", "SSH");
		crafted.put("proxyHost", "bastion");
		crafted.put("sshKeyPath", "/elsewhere/id");
		crafted.put("tlsCertificateFile", "/elsewhere/client.p12");
		crafted.put("tlsCaFile", "/certs/ca.pem");
		var data = act(MongoTransfer.Apply, Map.of("items", List.of(
				item("new-1", crafted, Map.of("sshKeyPath", "/keys/id", "tlsCertificateFile", "/keys/client.p12")),
				item("new-2", Map.of("connectionString", "mongodb://db/other", "authType", "nonsense"), Map.of()))));

		assertEquals(List.of("existing", "new-1", "new-2"),
				store.loadAll().stream().map(MongoConnectionProfile::getId).toList());
		var atlas = store.findById("new-1");
		assertEquals("mongodb+srv://cluster0.x.mongodb.net/?w=majority", atlas.getConnectionString(),
				"a crafted file cannot put a password back");
		assertEquals("app", atlas.getUsername());
		assertEquals(AuthType.DEFAULT, atlas.getAuthType());
		assertEquals("SERVICE_NAME:m", atlas.getAuthMechanismProperties(), "the session token is dropped");
		assertTrue(atlas.isTls());
		assertTrue(atlas.isTlsAllowInvalidHostnames());
		assertEquals(ProxyType.SSH, atlas.getProxyType());
		assertEquals("/keys/id", atlas.getSshKeyPath());
		assertEquals("/keys/client.p12", atlas.getTlsCertificateFile());
		assertEquals("/certs/ca.pem", atlas.getTlsCaFile());
		var other = store.findById("new-2");
		assertEquals("Imported connection", other.getName());
		assertEquals(AuthType.NONE, other.getAuthType());
		assertEquals(Map.of("new-1", Map.of("password", "crafted")), data.get("recoveredSecrets"));
		assertTrue(context.credentials.secrets.isEmpty(), "apply never writes the keychain");
	}

	@Test
	void anApplyWithNothingToRecoverAnswersNoRecoveredSecrets() {
		var data = act(MongoTransfer.Apply,
				Map.of("items", List.of(item("n", Map.of("name", "x", "connectionString", "mongodb://h"), Map.of()))));

		assertFalse(data.containsKey("recoveredSecrets"));
		assertEquals(List.of("n"), store.loadAll().stream().map(MongoConnectionProfile::getId).toList());
	}

	@Test
	void applyOverAnUnreadableStoreThrowsAndChangesNothing() {
		context.settings.putRaw(PluginIds.PLUGIN_ID, "profiles.json", "{not json");

		assertThrows(IllegalStateException.class,
				() -> act(MongoTransfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x"), Map.of())))));
		assertEquals("{not json", context.settings.get(PluginIds.PLUGIN_ID, "profiles.json"));
		assertThrows(IllegalStateException.class, () -> act(MongoTransfer.Remove, Map.of("ids", List.of("n"))));
		assertEquals("{not json", context.settings.get(PluginIds.PLUGIN_ID, "profiles.json"));
	}

	@Test
	void aFailedWriteThrowsInsteadOfBeingSwallowed() {
		store.add(MongoConnectionProfile.builder().id("existing").name("local").build());
		context.settings.failWrites = true;

		assertThrows(IllegalStateException.class,
				() -> act(MongoTransfer.Apply, Map.of("items", List.of(item("n", Map.of("name", "x"), Map.of())))));
		assertThrows(IllegalStateException.class, () -> act(MongoTransfer.Remove, Map.of("ids", List.of("existing"))));
		context.settings.failWrites = false;
		assertEquals(List.of("existing"), store.loadAll().stream().map(MongoConnectionProfile::getId).toList());
	}

	@Test
	void removeDeletesOnlyTheGivenIds() {
		store.add(MongoConnectionProfile.builder().id("keep").name("a").build());
		store.add(MongoConnectionProfile.builder().id("drop").name("b").build());

		act(MongoTransfer.Remove, Map.of("ids", List.of("drop", "unknown")));

		assertEquals(List.of("keep"), store.loadAll().stream().map(MongoConnectionProfile::getId).toList());
	}

	@Test
	void anotherProtocolVersionOrCategoryIsNotAnswered() {
		var data = new HashMap<String, Object>();
		data.put("transfer.protocol", 2);
		plugin.act(null, MongoTransfer.Describe, List.of(), null, data, null);
		assertFalse(data.containsKey("transfer.providers"));

		var other = act(MongoTransfer.Snapshot, Map.of("type", "profiles"));
		assertFalse(other.containsKey("rows"));
	}

	@Test
	void everyExportedFieldIsDeclaredAndNotSecret() {
		var exported = new ArrayList<>(MongoTransfer.fields(MongoConnectionProfile.builder().name("n")
				.connectionString("mongodb://h").authType(AuthType.SCRAM_SHA_256).username("u").authSource("admin")
				.authMechanismProperties("SERVICE_NAME:m").tls(true).tlsCaFile("ca").tlsCertificateFile("cert")
				.tlsAllowInvalidHostnames(true).tlsAllowInvalidCertificates(true).proxyType(ProxyType.SOCKS5)
				.proxyHost("ph").proxyPort("1080").proxyUsername("pu").sshKeyPath("k").build()).keySet());
		assertTrue(MongoTransfer.plainFieldNames().containsAll(exported), exported.toString());
		assertEquals(MongoTransfer.plainFieldNames().size(), exported.size(), "every plain field is exported");
	}

	private static Map<String, Object> item(String id, Map<String, String> fields, Map<String, String> keyFiles) {
		return Map.of("newId", id, "fields", fields, "keyFiles", keyFiles);
	}
}
