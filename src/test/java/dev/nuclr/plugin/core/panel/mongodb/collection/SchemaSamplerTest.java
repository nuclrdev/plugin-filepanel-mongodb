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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class SchemaSamplerTest {

	private static SchemaSampler.Field field(SchemaSampler.Result result, String path) {
		return result.fields().stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow();
	}

	@Test
	void countsTypesFrequencyAndNestedPaths() {
		var docs = List.of(
				new Document("_id", new ObjectId()).append("name", "a").append("at", new Date())
						.append("address", new Document("city", "Minsk"))
						.append("items", List.of(new Document("sku", "x"), new Document("sku", "y"))),
				new Document("_id", new ObjectId()).append("name", null).append("tags", List.of("t", 2)));
		var result = SchemaSampler.analyze(docs);

		assertEquals(2, result.sampled());
		assertEquals(Map.of("string", 1, "null", 1), field(result, "name").types());
		assertEquals(2, field(result, "name").documents());
		assertEquals(1, field(result, "address.city").documents());
		assertEquals(Map.of("date", 1), field(result, "at").types());
		// two items in one document: the path counts once per document, the type per occurrence
		assertEquals(1, field(result, "items.sku").documents());
		assertEquals(Map.of("string", 2), field(result, "items.sku").types());
		assertEquals(Map.of("object", 2), field(result, "items").itemTypes());
		assertEquals(Map.of("string", 1, "int", 1), field(result, "tags").itemTypes());
		assertFalse(result.truncated());

		Document asDocument = result.toDocument();
		assertEquals(2, asDocument.getInteger("sampled"));
		assertEquals(0.5, ((Document) asDocument.getList("fields", Document.class).stream()
				.filter(d -> d.getString("path").equals("address")).findFirst().orElseThrow()).getDouble("frequency"));
	}

	@Test
	void stopsAtTheFieldLimit() {
		var wide = new Document();
		for (int i = 0; i < SchemaSampler.MAX_FIELDS + 10; i++) {
			wide.append("k" + i, i);
		}
		var result = SchemaSampler.analyze(new ArrayList<>(List.of(wide)));
		assertEquals(SchemaSampler.MAX_FIELDS, result.fields().size());
		assertTrue(result.truncated());
	}
}
