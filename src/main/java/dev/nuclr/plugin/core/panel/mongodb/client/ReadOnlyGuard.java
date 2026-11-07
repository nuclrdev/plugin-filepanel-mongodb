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
import java.util.Set;

import org.bson.BsonDocument;
import org.bson.BsonValue;

/**
 * Keeps an aggregation pipeline a read. Every other operation this plugin can run is a
 * read by construction ({@link MongoSession} has no write); a pipeline is the one place
 * a write can hide, as a {@code $out} or {@code $merge} stage, including inside a
 * {@code $facet}, {@code $lookup} or {@code $unionWith} sub-pipeline.
 */
public final class ReadOnlyGuard {

	/** Stages that write their results into a collection. */
	static final Set<String> WRITING_STAGES = Set.of("$out", "$merge");

	private ReadOnlyGuard() {
	}

	/**
	 * @return the first writing stage found anywhere in the pipeline, or {@code null}
	 *         when it only reads
	 */
	public static String writingStage(List<BsonDocument> pipeline) {
		for (BsonDocument stage : pipeline) {
			String found = find(stage);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static String find(BsonValue value) {
		if (value.isDocument()) {
			for (var entry : value.asDocument().entrySet()) {
				if (WRITING_STAGES.contains(entry.getKey())) {
					return entry.getKey();
				}
				String nested = find(entry.getValue());
				if (nested != null) {
					return nested;
				}
			}
		} else if (value.isArray()) {
			for (BsonValue item : value.asArray()) {
				String nested = find(item);
				if (nested != null) {
					return nested;
				}
			}
		}
		return null;
	}
}
