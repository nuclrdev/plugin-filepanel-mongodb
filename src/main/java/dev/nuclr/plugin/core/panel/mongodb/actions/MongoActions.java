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
package dev.nuclr.plugin.core.panel.mongodb.actions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bson.BsonDocument;
import org.bson.Document;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.plugin.core.panel.mongodb.client.ErrorText;
import dev.nuclr.plugin.core.panel.mongodb.client.ExtJson;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionEntry;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionKind;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.DatabaseEntry;
import dev.nuclr.plugin.core.panel.mongodb.client.ReadOnlyGuard;
import dev.nuclr.plugin.core.panel.mongodb.collection.SchemaSampler;
import dev.nuclr.plugin.core.panel.mongodb.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnectionProfile;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs the actions this plugin declares in {@code actions.json}: listing saved
 * connections, databases and collections, sampling a collection's shape, counting,
 * finding and aggregating documents.
 *
 * <p>Every action is a read. {@link MongoSession} offers no write to call, and a
 * pipeline with a {@code $out} or {@code $merge} stage, the one way a read API can
 * write, is refused before it is sent (see {@link ReadOnlyGuard}).
 *
 * <p>Actions need no pane, selection or open resource, so they behave the same on the
 * headless plugin instance the commander keeps for them.
 *
 * <p>Error messages go back to whoever asked - often an agent - so they say what to
 * do next, not just what went wrong.
 */
@Slf4j
public final class MongoActions {

	/** Connects a saved profile. Production shares the plugin instance's registry and secret prompts. */
	@FunctionalInterface
	public interface Opener {

		/**
		 * @param profile the saved connection
		 * @return the session, or {@code null} if the user dismissed a secret prompt
		 * @throws Exception if no server can be reached or the credentials are refused
		 */
		MongoSession open(MongoConnectionProfile profile) throws Exception;
	}

	public static final String CONNECTIONS_LIST = "mongo.connections.list";
	public static final String DATABASES_LIST = "mongo.databases.list";
	public static final String COLLECTIONS_LIST = "mongo.collections.list";
	public static final String SCHEMA = "mongo.schema";
	public static final String COUNT = "mongo.count";
	public static final String FIND = "mongo.find";
	public static final String AGGREGATE = "mongo.aggregate";

	/** Every action id this class handles; must match actions.json exactly. */
	public static final Set<String> IDS = Set.of(CONNECTIONS_LIST, DATABASES_LIST, COLLECTIONS_LIST, SCHEMA, COUNT,
			FIND, AGGREGATE);

	static final int DEFAULT_COLLECTION_LIMIT = 500;
	static final int MAX_COLLECTION_LIMIT = 5000;
	/** Collections above which mongo.collections.list skips the per-collection counts. */
	static final int COUNT_LIMIT = 200;
	static final int DEFAULT_SAMPLE = 100;
	static final int MAX_SAMPLE = 1000;
	static final int DEFAULT_LIMIT = 20;
	static final int MAX_LIMIT = 1000;
	static final int DEFAULT_AGGREGATE_LIMIT = 100;
	static final int MAX_SKIP = 1_000_000;
	/** Rough budget for the documents of one answer, as JSON text; documents past it are dropped, with truncated set. */
	static final int MAX_DOCS_CHARS = 1_000_000;

	private static final long POLL_MILLIS = 250;

	private final ConnectionProfileStore store;
	private final Opener opener;
	private final Consumer<String> forget;

	/**
	 * @param store  where saved connection profiles are read from
	 * @param opener connects a profile
	 * @param forget drops a profile's session after the server refused its credentials, so
	 *               the next call connects (and asks) afresh
	 */
	public MongoActions(ConnectionProfileStore store, Opener opener, Consumer<String> forget) {
		this.store = store;
		this.opener = opener;
		this.forget = forget;
	}

	/**
	 * @param actionType the action id passed to {@code act}
	 * @return {@code true} if {@link #run} handles it
	 */
	public static boolean handles(String actionType) {
		return actionType != null && IDS.contains(actionType);
	}

