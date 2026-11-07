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
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.Code;
import org.bson.types.CodeWithScope;
import org.bson.types.Decimal128;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.bson.types.ObjectId;
import org.bson.types.Symbol;

/**
 * What a collection's documents look like, learned from a sample, since MongoDB keeps
 * no schema: every field path with the BSON types seen there and how many of the
 * sampled documents have it.
 *
 * <p>Paths are dotted the way queries write them. Documents inside arrays add their
 * fields under the array's path ({@code items.sku}), as a query on {@code items.sku}
 * reaches them; what the arrays themselves hold is counted in {@code itemTypes}.
 */
public final class SchemaSampler {

	/** Field paths reported at most; a collection of free-form keys would have no end. */
	static final int MAX_FIELDS = 2000;

	/**
	 * One field path.
	 *
	 * @param documents sampled documents having it
	 * @param types     type name to occurrences
	 * @param itemTypes for arrays, type name of their items to occurrences; empty otherwise
	 */
	public record Field(String path, int documents, Map<String, Integer> types, Map<String, Integer> itemTypes) {
	}

	public record Result(int sampled, List<Field> fields, boolean truncated) {

		/** As a document, for {@code _schema.json}. */
		public Document toDocument() {
			var list = new ArrayList<Document>();
			for (Field field : fields) {
				var d = new Document("path", field.path())
						.append("frequency", sampled == 0 ? 0.0 : Math.round(1000.0 * field.documents() / sampled) / 1000.0)
						.append("types", new Document(new LinkedHashMap<>(field.types())));
				if (!field.itemTypes().isEmpty()) {
					d.append("itemTypes", new Document(new LinkedHashMap<>(field.itemTypes())));
				}
				list.add(d);
			}
			var root = new Document("sampled", sampled).append("fields", list);
			if (truncated) {
				root.append("truncated", true);
			}
			return root;
		}
	}

	private static final class Stats {
		int documents;
		final Map<String, Integer> types = new LinkedHashMap<>();
		final Map<String, Integer> itemTypes = new LinkedHashMap<>();
	}

	private SchemaSampler() {
	}

	public static Result analyze(Collection<Document> documents) {
		var stats = new LinkedHashMap<String, Stats>();
		boolean[] truncated = { false };
		for (Document document : documents) {
			Set<String> seen = new HashSet<>();
			walk(document, "", stats, seen, truncated);
			for (String path : seen) {
				stats.get(path).documents++;
			}
		}
		var fields = new ArrayList<Field>(stats.size());
		stats.forEach((path, s) -> fields.add(new Field(path, s.documents, s.types, s.itemTypes)));
		return new Result(documents.size(), fields, truncated[0]);
	}

	private static void walk(Document document, String prefix, Map<String, Stats> stats, Set<String> seen,
			boolean[] truncated) {
		for (Map.Entry<String, Object> entry : document.entrySet()) {
			String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
			Stats s = stats.get(path);
			if (s == null) {
				if (stats.size() >= MAX_FIELDS) {
					truncated[0] = true;
					continue;
				}
				s = new Stats();
				stats.put(path, s);
			}
			seen.add(path);
			Object value = entry.getValue();
			s.types.merge(typeName(value), 1, Integer::sum);
			if (value instanceof Document child) {
				walk(child, path, stats, seen, truncated);
			} else if (value instanceof List<?> list) {
				for (Object item : list) {
					s.itemTypes.merge(typeName(item), 1, Integer::sum);
					if (item instanceof Document child) {
						walk(child, path, stats, seen, truncated);
					}
				}
			}
		}
	}

	/** The type's name as {@code $type} and Compass spell it. */
	public static String typeName(Object value) {
		return switch (value) {
			case null -> "null";
			case String s -> "string";
			case Integer i -> "int";
			case Long l -> "long";
			case Double d -> "double";
			case Decimal128 d -> "decimal";
			case Boolean b -> "bool";
			case Date d -> "date";
			case ObjectId o -> "objectId";
			case Document d -> "object";
			case List<?> l -> "array";
			case Binary b -> "binData";
			case UUID u -> "binData";
			case byte[] b -> "binData";
			case BsonTimestamp t -> "timestamp";
			case org.bson.BsonRegularExpression r -> "regex";
			case java.util.regex.Pattern p -> "regex";
			case CodeWithScope c -> "javascriptWithScope";
			case Code c -> "javascript";
			case Symbol s -> "symbol";
			case MinKey m -> "minKey";
			case MaxKey m -> "maxKey";
			default -> value.getClass().getSimpleName();
		};
	}
}
