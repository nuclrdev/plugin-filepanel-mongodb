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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionEntry;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionKind;
import dev.nuclr.plugin.core.panel.mongodb.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.CredentialStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnectionProfile;
import dev.nuclr.plugin.core.panel.mongodb.resource.MongoResource;
import dev.nuclr.plugin.core.panel.mongodb.resource.ResourceKind;
import dev.nuclr.plugin.core.panel.mongodb.support.FakeContext;
import dev.nuclr.plugin.core.panel.mongodb.support.FakeSession;

class MongoFilePanelPluginTest {

	private FakeSession session;
	private FakeContext context;
	private MongoFilePanelPlugin plugin;
	private int opened;

	@BeforeEach
	void setUp() {
		session = FakeSession.sample();
		context = new FakeContext();
		plugin = new MongoFilePanelPlugin((profile, secrets) -> {
			opened++;
			return session;
		});
		plugin.preinit(context);
		plugin.init();
	}

	@AfterEach
	void tearDown() {
		plugin.unload();
	}

	private MongoConnectionProfile saveProfile() {
		return new ConnectionProfileStore(context.settings).add(MongoConnectionProfile.builder()
				.name("Dev").connectionString("mongodb://localhost:27017").build());
	}

	private List<NuclrResource> open(NuclrResource resource) {
		return plugin.openResource(resource, new AtomicBoolean()).getEntries();
	}

	private static MongoResource named(List<NuclrResource> entries, String name) {
		return (MongoResource) entries.stream().filter(e -> e.getName().equals(name)).findFirst()
				.orElseThrow(() -> new AssertionError(name + " not in " + entries.stream().map(NuclrResource::getName).toList()));
	}

