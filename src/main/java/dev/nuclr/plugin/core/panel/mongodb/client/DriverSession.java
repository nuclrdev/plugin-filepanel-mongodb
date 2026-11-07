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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.MongoCommandException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.CountOptions;

import dev.nuclr.plugin.core.panel.mongodb.connection.SshTunnel;
import lombok.extern.slf4j.Slf4j;

/** {@link MongoSession} over a {@link MongoClient}, and the SSH tunnel under it if any. */
@Slf4j
public final class DriverSession implements MongoSession {

	/** Server-side limit for any one read, so a careless query cannot run for hours. */
	static final long MAX_TIME_SECONDS = 60;

	/** The server's code for a user lacking a privilege. */
	static final int UNAUTHORIZED = 13;

	private final MongoClient client;
	private final String defaultDatabase;
	private final SshTunnel tunnel;

	public DriverSession(MongoClient client, String defaultDatabase, SshTunnel tunnel) {
		this.client = client;
		this.defaultDatabase = defaultDatabase;
		this.tunnel = tunnel;
	}

	@Override
	public ServerInfo ping() {
		MongoDatabase admin = client.getDatabase("admin");
		admin.runCommand(new Document("ping", 1));
		String version = "";
		try {
			version = admin.runCommand(new Document("buildInfo", 1)).get("version", "");
		} catch (RuntimeException e) {
			log.debug("buildInfo refused: {}", e.getMessage());
		}
		String topology = client.getClusterDescription().getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
		return new ServerInfo(version, topology);
	}

	@Override
	public List<DatabaseEntry> databases() {
		var databases = new ArrayList<DatabaseEntry>();
		try {
			for (Document d : client.listDatabases().authorizedDatabasesOnly(true)) {
				Number size = d.get("sizeOnDisk", Number.class);
				databases.add(new DatabaseEntry(d.getString("name"), size == null ? -1 : size.longValue(),
						Boolean.TRUE.equals(d.getBoolean("empty"))));
			}
		} catch (MongoCommandException e) {
			// A user of one database may not list them; the connection string names it.
			if (defaultDatabase == null || e.getErrorCode() != UNAUTHORIZED) {
				throw e;
			}
		}
		if (defaultDatabase != null && databases.stream().noneMatch(d -> d.name().equals(defaultDatabase))) {
			databases.add(new DatabaseEntry(defaultDatabase, -1, false));
		}
		return databases;
	}

	@Override
	public List<CollectionEntry> collections(String database) {
		MongoDatabase db = client.getDatabase(database);
		var collections = new ArrayList<CollectionEntry>();
		try {
			for (Document d : db.listCollections()) {
				if (isSystem(d.getString("name"))) {
					continue;
				}
				Document options = d.get("options", Document.class);
				collections.add(new CollectionEntry(d.getString("name"), CollectionKind.of(d.getString("type")),
						options == null ? new Document() : options));
			}
		} catch (MongoCommandException e) {
			if (e.getErrorCode() != UNAUTHORIZED) {
				throw e;
			}
			// Without listCollections, a user may still list the ones it can read, by name.
			for (String name : db.listCollectionNames().authorizedCollections(true)) {
				if (isSystem(name)) {
					continue;
				}
				collections.add(new CollectionEntry(name, CollectionKind.COLLECTION, new Document()));
			}
		}
		return collections;
	}

	/**
	 * MongoDB's own bookkeeping: {@code system.views}, the {@code system.buckets.*} behind
	 * time series collections, {@code system.profile}. Left out, as Compass does.
	 */
	static boolean isSystem(String name) {
		return name == null || name.startsWith("system.");
	}

	@Override
	public long estimatedCount(String database, String collection) {
		try {
			return client.getDatabase(database).getCollection(collection).estimatedDocumentCount();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	@Override
	public long count(String database, String collection, Bson filter) {
		return client.getDatabase(database).getCollection(collection).countDocuments(
				filter == null ? new Document() : filter, new CountOptions().maxTime(MAX_TIME_SECONDS, TimeUnit.SECONDS));
	}

	@Override
	public List<Document> find(String database, String collection, Bson filter, Bson projection, Bson sort, int skip,
			int limit) {
		FindIterable<Document> find = client.getDatabase(database).getCollection(collection)
				.find(filter == null ? new Document() : filter)
				.maxTime(MAX_TIME_SECONDS, TimeUnit.SECONDS)
				.limit(limit);
		if (projection != null) {
			find.projection(projection);
		}
		if (sort != null) {
			find.sort(sort);
		}
		if (skip > 0) {
			find.skip(skip);
		}
		return find.into(new ArrayList<>());
	}

	@Override
	public List<Document> aggregate(String database, String collection, List<? extends Bson> pipeline, int limit) {
		var results = new ArrayList<Document>();
		try (MongoCursor<Document> cursor = client.getDatabase(database).getCollection(collection)
				.aggregate(pipeline)
				.maxTime(MAX_TIME_SECONDS, TimeUnit.SECONDS)
				.batchSize(Math.min(limit, 1000))
				.iterator()) {
			while (results.size() < limit && cursor.hasNext()) {
				results.add(cursor.next());
			}
		}
		return results;
	}

	@Override
	public List<Document> indexes(String database, String collection) {
		return client.getDatabase(database).getCollection(collection).listIndexes().into(new ArrayList<>());
	}

	@Override
	public Document command(String database, Bson command) {
		return client.getDatabase(database).runCommand(command);
	}

	@Override
	public String defaultDatabase() {
		return defaultDatabase;
	}

	@Override
	public void close() {
		try {
			client.close();
		} finally {
			if (tunnel != null) {
				tunnel.close();
			}
		}
	}
}
