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
package dev.nuclr.plugin.core.panel.mongodb.resource;

/**
 * Where a {@link MongoResource} sits in
 * {@code Explorer -> Connection -> Database -> Collection -> files / docs -> doc}.
 */
public enum ResourceKind {
	EXPLORER_ROOT,
	CONNECTION,
	DATABASE,
	/** A collection, view or time series collection. */
	COLLECTION,
	/** The {@code docs/} folder inside a collection. */
	DOCS,
	/** A virtual file such as {@code _indexes.json}. */
	FILE,
	/** One document, as {@code <_id>.json}. */
	DOC,
	/** Non-selectable last row saying the docs listing was capped. */
	LIMIT_MARKER
}
