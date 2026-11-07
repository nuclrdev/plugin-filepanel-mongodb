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

import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

/**
 * The reads the panel and the agent actions make, and nothing else: there is no insert,
 * update, delete or drop here to call by mistake. {@link DriverSession} runs them with
 * the MongoDB driver; tests answer them from memory.
 *
 * <p>Every method may block on the network and throws the driver's runtime exceptions.
 */
public interface MongoSession extends AutoCloseable {

	/** What the server said about itself when asked. */
	record ServerInfo(String version, String topology) {
	}

	/** A database as {@code listDatabases} reports it; {@code sizeOnDisk} is -1 when unknown. */
	record DatabaseEntry(String name, long sizeOnDisk, boolean empty) {
	}

	/** Collection kinds as {@code listCollections} names them. */
	enum CollectionKind {
		COLLECTION, VIEW, TIMESERIES;

		public static CollectionKind of(String type) {
			if ("view".equals(type)) {
				return VIEW;
			}
			return "timeseries".equals(type) ? TIMESERIES : COLLECTION;
		}

		public String label() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/**
	 * A collection, view or time series collection.
	 *
	 * @param options its {@code options} document (validator, viewOn and pipeline,
	 *                timeseries, capped...), empty when the server would not say
	 */
	record CollectionEntry(String name, CollectionKind kind, Document options) {

		/** The time field of a time series collection, else {@code null}. */
		public String timeField() {
			Object timeseries = options == null ? null : options.get("timeseries");
			return timeseries instanceof Document d ? d.getString("timeField") : null;
		}

		public boolean capped() {
			return options != null && Boolean.TRUE.equals(options.get("capped"));
		}
	}

	/** Checks the connection and the credentials with a {@code ping} and reads the version. */
	ServerInfo ping();

	/** The databases the user may see, the connection string's database included. */
	List<DatabaseEntry> databases();

	List<CollectionEntry> collections(String database);

	/** The count from metadata, cheap; -1 when the server cannot tell (a view). */
	long estimatedCount(String database, String collection);

	/** An exact count of the documents matching {@code filter} ({@code null}: all). */
	long count(String database, String collection, Bson filter);

	/**
	 * @param filter     {@code null} matches everything
	 * @param projection {@code null} returns whole documents
	 * @param sort       {@code null} leaves the server's order
	 */
	List<Document> find(String database, String collection, Bson filter, Bson projection, Bson sort, int skip,
			int limit);

	/** Runs a pipeline and returns at most {@code limit} results. */
	List<Document> aggregate(String database, String collection, List<? extends Bson> pipeline, int limit);

	List<Document> indexes(String database, String collection);

	/** A read-only command such as {@code dbStats}. */
	Document command(String database, Bson command);

	/** The database the connection string names, or {@code null}. */
	String defaultDatabase();

	@Override
	void close();
}
