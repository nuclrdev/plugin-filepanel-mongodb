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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.OpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionEntry;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.DatabaseEntry;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnectionProfile;

/**
 * A node in the virtual tree. Always {@code path == null}, per the SDK contract for
 * remote resources. Files and documents carry their content, fetched when their folder
 * was listed, so opening, previewing and copying them needs no further round trip.
 *
 * <p>Not a Lombok {@code @Data} class: {@link NuclrResource} defines equality by
 * {@code uuid} alone, and a generated {@code equals} would shadow that.
 */
public final class MongoResource extends NuclrResource {

	private static final long serialVersionUID = 1L;

	private static final String SCHEME = "mongodb-explorer://";

	private final ResourceKind kind;
	private String profileId;
	private String database;
	private String collection;
	private byte[] content;

	/** COLLECTION only: what the database listing knew about it. Looked up again when absent. */
	private transient CollectionEntry collectionEntry;

	private MongoResource(ResourceKind kind) {
		super(null);
		this.kind = kind;
	}

	public ResourceKind getKind() {
		return kind;
	}

	public String getProfileId() {
		return profileId;
	}

	public String getDatabase() {
		return database;
	}

	public String getCollection() {
		return collection;
	}

	public CollectionEntry getCollectionEntry() {
		return collectionEntry;
	}

