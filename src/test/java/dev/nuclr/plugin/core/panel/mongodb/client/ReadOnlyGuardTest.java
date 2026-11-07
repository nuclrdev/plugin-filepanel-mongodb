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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

class ReadOnlyGuardTest {

	private static List<BsonDocument> pipeline(String json) {
		return BsonArray.parse(json).stream().map(BsonValue::asDocument).toList();
	}

	@Test
	void readingStagesPass() {
		assertNull(ReadOnlyGuard.writingStage(pipeline(
				"[{\"$match\": {\"a\": 1}}, {\"$group\": {\"_id\": \"$b\", \"n\": {\"$sum\": 1}}}, {\"$sort\": {\"n\": -1}}]")));
	}

	@Test
	void outAndMergeAreFoundAnywhere() {
		assertEquals("$out", ReadOnlyGuard.writingStage(pipeline("[{\"$match\": {}}, {\"$out\": \"copy\"}]")));
		assertEquals("$merge", ReadOnlyGuard.writingStage(pipeline("[{\"$merge\": {\"into\": \"x\"}}]")));
		assertEquals("$out", ReadOnlyGuard.writingStage(pipeline(
				"[{\"$facet\": {\"a\": [{\"$match\": {}}], \"b\": [{\"$out\": \"x\"}]}}]")));
		assertEquals("$merge", ReadOnlyGuard.writingStage(pipeline(
				"[{\"$lookup\": {\"from\": \"o\", \"pipeline\": [{\"$merge\": \"x\"}], \"as\": \"y\"}}]")));
	}
}
