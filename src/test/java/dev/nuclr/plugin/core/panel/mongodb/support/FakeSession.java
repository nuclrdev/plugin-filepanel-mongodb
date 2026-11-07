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
package dev.nuclr.plugin.core.panel.mongodb.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import com.mongodb.MongoClientSettings;

import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession;

/**
 * An in-memory {@link MongoSession}: databases of collections of documents. Filters
 * match top-level equality only and sorts honour one key, which is all the tests ask of it.
 */
public final class FakeSession implements MongoSession {

	public final Map<String, Map<String, List<Document>>> data = new LinkedHashMap<>();
	public final Map<String, CollectionEntry> entries = new LinkedHashMap<>();
	public final List<List<? extends Bson>> pipelines = new ArrayList<>();
	public final List<Bson> filters = new ArrayList<>();
	public RuntimeException failure;
	public boolean closed;
	public int pings;

	public static FakeSession sample() {
		var session = new FakeSession();
		var orders = new ArrayList<Document>();
		for (int i = 0; i < 3; i++) {
			orders.add(new Document("_id", new ObjectId(new Date(1_700_000_000_000L + i * 1000L)))
					.append("status", i == 0 ? "failed" : "paid")
					.append("total", 10 + i)
					.append("items", List.of(new Document("sku", "A" + i).append("qty", 1))));
		}
		session.put("shop", "orders", new CollectionEntry("orders", CollectionKind.COLLECTION, new Document()), orders);
		session.put("shop", "customers", new CollectionEntry("customers", CollectionKind.COLLECTION, new Document()),
				new ArrayList<>(List.of(new Document("_id", "c1").append("name", "Ada"),
						new Document("_id", 1).append("name", "Number one"),
						new Document("_id", "1").append("name", "String one"))));
		session.put("shop", "paidOrders", new CollectionEntry("paidOrders", CollectionKind.VIEW,
				new Document("viewOn", "orders").append("pipeline",
						List.of(new Document("$match", new Document("status", "paid"))))), new ArrayList<>());
		return session;
	}

	public void put(String database, String collection, CollectionEntry entry, List<Document> documents) {
		data.computeIfAbsent(database, d -> new LinkedHashMap<>()).put(collection, documents);
		entries.put(database + "." + collection, entry);
	}

	private void check() {
		if (failure != null) {
			throw failure;
		}
	}

	private List<Document> documents(String database, String collection) {
		return data.getOrDefault(database, Map.of()).getOrDefault(collection, List.of());
	}

	@Override
	public ServerInfo ping() {
		check();
		pings++;
		return new ServerInfo("8.0.4", "replica set");
	}

	@Override
	public List<DatabaseEntry> databases() {
		check();
		var list = new ArrayList<DatabaseEntry>();
		data.forEach((name, collections) -> list.add(new DatabaseEntry(name, 4096L * collections.size(), false)));
		return list;
	}

	@Override
	public List<CollectionEntry> collections(String database) {
		check();
		var list = new ArrayList<CollectionEntry>();
		for (String name : data.getOrDefault(database, Map.of()).keySet()) {
			list.add(entries.get(database + "." + name));
		}
		return list;
	}

	@Override
	public long estimatedCount(String database, String collection) {
		return documents(database, collection).size();
	}

	@Override
	public long count(String database, String collection, Bson filter) {
		check();
		filters.add(filter);
		return documents(database, collection).stream().filter(d -> matches(d, filter)).count();
	}

	@Override
	public List<Document> find(String database, String collection, Bson filter, Bson projection, Bson sort, int skip,
			int limit) {
		check();
		filters.add(filter);
		List<Document> matching = new ArrayList<>(documents(database, collection).stream()
				.filter(d -> matches(d, filter)).toList());
		if (sort != null) {
			BsonDocument s = sort.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
			String key = s.getFirstKey();
			if (!"$natural".equals(key)) {
				Comparator<Document> byKey = Comparator.comparing(d -> String.valueOf(d.get(key)));
				matching.sort(s.getNumber(key).intValue() < 0 ? byKey.reversed() : byKey);
			}
		}
		return matching.stream().skip(skip).limit(limit).toList();
	}

	@Override
	public List<Document> aggregate(String database, String collection, List<? extends Bson> pipeline, int limit) {
		check();
		pipelines.add(pipeline);
		BsonDocument first = pipeline.get(0).toBsonDocument(BsonDocument.class,
				MongoClientSettings.getDefaultCodecRegistry());
		if (first.containsKey("$collStats")) {
			return List.of(new Document("ns", database + "." + collection).append("storageStats",
					new Document("count", documents(database, collection).size())));
		}
		return documents(database, collection).stream().limit(limit).toList();
	}

	@Override
	public List<Document> indexes(String database, String collection) {
		check();
		return List.of(new Document("v", 2).append("key", new Document("_id", 1)).append("name", "_id_"));
	}

	@Override
	public Document command(String database, Bson command) {
		check();
		return new Document("db", database).append("collections", data.getOrDefault(database, Map.of()).size());
	}

	@Override
	public String defaultDatabase() {
		return null;
	}

	@Override
	public void close() {
		closed = true;
	}

	private static boolean matches(Document document, Bson filter) {
		if (filter == null) {
			return true;
		}
		BsonDocument f = filter.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
		for (String key : f.keySet()) {
			BsonDocument wrapped = new Document("v", document.get(key)).toBsonDocument();
			if (!f.get(key).equals(wrapped.get("v"))) {
				return false;
			}
		}
		return true;
	}
}
