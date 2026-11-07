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
package dev.nuclr.plugin.core.panel.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.mongodb.MongoSecurityException;
import com.mongodb.MongoCredential;

import dev.nuclr.plugin.core.panel.mongodb.actions.MongoActions;
import dev.nuclr.plugin.core.panel.mongodb.connection.AuthType;
import dev.nuclr.plugin.core.panel.mongodb.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnectionProfile;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnector;
import dev.nuclr.plugin.core.panel.mongodb.support.FakeContext;
import dev.nuclr.plugin.core.panel.mongodb.support.FakeSession;
import dev.nuclr.plugin.core.panel.mongodb.support.RecordingCallback;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The actions over an in-memory deployment, run the way the commander runs them: on a
 * plugin instance that was only preinit-ed and init-ed, through {@code act} with no other
 * panel, no selection and no focused resource.
 */
class MongoActionsTest {

	/** The schema keywords Commander accepts (its ActionSchema.KEYWORDS). */
	private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "enum", "items",
			"description", "default", "minimum", "maximum", "maxLength");

	private final ObjectMapper mapper = new ObjectMapper();
	private final List<MongoConnector.Secrets> secretsSeen = new ArrayList<>();
	private FakeSession session;
	private FakeContext context;
	private MongoFilePanelPlugin plugin;
	private MongoConnectionProfile profile;
	private int opened;

	@BeforeEach
	void setUp() {
		session = FakeSession.sample();
		context = new FakeContext();
		plugin = new MongoFilePanelPlugin((p, secrets) -> {
			secretsSeen.add(secrets);
			opened++;
			return session;
		});
		plugin.preinit(context);
		plugin.init();
		profile = new ConnectionProfileStore(context.settings).add(MongoConnectionProfile.builder()
				.name("Dev").connectionString("mongodb://localhost:27017").build());
	}

	@AfterEach
	void tearDown() {
		plugin.unload();
	}

	private RecordingCallback run(String id, Map<String, Object> args) {
		var callback = new RecordingCallback();
		plugin.act(null, id, List.of(), null, new HashMap<>(args), callback);
		return callback;
	}

	private Map<String, Object> ok(String id, Map<String, Object> args) {
		RecordingCallback callback = run(id, args);
		assertNull(callback.error, () -> id + " failed: " + callback.error);
		assertTrue(callback.completed, id + " should complete");
		assertNotNull(callback.result, id + " should report a result");
		return callback.result;
	}

	private String error(String id, Map<String, Object> args) {
		RecordingCallback callback = run(id, args);
		assertNotNull(callback.error, () -> id + " should fail, but returned " + callback.result);
		assertFalse(callback.completed);
		return callback.error;
	}

	@SuppressWarnings("unchecked")
	private static <T> T get(Map<String, Object> map, String key) {
		return (T) map.get(key);
	}

	private Map<String, Object> shop(String collection, Map<String, Object> more) {
		var args = new HashMap<String, Object>(more);
		args.put("connection", "Dev");
		args.put("database", "shop");
		args.put("collection", collection);
		return args;
	}

	// ------------------------------------------------------------------
	// actions.json
	// ------------------------------------------------------------------

	@Test
	void actionsJsonDeclaresExactlyTheHandledActionsAllReadOnly() {
		String json = plugin.getActionsJson();
		assertNotNull(json, "actions.json should be found next to the plugin class");
		JsonNode root = mapper.readTree(json);
		assertEquals(1, root.get("schemaVersion").asInt());

		var ids = new HashSet<String>();
		for (JsonNode action : root.get("actions")) {
			String id = action.get("id").asString();
			ids.add(id);
			assertTrue(id.matches("[a-z0-9._-]{1,48}") && id.contains("."), id);
			assertEquals("read", action.get("class").asString(), id);
			String doc = action.get("doc").asString();
			assertNotNull(MongoFilePanelPlugin.class.getResource(doc), id + ": missing doc " + doc);
			checkSchema(action.get("input"), id + ".input");
			checkSchema(action.get("output"), id + ".output");
		}
		assertEquals(MongoActions.IDS, ids);
	}

	private static void checkSchema(JsonNode schema, String where) {
		for (var entry : schema.properties()) {
			assertTrue(KEYWORDS.contains(entry.getKey()), where + ": '" + entry.getKey() + "' is not supported");
		}
		for (var property : schema.path("properties").properties()) {
			checkSchema(property.getValue(), where + "." + property.getKey());
		}
		for (JsonNode name : schema.path("required")) {
			assertTrue(schema.path("properties").has(name.asString()), where + ": required " + name);
		}
		if (schema.has("items")) {
			checkSchema(schema.get("items"), where + "[]");
		}
	}

	// ------------------------------------------------------------------
	// Listing
	// ------------------------------------------------------------------

	@Test
	void listsConnectionsWithoutSecrets() {
		context.credentials.secrets.put(profile.getId(), "s3cret");
		Map<String, Object> result = ok(MongoActions.CONNECTIONS_LIST, Map.of());
		List<Map<String, Object>> connections = get(result, "connections");
		assertEquals(1, connections.size());
		assertEquals("Dev", connections.get(0).get("name"));
		assertEquals("mongodb://localhost:27017", connections.get(0).get("hosts"));
		assertEquals("none", connections.get(0).get("auth"));
		assertFalse(String.valueOf(result).contains("s3cret"));
		assertEquals(0, opened, "listing saved connections connects to nothing");
	}

	@Test
	void listsDatabasesAndCollections() {
		List<Map<String, Object>> databases = get(ok(MongoActions.DATABASES_LIST, Map.of("connection", profile.getId())),
				"databases");
		assertEquals("shop", databases.get(0).get("name"));

		Map<String, Object> result = ok(MongoActions.COLLECTIONS_LIST,
				Map.of("connection", "dev", "database", "shop", "nameLike", "*ORDERS"));
		List<Map<String, Object>> collections = get(result, "collections");
		assertEquals(List.of("orders", "paidOrders"), collections.stream().map(c -> c.get("name")).toList());
		assertEquals(3L, collections.get(0).get("docs"));
		assertEquals("view", collections.get(1).get("type"));
		assertEquals("orders", collections.get(1).get("viewOn"));
		assertNull(collections.get(1).get("docs"));
		assertEquals(1, opened, "one session for every call");
	}

	@Test
	void unknownConnectionListsTheSavedOnes() {
		String message = error(MongoActions.DATABASES_LIST, Map.of("connection", "prod"));
		assertTrue(message.contains("No saved connection 'prod'") && message.contains("Dev"), message);
	}

	// ------------------------------------------------------------------
	// Schema, count, find, aggregate
	// ------------------------------------------------------------------

	@Test
	void schemaSamplesFieldsAndAddsIndexes() {
		Map<String, Object> result = ok(MongoActions.SCHEMA, shop("orders", Map.of("sampleSize", 50)));
		assertEquals(3, result.get("sampled"));
		List<Map<String, Object>> fields = get(result, "fields");
		Map<String, Object> sku = fields.stream().filter(f -> f.get("path").equals("items.sku")).findFirst().orElseThrow();
		assertEquals(Map.of("string", 3), sku.get("types"));
		List<Object> indexes = get(result, "indexes");
		assertEquals(1, indexes.size());
		assertTrue(session.pipelines.getLast().toString().contains("$sample"));
	}

	@Test
	void schemaOfAMissingCollectionSaysWhatToDo() {
		String message = error(MongoActions.SCHEMA, shop("nope", Map.of()));
		assertTrue(message.contains("mongo.collections.list"), message);
	}

	@Test
	void countPassesTheFilter() {
		Map<String, Object> result = ok(MongoActions.COUNT, shop("orders", Map.of("filter", Map.of("status", "failed"))));
		assertEquals(1L, result.get("count"));
	}

	@Test
	void findUnderstandsExtendedJsonAndReturnsIt() {
		ObjectId id = session.data.get("shop").get("orders").get(1).getObjectId("_id");
		Map<String, Object> result = ok(MongoActions.FIND,
				shop("orders", Map.of("filter", Map.of("_id", Map.of("$oid", id.toHexString())))));
		BsonDocument filter = (BsonDocument) session.filters.getLast();
		assertEquals(new BsonObjectId(id), filter.get("_id"));
		List<Map<String, Object>> documents = get(result, "documents");
		assertEquals(1, documents.size());
		assertEquals(Map.of("$oid", id.toHexString()), documents.get(0).get("_id"));
		assertEquals(1, result.get("returned"));
		assertEquals(false, result.get("truncated"));
	}

	@Test
	void findHonoursSortAndLimit() {
		Map<String, Object> result = ok(MongoActions.FIND,
				shop("orders", Map.of("sort", Map.of("total", -1), "limit", 2)));
		List<Map<String, Object>> documents = get(result, "documents");
		assertEquals(List.of(12, 11), documents.stream().map(d -> d.get("total")).toList());
	}

	@Test
	void oversizedDocumentComesBackAsAStandIn() {
		session.data.get("shop").get("orders").add(0, new Document("_id", "big").append("blob", "x".repeat(1_100_000)));
		Map<String, Object> result = ok(MongoActions.FIND, shop("orders", Map.of("sort", Map.of("_id", -1), "limit", 1)));
		List<Map<String, Object>> documents = get(result, "documents");
		assertEquals("big", documents.get(0).get("_id"));
		assertTrue(String.valueOf(documents.get(0).get("leftOut")).contains("characters"));
		assertEquals(true, result.get("truncated"));
	}

	@Test
	void aggregateRunsReadingPipelines() {
		Map<String, Object> result = ok(MongoActions.AGGREGATE,
				shop("orders", Map.of("pipeline", List.of(Map.of("$match", Map.of())), "limit", 2)));
		assertEquals(2, result.get("returned"));
		assertEquals(true, result.get("limitReached"));
	}

	@Test
	void aggregateRefusesOutAndMergeBeforeConnecting() {
		String message = error(MongoActions.AGGREGATE, shop("orders",
				Map.of("pipeline", List.of(Map.of("$match", Map.of()), Map.of("$out", "copy")))));
		assertTrue(message.contains("$out") && message.contains("only read"), message);
		message = error(MongoActions.AGGREGATE, shop("orders", Map.of("pipeline",
				List.of(Map.of("$facet", Map.of("a", List.of(Map.of("$merge", Map.of("into", "x")))))))));
		assertTrue(message.contains("$merge"), message);
		assertEquals(0, opened);
		assertTrue(session.pipelines.isEmpty());
	}

	@Test
	void badArgumentsAreExplained() {
		assertTrue(error(MongoActions.FIND, shop("orders", Map.of("limit", 5000))).contains("between 1 and 1000"));
		assertTrue(error(MongoActions.FIND, shop("orders", Map.of("filter", "status=1"))).contains("must be an object"));
		assertTrue(error(MongoActions.AGGREGATE, shop("orders", Map.of("pipeline", List.of("x"))))
				.contains("array of stage objects"));
		assertTrue(error(MongoActions.COUNT, Map.of("connection", "Dev", "database", "shop"))
				.contains("Missing argument 'collection'"));
	}

	// ------------------------------------------------------------------
	// Secrets and failures
	// ------------------------------------------------------------------

	@Test
	void storedSecretsAreUsedAndNeverPrompted() {
		var store = new ConnectionProfileStore(context.settings);
		var secured = store.add(MongoConnectionProfile.builder().name("Secured").connectionString("mongodb://h")
				.authType(AuthType.DEFAULT).username("app").build());
		context.credentials.secrets.put(secured.getId(), "pw");
		ok(MongoActions.DATABASES_LIST, Map.of("connection", "Secured"));
		assertEquals("pw", secretsSeen.getLast().main());
	}

	@Test
	void aMissingSecretWithNoDisplayFailsAndSaysWhy() {
		var store = new ConnectionProfileStore(context.settings);
		store.add(MongoConnectionProfile.builder().name("Secured").connectionString("mongodb://h")
				.authType(AuthType.DEFAULT).username("app").build());
		String message = error(MongoActions.DATABASES_LIST, Map.of("connection", "Secured"));
		assertTrue(message.contains("Remember"), message);
		assertEquals(0, opened);
	}

	@Test
	void refusedCredentialsDropTheSession() {
		ok(MongoActions.DATABASES_LIST, Map.of("connection", "Dev"));
		session.failure = new MongoSecurityException(MongoCredential.createCredential("u", "admin", new char[0]),
				"Exception authenticating");
		String message = error(MongoActions.DATABASES_LIST, Map.of("connection", "Dev"));
		assertTrue(message.startsWith("Dev: Authentication failed"), message);
		assertTrue(session.closed, "the refused session is closed so the next call connects afresh");
	}

	@Test
	void cancelledCallsStop() {
		var callback = new RecordingCallback();
		callback.cancelled.set(true);
		plugin.act(null, MongoActions.DATABASES_LIST, List.of(), null, new HashMap<>(Map.of("connection", "Dev")), callback);
		assertEquals("Cancelled.", callback.error);
	}
}