	/** The file's text (FILE and DOC only). */
	public String getText() {
		return content == null ? null : new String(content, StandardCharsets.UTF_8);
	}

	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if (content == null) {
			return super.openInputStream(options);
		}
		return new ByteArrayInputStream(content);
	}

	// ------------------------------------------------------------------
	// Factories
	// ------------------------------------------------------------------

	public static MongoResource explorerRoot() {
		var r = new MongoResource(ResourceKind.EXPLORER_ROOT);
		r.setUuid(SCHEME);
		r.setFullPath(SCHEME);
		r.setName("MongoDB Explorer");
		r.setFolder(true);
		return r;
	}

	public static MongoResource connection(MongoConnectionProfile profile) {
		var r = new MongoResource(ResourceKind.CONNECTION);
		r.profileId = profile.getId();
		r.setUuid(SCHEME + profile.getId());
		r.setFullPath(profile.getName());
		r.setName(profile.getName());
		r.setFolder(true);
		r.getMetadata().put("Name", profile.getName());
		r.getMetadata().put("Hosts", profile.getDisplayTarget());
		return r;
	}

	public static MongoResource database(String profileId, DatabaseEntry entry) {
		var r = database(profileId, entry.name());
		if (entry.sizeOnDisk() >= 0) {
			r.setLength(entry.sizeOnDisk());
			r.getMetadata().put("Size", humanSize(entry.sizeOnDisk()));
		}
		return r;
	}

	public static MongoResource database(String profileId, String name) {
		var r = new MongoResource(ResourceKind.DATABASE);
		r.profileId = profileId;
		r.database = name;
		r.setUuid(SCHEME + profileId + "/" + name);
		r.setFullPath(name);
		r.setName(name);
		r.setFolder(true);
		r.getMetadata().put("Name", name);
		return r;
	}

	public static MongoResource collection(String profileId, String database, CollectionEntry entry, long docs) {
		var r = collection(profileId, database, entry.name(), entry);
		r.getMetadata().put("Type", entry.kind().label());
		r.getMetadata().put("Docs", docs < 0 ? "" : String.format(Locale.ROOT, "%,d", docs));
		return r;
	}

	/** A collection known by name, perhaps with its entry, e.g. the {@code ..} entry above its files. */
	public static MongoResource collection(String profileId, String database, String name, CollectionEntry entryOrNull) {
		var r = new MongoResource(ResourceKind.COLLECTION);
		r.profileId = profileId;
		r.database = database;
		r.collection = name;
		r.collectionEntry = entryOrNull;
		r.setUuid(SCHEME + profileId + "/" + database + "/" + name);
		r.setFullPath(database + "/" + name);
		r.setName(name);
		r.setFolder(true);
		r.getMetadata().put("Name", name);
		return r;
	}

	public static MongoResource docs(String profileId, String database, String collection) {
		var r = new MongoResource(ResourceKind.DOCS);
		r.profileId = profileId;
		r.database = database;
		r.collection = collection;
		r.setUuid(SCHEME + profileId + "/" + database + "/" + collection + "/docs");
		r.setFullPath(database + "/" + collection + "/docs");
		r.setName("docs");
		r.setFolder(true);
		r.getMetadata().put("Name", "docs");
		return r;
	}

	/** A virtual file; {@code collection} is {@code null} for a file of the database itself. */
	public static MongoResource file(String profileId, String database, String collection, String fileName,
			String text) {
		var r = new MongoResource(ResourceKind.FILE);
		r.profileId = profileId;
		r.database = database;
		r.collection = collection;
		r.content = text.getBytes(StandardCharsets.UTF_8);
		String folder = collection == null ? database : database + "/" + collection;
		r.setUuid(SCHEME + profileId + "/" + folder + "/" + fileName);
		r.setFullPath(folder + "/" + fileName);
		r.setName(fileName);
		r.setFolder(false);
		r.setLength(r.content.length);
		r.getMetadata().put("Name", fileName);
		r.getMetadata().put("Size", humanSize(r.content.length));
		return r;
	}

	/** A document under the file name the caller chose, the uuid keeping its real id. */
	public static MongoResource doc(String profileId, String database, String collection, String idText, String json,
			String timestamp, String fileName) {
		var r = new MongoResource(ResourceKind.DOC);
		r.profileId = profileId;
		r.database = database;
		r.collection = collection;
		r.content = json.getBytes(StandardCharsets.UTF_8);
		r.setUuid(SCHEME + profileId + "/" + database + "/" + collection + "/docs/" + idText);
		r.setFullPath(database + "/" + collection + "/docs/" + fileName);
		r.setName(fileName);
		r.setFolder(false);
		r.setLength(r.content.length);
		r.getMetadata().put("Name", fileName);
		r.getMetadata().put("Size", humanSize(r.content.length));
		r.getMetadata().put("Timestamp", timestamp == null ? "" : timestamp);
		return r;
	}

	public static MongoResource limitMarker(String profileId, String database, String collection, String text) {
		var r = new MongoResource(ResourceKind.LIMIT_MARKER);
		r.profileId = profileId;
		r.database = database;
		r.collection = collection;
		r.setUuid(SCHEME + profileId + "/" + database + "/" + collection + "/docs/#limit");
		r.setFullPath("");
		r.setName(text);
		r.setFolder(false);
		r.setReadable(false);
		r.getMetadata().put("Name", text);
		return r;
	}

	/** The {@code ..} entry: {@code parent}'s identity under the name {@code ..}. */
	public static MongoResource parentEntry(MongoResource parent) {
		var r = new MongoResource(parent.kind);
		r.profileId = parent.profileId;
		r.database = parent.database;
		r.collection = parent.collection;
		r.collectionEntry = parent.collectionEntry;
		r.setUuid(parent.getUuid());
		r.setFullPath(parent.getFullPath());
		r.setName("..");
		r.setFolder(true);
		return r;
	}

	/** Characters of an id kept in its file name; a longer id is cut and hashed. */
	static final int MAX_NAME_CHARS = 120;

	private static final Pattern UNSAFE = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");
	/** Names Windows keeps for devices, with or without an extension. */
	private static final Pattern RESERVED = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(\\..*)?");

	/**
	 * The file name of a document: its {@code _id} as text plus {@code .json} when that
	 * makes a valid file name as it is. Otherwise characters no file system takes become
	 * {@code _}, a long id is cut, and a hash of the real id is added
	 * ({@code a_b~1f3e9c2a.json}), so ids differing only in those characters still get
	 * different names.
	 */
	public static String docFileName(String idText) {
		String text = idText == null ? "" : idText;
		String safe = UNSAFE.matcher(text).replaceAll("_");
		boolean valid = safe.equals(text) && !safe.isBlank() && !safe.endsWith(".") && !safe.endsWith(" ")
				&& !RESERVED.matcher(safe).matches() && safe.length() <= MAX_NAME_CHARS;
		if (valid) {
			return safe + ".json";
		}
		String base = safe.length() > MAX_NAME_CHARS ? safe.substring(0, MAX_NAME_CHARS) : safe;
		return base.strip() + "~" + shortHash(text) + ".json";
	}

	/**
	 * A document's name when {@link #docFileName(String)} is already taken in the same
	 * listing: ids differing only in case, which a case-insensitive file system cannot
	 * tell apart, or ids of different types with the same text ({@code 1} and {@code "1"}).
	 */
	public static String docFileName(String idText, String typedId) {
		String plain = docFileName(idText);
		return plain.substring(0, plain.length() - ".json".length()) + "~" + shortHash(typedId) + ".json";
	}

	/** The first 8 hex digits of the text's SHA-256: stable across runs and machines. */
	private static String shortHash(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 4);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String humanSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = { "KB", "MB", "GB", "TB", "PB" };
		double value = bytes;
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}
}