	/**
	 * Run an action and report through the callback: {@code onResult} then
	 * {@code onComplete} on success, {@code onError} otherwise.
	 */
	public void run(String id, Map<String, Object> args, NuclrPluginCallback callback) {
		Map<String, Object> arguments = args == null ? Map.of() : args;
		try {
			Map<String, Object> result = switch (id) {
				case CONNECTIONS_LIST -> connectionsList();
				case DATABASES_LIST -> databasesList(arguments, callback);
				case COLLECTIONS_LIST -> collectionsList(arguments, callback);
				case SCHEMA -> schema(arguments, callback);
				case COUNT -> count(arguments, callback);
				case FIND -> find(arguments, callback);
				case AGGREGATE -> aggregate(arguments, callback);
				default -> throw new ActionException("Unknown action '" + id + "'.");
			};
			callback.onResult(result);
			callback.onComplete();
		} catch (ActionException e) {
			callback.onError(e.getMessage(), e);
		} catch (Exception e) {
			log.warn("Action {} failed: {}", id, e.toString());
			callback.onError(id + " failed: " + ErrorText.describe(e), e);
		}
	}

	// =========================================================================
	// mongo.connections.list
	// =========================================================================

	private Map<String, Object> connectionsList() {
		var connections = new ArrayList<Map<String, Object>>();
		for (MongoConnectionProfile profile : store.loadAll()) {
			var connection = new LinkedHashMap<String, Object>();
			connection.put("id", profile.getId());
			connection.put("name", profile.getName());
			connection.put("hosts", profile.getDisplayTarget());
			connection.put("auth", authLabel(profile));
			connection.put("username", blankToNull(profile.getUsername()));
			connections.add(connection);
		}
		connections.sort((a, b) -> String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name"))));
		return Map.of("connections", connections);
	}

	private static String authLabel(MongoConnectionProfile profile) {
		return switch (profile.getEffectiveAuthType()) {
			case NONE -> "none";
			case DEFAULT -> "password";
			default -> profile.getEffectiveAuthType().mechanism();
		};
	}

	// =========================================================================
	// mongo.databases.list
	// =========================================================================

	private Map<String, Object> databasesList(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		MongoConnectionProfile profile = profile(text(args, "connection", true));
		List<DatabaseEntry> entries = call(profile, callback, MongoSession::databases);
		var databases = new ArrayList<Map<String, Object>>();
		for (DatabaseEntry entry : entries) {
			var database = new LinkedHashMap<String, Object>();
			database.put("name", entry.name());
			database.put("sizeBytes", entry.sizeOnDisk() < 0 ? null : entry.sizeOnDisk());
			database.put("empty", entry.empty());
			databases.add(database);
		}
		return Map.of("databases", databases);
	}

	// =========================================================================
	// mongo.collections.list
	// =========================================================================

	private Map<String, Object> collectionsList(Map<String, Object> args, NuclrPluginCallback callback)
			throws Exception {
		MongoConnectionProfile profile = profile(text(args, "connection", true));
		String database = text(args, "database", true);
		String nameLike = text(args, "nameLike", false);
		int limit = integer(args, "limit", DEFAULT_COLLECTION_LIMIT, 1, MAX_COLLECTION_LIMIT);
		Pattern pattern = nameLike == null ? null : wildcard(nameLike);

		List<Map<String, Object>> collections = call(profile, callback, session -> {
			var matching = new ArrayList<CollectionEntry>();
			for (CollectionEntry entry : session.collections(database)) {
				if (pattern == null || pattern.matcher(entry.name()).matches()) {
					matching.add(entry);
				}
			}
			matching.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
			boolean count = matching.size() <= COUNT_LIMIT;
			var rows = new ArrayList<Map<String, Object>>();
			for (CollectionEntry entry : matching) {
				var row = new LinkedHashMap<String, Object>();
				row.put("name", entry.name());
				row.put("type", entry.kind().label());
				long docs = count && entry.kind() != CollectionKind.VIEW && rows.size() < limit
						? session.estimatedCount(database, entry.name())
						: -1;
				row.put("docs", docs < 0 ? null : docs);
				Object viewOn = entry.options().get("viewOn");
				if (viewOn != null) {
					row.put("viewOn", viewOn);
				}
				if (entry.timeField() != null) {
					row.put("timeField", entry.timeField());
				}
				if (entry.capped()) {
					row.put("capped", true);
				}
				rows.add(row);
			}
			return rows;
		});
		int total = collections.size();
		boolean truncated = total > limit;
		var result = new LinkedHashMap<String, Object>();
		result.put("database", database);
		result.put("collections", truncated ? new ArrayList<>(collections.subList(0, limit)) : collections);
		result.put("total", total);
		result.put("truncated", truncated);
		return result;
	}

	/** A {@code *} pattern, matched ignoring case, as a regex. */
	static Pattern wildcard(String glob) {
		String regex = Pattern.compile("\\*").splitAsStream(glob).map(Pattern::quote)
				.collect(Collectors.joining(".*"));
		if (glob.endsWith("*")) {
			regex += ".*";
		}
		return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	// =========================================================================
	// mongo.schema
	// =========================================================================

	private Map<String, Object> schema(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		MongoConnectionProfile profile = profile(text(args, "connection", true));
		String database = text(args, "database", true);
		String collection = text(args, "collection", true);
		int sampleSize = integer(args, "sampleSize", DEFAULT_SAMPLE, 1, MAX_SAMPLE);

		return call(profile, callback, session -> {
			CollectionEntry entry = null;
			for (CollectionEntry e : session.collections(database)) {
				if (e.name().equals(collection)) {
					entry = e;
				}
			}
			if (entry == null) {
				throw new ActionException("No collection '" + collection + "' in database '" + database + "' of "
						+ profile.getName() + ". Use mongo.collections.list to see what there is.");
			}
			List<Document> sample = session.aggregate(database, collection,
					List.of(new Document("$sample", new Document("size", sampleSize))), sampleSize);
			SchemaSampler.Result analysis = SchemaSampler.analyze(sample);

			var result = new LinkedHashMap<String, Object>();
			result.put("database", database);
			result.put("collection", collection);
			result.put("type", entry.kind().label());
			result.put("sampled", analysis.sampled());
			result.put("fields", ExtJson.plainValue(analysis.toDocument().get("fields")));
			if (analysis.truncated()) {
				result.put("fieldsTruncated", true);
			}
			if (entry.kind() != CollectionKind.VIEW) {
				var indexes = new ArrayList<Object>();
				for (Document index : session.indexes(database, collection)) {
					indexes.add(ExtJson.plain(index));
				}
				result.put("indexes", indexes);
			}
			Object validator = entry.options().get("validator");
			if (validator instanceof Document v) {
				result.put("validator", ExtJson.plain(v));
			}
			if (entry.kind() == CollectionKind.VIEW) {
				result.put("viewOn", entry.options().get("viewOn"));
				result.put("viewPipeline", ExtJson.plainValue(entry.options().get("pipeline")));
			}
			if (entry.timeField() != null) {
				result.put("timeField", entry.timeField());
			}
			return result;
		});
	}

	// =========================================================================
	// mongo.count
	// =========================================================================

	private Map<String, Object> count(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		MongoConnectionProfile profile = profile(text(args, "connection", true));
		String database = text(args, "database", true);
		String collection = text(args, "collection", true);
		BsonDocument filter = bson(args, "filter");

		long count = call(profile, callback, session -> session.count(database, collection, filter));
		var result = new LinkedHashMap<String, Object>();
		result.put("database", database);
		result.put("collection", collection);
		result.put("count", count);
		return result;
	}

	// =========================================================================
	// mongo.find
	// =========================================================================

	private Map<String, Object> find(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		MongoConnectionProfile profile = profile(text(args, "connection", true));
		String database = text(args, "database", true);
		String collection = text(args, "collection", true);
		BsonDocument filter = bson(args, "filter");
		BsonDocument projection = bson(args, "projection");
		BsonDocument sort = bson(args, "sort");
		int limit = integer(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
		int skip = integer(args, "skip", 0, 0, MAX_SKIP);

		List<Document> documents = call(profile, callback,
				session -> session.find(database, collection, filter, projection, sort, skip, limit));
		var result = new LinkedHashMap<String, Object>();
		result.put("database", database);
		result.put("collection", collection);
		putDocuments(result, documents, callback);
		return result;
	}

	// =========================================================================
	// mongo.aggregate
	// =========================================================================

	private Map<String, Object> aggregate(Map<String, Object> args, NuclrPluginCallback callback) throws Exception {
		MongoConnectionProfile profile = profile(text(args, "connection", true));
		String database = text(args, "database", true);
		String collection = text(args, "collection", true);
		List<Object> stages = list(args, "pipeline");
		int limit = integer(args, "limit", DEFAULT_AGGREGATE_LIMIT, 1, MAX_LIMIT);

		List<BsonDocument> pipeline;
		try {
			pipeline = ExtJson.documents(stages);
		} catch (RuntimeException e) {
			throw new ActionException("Argument 'pipeline' must be an array of stage objects: " + e.getMessage());
		}
		String writing = ReadOnlyGuard.writingStage(pipeline);
		if (writing != null) {
			throw new ActionException("Refused: the pipeline has a " + writing + " stage, which writes to a collection. "
					+ "These tools only read; leave the stage out and the results come back here instead.");
		}
		List<Document> documents = call(profile, callback,
				session -> session.aggregate(database, collection, pipeline, limit));
		var result = new LinkedHashMap<String, Object>();
		result.put("database", database);
		result.put("collection", collection);
		putDocuments(result, documents, callback);
		if (documents.size() >= limit) {
			result.put("limitReached", true);
		}
		return result;
	}

	/** {@code documents}, {@code returned} and {@code truncated}, keeping to {@link #MAX_DOCS_CHARS}. */
	private static void putDocuments(Map<String, Object> result, List<Document> documents,
			NuclrPluginCallback callback) throws ActionException {
		var rows = new ArrayList<Object>();
		long budget = MAX_DOCS_CHARS;
		boolean truncated = false;
		for (Document document : documents) {
			checkCancelled(callback);
			String json = ExtJson.compact(document);
			if (json.length() > budget) {
				// Stop at the first document that does not fit. When not even one does,
				// say so with a stand-in that keeps its _id but leaves out the rest.
				truncated = true;
				if (rows.isEmpty()) {
					var standIn = new LinkedHashMap<String, Object>();
					standIn.put("_id", ExtJson.plainValue(document.get("_id")));
					standIn.put("leftOut", json.length() + " characters; ask for fewer fields with a projection");
					rows.add(standIn);
				}
				break;
			}
			budget -= json.length();
			rows.add(ExtJson.plain(document));
		}
		result.put("documents", rows);
		result.put("returned", rows.size());
		result.put("truncated", truncated);
	}

	// =========================================================================
	// Connections and calls
	// =========================================================================

	@FunctionalInterface
	private interface SessionWork<T> {
		T run(MongoSession session) throws Exception;
	}

	/**
	 * Run {@code work} against the profile's servers on a thread of its own, so a Cancel
	 * from the caller is answered at once; the abandoned request is interrupted.
	 */
	private <T> T call(MongoConnectionProfile profile, NuclrPluginCallback callback, SessionWork<T> work)
			throws Exception {
		MongoSession session = connect(profile);
		checkCancelled(callback);
		var task = new FutureTask<T>(() -> work.run(session));
		Thread.ofVirtual().name("mongo-action").start(task);
		while (true) {
			try {
				return task.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
			} catch (TimeoutException e) {
				if (callback.isCancelled()) {
					task.cancel(true);
					throw new ActionException("Cancelled.");
				}
			} catch (ExecutionException e) {
				Throwable cause = e.getCause();
				if (cause instanceof ActionException action) {
					throw action;
				}
				if (ErrorText.isAuthenticationFailure(cause)) {
					forget.accept(profile.getId());
				}
				throw new ActionException(profile.getName() + ": " + ErrorText.describe(cause), cause);
			}
		}
	}

	private MongoSession connect(MongoConnectionProfile profile) throws ActionException {
		MongoSession session;
		try {
			session = opener.open(profile);
		} catch (Exception e) {
			throw new ActionException("Cannot connect to " + profile.getName() + ": " + ErrorText.describe(e), e);
		}
		if (session == null) {
			throw new ActionException("Not connected to " + profile.getName() + ": it needs a password or other "
					+ "secret, none is stored and the prompt was dismissed. Ask the user to enter it, or to open the "
					+ "connection once in the MongoDB panel with 'Remember' ticked.");
		}
		return session;
	}

	/** Find a saved connection by id, or by name ignoring case. */
	MongoConnectionProfile profile(String reference) throws ActionException {
		List<MongoConnectionProfile> profiles = store.loadAll();
		for (MongoConnectionProfile profile : profiles) {
			if (reference.equals(profile.getId())) {
				return profile;
			}
		}
		List<MongoConnectionProfile> byName = profiles.stream()
				.filter(profile -> reference.equalsIgnoreCase(profile.getName()))
				.toList();
		if (byName.size() == 1) {
			return byName.get(0);
		}
		String names = profiles.stream().map(MongoConnectionProfile::getName).collect(Collectors.joining(", "));
		if (byName.isEmpty()) {
			throw new ActionException("No saved connection '" + reference + "'. Saved connections: "
					+ (names.isEmpty() ? "none - ask the user to add one in the MongoDB panel (F7)." : names + "."));
		}
		throw new ActionException("'" + reference + "' matches " + byName.size()
				+ " saved connections; use the id from mongo.connections.list instead.");
	}

	// =========================================================================
	// Shared helpers
	// =========================================================================

	private static void checkCancelled(NuclrPluginCallback callback) throws ActionException {
		if (callback.isCancelled()) {
			throw new ActionException("Cancelled.");
		}
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text;
	}

	/** An object argument as BSON, {@code null} when absent; Extended JSON such as {@code {"$oid": ...}} understood. */
	static BsonDocument bson(Map<String, Object> args, String name) throws ActionException {
		Map<String, Object> value = object(args, name);
		if (value == null) {
			return null;
		}
		try {
			return ExtJson.document(value);
		} catch (RuntimeException e) {
			throw new ActionException("Argument '" + name + "' is not a valid document: " + e.getMessage());
		}
	}

	static String text(Map<String, Object> args, String name, boolean required) throws ActionException {
		Object value = args.get(name);
		if (value instanceof String text && !text.isBlank()) {
			return text;
		}
		if (value != null && !(value instanceof String)) {
			throw new ActionException("Argument '" + name + "' must be a string.");
		}
		if (required) {
			throw new ActionException("Missing argument '" + name + "'.");
		}
		return null;
	}

	static int integer(Map<String, Object> args, String name, int defaultValue, int min, int max)
			throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return defaultValue;
		}
		if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
			throw new ActionException("Argument '" + name + "' must be a whole number.");
		}
		long whole = number.longValue();
		if (whole < min || whole > max) {
			throw new ActionException("Argument '" + name + "' must be between " + min + " and " + max + ".");
		}
		return (int) whole;
	}

	@SuppressWarnings("unchecked")
	static List<Object> list(Map<String, Object> args, String name) throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			throw new ActionException("Missing argument '" + name + "'.");
		}
		if (!(value instanceof List<?> list)) {
			throw new ActionException("Argument '" + name + "' must be an array.");
		}
		return (List<Object>) list;
	}

	/** An object argument, {@code null} when absent. */
	@SuppressWarnings("unchecked")
	static Map<String, Object> object(Map<String, Object> args, String name) throws ActionException {
		Object value = args.get(name);
		if (value == null) {
			return null;
		}
		if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
			throw new ActionException("Argument '" + name + "' must be an object.");
		}
		return (Map<String, Object>) map;
	}

	/** A failure whose message is meant for the caller as it stands. */
	static final class ActionException extends Exception {

		private static final long serialVersionUID = 1L;

		ActionException(String message) {
			super(message);
		}

		ActionException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
