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
import java.util.Map;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;

import tools.jackson.databind.ObjectMapper;

/**
 * Documents to and from text, in MongoDB's relaxed Extended JSON: the form mongosh and
 * Compass export, where a date reads {@code {"$date": "2026-01-02T03:04:05Z"}} and an
 * ObjectId {@code {"$oid": "..."}}, so no type is lost and plain numbers stay plain.
 */
public final class ExtJson {

	private static final JsonWriterSettings PRETTY = JsonWriterSettings.builder()
			.outputMode(JsonMode.RELAXED).indent(true).build();
	private static final JsonWriterSettings COMPACT = JsonWriterSettings.builder()
			.outputMode(JsonMode.RELAXED).build();
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ExtJson() {
	}

	/** A document as indented relaxed Extended JSON. */
	public static String pretty(Document document) {
		return document.toJson(PRETTY);
	}

	public static String compact(Document document) {
		return document.toJson(COMPACT);
	}

	/** A document as plain maps, lists and values, Extended JSON wrappers such as $oid kept. */
	public static Object plain(Document document) {
		return MAPPER.readValue(compact(document), Object.class);
	}

	/** Any BSON value (a list of documents, an ObjectId) as plain maps, lists and values. */
	public static Object plainValue(Object value) {
		return value == null ? null : ((Map<?, ?>) plain(new Document("v", value))).get("v");
	}

	/** An agent's argument object (a filter, projection, sort) as BSON; $oid, $date and the like understood. */
	public static BsonDocument document(Map<String, Object> value) {
		return BsonDocument.parse(MAPPER.writeValueAsString(value));
	}

	/** An agent's array of stage objects as BSON documents. */
	public static List<BsonDocument> documents(List<Object> value) {
		BsonArray array = BsonArray.parse(MAPPER.writeValueAsString(value));
		var documents = new ArrayList<BsonDocument>(array.size());
		for (BsonValue item : array) {
			if (!item.isDocument()) {
				throw new IllegalArgumentException("every item must be an object");
			}
			documents.add(item.asDocument());
		}
		return documents;
	}

	/**
	 * An {@code _id} as an Extended JSON value, telling types apart: {@code 1} and
	 * {@code "1"} differ here, unlike in {@link #idText}.
	 */
	public static String typedId(Object id) {
		String json = new Document("v", id).toJson(COMPACT);
		return json.substring(json.indexOf(':') + 1, json.length() - 1).strip();
	}

	/** An {@code _id} as text: an ObjectId's hex, a string as it is, anything else as Extended JSON. */
	public static String idText(Object id) {
		if (id == null) {
			return "null";
		}
		if (id instanceof ObjectId || id instanceof String || id instanceof Number || id instanceof Boolean) {
			return id.toString();
		}
		return typedId(id);
	}
}
