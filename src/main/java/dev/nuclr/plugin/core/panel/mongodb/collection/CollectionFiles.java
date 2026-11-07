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
package dev.nuclr.plugin.core.panel.mongodb.collection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bson.Document;

import dev.nuclr.plugin.core.panel.mongodb.client.ExtJson;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionEntry;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionKind;
import lombok.extern.slf4j.Slf4j;

/**
 * The read-only files of a collection folder, and of a database folder. A file the
 * server cannot produce (a privilege the user lacks, a command an older version does not
 * have) is left out rather than failing the folder.
 */
@Slf4j
public final class CollectionFiles {

	/** Documents {@code _schema.json} is learned from. */
	public static final int SCHEMA_SAMPLE = 100;

	public record VirtualFile(String name, String content) {
	}

	private CollectionFiles() {
	}

	/** The files of a collection, view or time series collection. */
	public static List<VirtualFile> build(MongoSession session, String database, CollectionEntry entry,
			AtomicBoolean cancelled) {
		var files = new ArrayList<VirtualFile>();
		String name = entry.name();
		boolean view = entry.kind() == CollectionKind.VIEW;

		var options = new Document("name", name).append("type", entry.kind().label())
				.append("options", entry.options() == null ? new Document() : entry.options());
		files.add(new VirtualFile("_options.json", ExtJson.pretty(options) + "\n"));

		if (!view && !isCancelled(cancelled)) {
			tryAdd(files, "_indexes.json", () -> prettyArray(session.indexes(database, name)));
		}
		if (!view && !isCancelled(cancelled)) {
			tryAdd(files, "_stats.json", () -> ExtJson.pretty(stats(session, database, name)) + "\n");
		}
		if (!isCancelled(cancelled)) {
			tryAdd(files, "_schema.json", () -> ExtJson.pretty(SchemaSampler.analyze(
					session.aggregate(database, name, List.of(new Document("$sample", new Document("size", SCHEMA_SAMPLE))),
							SCHEMA_SAMPLE)).toDocument()) + "\n");
		}
		return files;
	}

	/** The files of a database folder. */
	public static List<VirtualFile> buildForDatabase(MongoSession session, String database) {
		var files = new ArrayList<VirtualFile>();
		tryAdd(files, "_stats.json", () -> ExtJson.pretty(session.command(database, new Document("dbStats", 1))) + "\n");
		return files;
	}

	/**
	 * Storage statistics: the {@code $collStats} stage where the server has it (3.4+;
	 * the {@code collStats} command is deprecated since 6.2), else the command.
	 */
	static Document stats(MongoSession session, String database, String collection) {
		try {
			List<Document> stats = session.aggregate(database, collection,
					List.of(new Document("$collStats", new Document("storageStats", new Document()))), 1);
			if (!stats.isEmpty()) {
				return stats.get(0);
			}
		} catch (RuntimeException e) {
			log.debug("$collStats refused on {}.{}: {}", database, collection, e.getMessage());
		}
		return session.command(database, new Document("collStats", collection));
	}

	@FunctionalInterface
	private interface Producer {
		String produce();
	}

	private static void tryAdd(List<VirtualFile> files, String name, Producer producer) {
		try {
			files.add(new VirtualFile(name, producer.produce()));
		} catch (RuntimeException e) {
			log.info("Left out {}: {}", name, e.getMessage());
		}
	}

	/** Documents as an indented JSON array. */
	public static String prettyArray(List<Document> documents) {
		if (documents.isEmpty()) {
			return "[]\n";
		}
		var text = new StringBuilder("[\n");
		for (int i = 0; i < documents.size(); i++) {
			text.append("  ").append(ExtJson.pretty(documents.get(i)).replace("\n", "\n  "));
			text.append(i < documents.size() - 1 ? ",\n" : "\n");
		}
		return text.append("]\n").toString();
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}
}