	private static String text(NuclrResource resource) throws Exception {
		try (InputStream in = resource.openInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void walksFromTheRootToADocument() throws Exception {
		MongoConnectionProfile profile = saveProfile();
		List<NuclrResource> root = open(MongoResource.explorerRoot());
		MongoResource connection = named(root, "Dev");
		assertEquals("mongodb://localhost:27017", connection.getMetadata().get("Hosts"));

		List<NuclrResource> databases = open(connection);
		assertEquals("..", databases.get(0).getName());
		MongoResource shop = named(databases, "shop");
		assertEquals(ResourceKind.DATABASE, shop.getKind());

		List<NuclrResource> collections = open(shop);
		assertEquals("Dev / shop", plugin.getCurrentLocationDisplayText());
		assertEquals(List.of("..", "customers", "orders", "paidOrders", "_stats.json"),
				collections.stream().map(NuclrResource::getName).toList());
		assertEquals("3", named(collections, "orders").getMetadata().get("Docs"));
		assertEquals("view", named(collections, "paidOrders").getMetadata().get("Type"));

		List<NuclrResource> orders = open(named(collections, "orders"));
		assertEquals(List.of("..", "docs", "_options.json", "_indexes.json", "_stats.json", "_schema.json"),
				orders.stream().map(NuclrResource::getName).toList());
		assertTrue(text(named(orders, "_indexes.json")).contains("\"_id_\""));
		assertTrue(text(named(orders, "_schema.json")).contains("\"items.sku\""));

		List<NuclrResource> docs = open(named(orders, "docs"));
		assertEquals("..", docs.get(0).getName());
		MongoResource newest = (MongoResource) docs.get(1);
		ObjectId newestId = session.data.get("shop").get("orders").get(2).getObjectId("_id");
		assertEquals(newestId.toHexString() + ".json", newest.getName());
		assertEquals(newestId.getDate().toInstant().toString(), newest.getMetadata().get("Timestamp"));
		String json = text(newest);
		assertTrue(json.contains("\"$oid\": \"" + newestId.toHexString() + "\""), json);
		assertNull(newest.getPath(), "remote resources carry no local path");
		assertEquals(profile.getId(), newest.getProfileId());
		assertEquals(1, opened);
	}

	@Test
	void viewsGetNoIndexesOrStats() {
		saveProfile();
		MongoResource shop = named(open(named(open(MongoResource.explorerRoot()), "Dev")), "shop");
		List<NuclrResource> view = open(named(open(shop), "paidOrders"));
		assertEquals(List.of("..", "docs", "_options.json", "_schema.json"),
				view.stream().map(NuclrResource::getName).toList());
	}

	@Test
	void idsOfDifferentTypesWithTheSameTextGetDifferentNames() {
		MongoConnectionProfile profile = saveProfile();
		List<NuclrResource> docs = open(MongoResource.docs(profile.getId(), "shop", "customers"));
		var names = docs.stream().skip(1).map(NuclrResource::getName).toList();
		assertEquals(3, names.size());
		assertEquals(3, names.stream().distinct().count(), names.toString());
		assertTrue(names.contains("c1.json"));
		var uuids = docs.stream().skip(1).map(NuclrResource::getUuid).distinct().count();
		assertEquals(3, uuids);
	}

	@Test
	void timeSeriesSortByTheirTimeFieldAndShowIt() {
		MongoConnectionProfile profile = saveProfile();
		var options = new Document("timeseries", new Document("timeField", "ts").append("metaField", "sensor"));
		var readings = new ArrayList<Document>();
		readings.add(new Document("_id", new ObjectId()).append("ts", new Date(1_000)).append("v", 1));
		readings.add(new Document("_id", new ObjectId()).append("ts", new Date(9_000)).append("v", 2));
		session.put("iot", "readings", new CollectionEntry("readings", CollectionKind.TIMESERIES, options), readings);

		assertEquals(new Document("ts", -1), MongoFilePanelPlugin.newestFirst(
				new CollectionEntry("readings", CollectionKind.TIMESERIES, options)));
		List<NuclrResource> docs = open(MongoResource.docs(profile.getId(), "iot", "readings"));
		assertEquals("1970-01-01T00:00:09Z", docs.get(1).getMetadata().get("Timestamp"));
		assertEquals(new Document("$natural", -1), MongoFilePanelPlugin.newestFirst(
				new CollectionEntry("log", CollectionKind.COLLECTION, new Document("capped", true))));
	}

	@Test
	void aCappedDocsListingSaysSo() {
		MongoConnectionProfile profile = saveProfile();
		var many = new ArrayList<Document>();
		for (int i = 0; i < MongoFilePanelPlugin.DOC_CAP + 5; i++) {
			many.add(new Document("_id", i));
		}
		session.put("big", "events", new CollectionEntry("events", CollectionKind.COLLECTION, new Document()), many);
		List<NuclrResource> docs = open(MongoResource.docs(profile.getId(), "big", "events"));
		NuclrResource marker = docs.getLast();
		assertEquals(MongoFilePanelPlugin.DOC_CAP + 2, docs.size());
		assertFalse(marker.isReadable());
		assertTrue(marker.getName().contains("505"), marker.getName());
	}

	@Test
	void aFailingServerLeavesAnEmptyListingAndNoSession() {
		saveProfile();
		session.failure = new IllegalStateException("down");
		List<NuclrResource> databases = open(named(open(MongoResource.explorerRoot()), "Dev"));
		assertTrue(databases.isEmpty());
		assertTrue(session.closed, "a session whose ping failed is closed, not kept");
	}

	@Test
	void deletingAConnectionForgetsItsSecrets() {
		MongoConnectionProfile profile = saveProfile();
		context.credentials.secrets.put(profile.getId(), "pw");
		context.credentials.secrets.put(CredentialStore.tlsKey(profile.getId()), "cert");
		context.credentials.secrets.put(CredentialStore.proxyKey(profile.getId()), "ssh");
		plugin.deleteConnections(List.of(profile.getId()));
		assertTrue(context.credentials.secrets.isEmpty());
		assertTrue(open(MongoResource.explorerRoot()).isEmpty());
	}

	@Test
	void menusOfferNewEditDeleteOnConnectionsAndCopyOnFiles() {
		MongoConnectionProfile profile = saveProfile();
		open(MongoResource.explorerRoot());
		var connectionMenu = plugin.menuItems(MongoResource.connection(profile)).stream().map(m -> m.getName()).toList();
		assertTrue(connectionMenu.containsAll(List.of("New", "Edit", "Delete")), connectionMenu.toString());
		var fileMenu = plugin.menuItems(MongoResource.file(profile.getId(), "shop", "orders", "_stats.json", "{}"))
				.stream().map(m -> m.getName()).toList();
		assertTrue(fileMenu.contains("Copy"), fileMenu.toString());
		assertNotEquals(plugin.uuid(), new MongoFilePanelPlugin().uuid(), "every instance has its own uuid");
	}

	@Test
	void docFileNamesStaySafe() {
		assertEquals("abc.json", MongoResource.docFileName("abc"));
		assertTrue(MongoResource.docFileName("a/b").matches("a_b~[0-9a-f]{8}\\.json"));
		assertTrue(MongoResource.docFileName("CON").matches("CON~[0-9a-f]{8}\\.json"));
		assertNotEquals(MongoResource.docFileName("a/b"), MongoResource.docFileName("a:b"));
	}
}
