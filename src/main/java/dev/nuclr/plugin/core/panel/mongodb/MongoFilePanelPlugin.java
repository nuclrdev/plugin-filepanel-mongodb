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
package dev.nuclr.plugin.core.panel.mongodb;

import static dev.nuclr.plugin.core.panel.mongodb.Messages.tr;

import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.panel.mongodb.actions.MongoActions;
import dev.nuclr.plugin.core.panel.mongodb.client.ErrorText;
import dev.nuclr.plugin.core.panel.mongodb.client.ExtJson;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionEntry;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.CollectionKind;
import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession.DatabaseEntry;
import dev.nuclr.plugin.core.panel.mongodb.collection.CollectionFiles;
import dev.nuclr.plugin.core.panel.mongodb.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.ConnectionRegistry;
import dev.nuclr.plugin.core.panel.mongodb.connection.CredentialStore;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnectionProfile;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnector;
import dev.nuclr.plugin.core.panel.mongodb.connection.ProxyType;
import dev.nuclr.plugin.core.panel.mongodb.panel.ConnectionEditorDialog;
import dev.nuclr.plugin.core.panel.mongodb.resource.MongoResource;
import dev.nuclr.plugin.core.panel.mongodb.resource.ResourceKind;
import lombok.extern.slf4j.Slf4j;

/**
 * Browses MongoDB deployments as a read-only virtual filesystem:
 * {@code Explorer root -> Connection -> Database -> Collection -> files and docs/}.
 *
 * <p>Like every {@link FilePanelNuclrPlugin}, this is a data and behaviour provider; the
 * host owns the table. The only Swing this plugin owns is its modal dialogs.
 */
@Slf4j
public final class MongoFilePanelPlugin implements FilePanelNuclrPlugin {

	/** Documents shown in a {@code docs/} folder. It is a peek at the newest data, not a way to page a collection. */
	static final int DOC_CAP = 500;

	/** Collections above which a database listing skips the per-collection document counts. */
	static final int COUNT_LIMIT = 200;

	private static final String ActionNewConnection = "mongodb.newConnection";
	private static final String ActionEditConnection = "mongodb.editConnection";
	private static final String ActionDeleteConnection = "mongodb.deleteConnection";
	private static final String ActionDisconnect = "mongodb.disconnect";
	/** Must match Commander's F5 action protocol value (see {@code LocalFileSystemPlugin}). */
	private static final String ActionCopy = "filepanel.copy";
	private static final String ActionAcceptCopy = "accept.copy";

	/** Event-type prefix the commander parses into one of its own sort comparators. */
	private static final String SortEventPrefix = "filepanel.sort:";

	/** The user dismissed a secret prompt; the listing simply comes back empty. */
	private static final class ConnectCancelled extends Exception {

		private static final long serialVersionUID = 1L;

		ConnectCancelled() {
			super("cancelled", null, false, false);
		}
	}

	private record SecretPrompt(String secret, boolean remember) {
	}

	private final String uuid = UUID.randomUUID().toString();
	private final ConnectionRegistry.SessionFactory sessionFactory;

	private NuclrPluginContext context;
	private ConnectionProfileStore profileStore;
	private CredentialStore credentialStore;
	private ConnectionRegistry connectionRegistry;
	private MongoActions actions;

	private volatile boolean focused;
	private volatile MongoResource currentResource;

	public MongoFilePanelPlugin() {
		this(null);
	}

	/** For tests: every connection is made through {@code sessionFactory}. */
	MongoFilePanelPlugin(ConnectionRegistry.SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	// ------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------

	@Override
	public void preinit(NuclrPluginContext context) {
		Messages.use(context.getLocale());
		this.context = context;
		this.profileStore = new ConnectionProfileStore(context.getSettings());
		this.credentialStore = new CredentialStore(context.getCredentialStore());
		this.connectionRegistry = sessionFactory == null ? new ConnectionRegistry()
				: new ConnectionRegistry(sessionFactory);
		this.actions = new MongoActions(profileStore, this::actionSession, connectionRegistry::disconnect);
		this.currentResource = MongoResource.explorerRoot();
		log.info("MongoDB Explorer panel plugin loaded");
	}

	@Override
	public void init() {
	}

	@Override
	public void unload() {
		connectionRegistry.disconnectAll();
		log.info("MongoDB Explorer panel plugin unloaded");
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void closeResource() {
		// Every listing is fetched whole in openResource; nothing stays open per resource.
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		return resource instanceof MongoResource;
	}

	// ------------------------------------------------------------------
	// Alt+F1 / Alt+F2 drive picker
	// ------------------------------------------------------------------

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		var item = new MenuItem();
		item.setText("MongoDB Explorer");
		item.setPath(MongoResource.explorerRoot());
		item.setUuid(PluginIds.PLUGIN_ID + ":explorer");

		var holder = new MenuItemsHolder();
		holder.setTitle("MongoDB");
		holder.setMenuItems(List.of(item));
		return holder;
	}

	// ------------------------------------------------------------------
	// Listing
	// ------------------------------------------------------------------

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled) {
		return openResource(resourceToOpen, cancelled, null);
	}

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen, AtomicBoolean cancelled, EntrySink sink) {
		if (!(resourceToOpen instanceof MongoResource mongo)) {
			return null;
		}
		try {
			return switch (mongo.getKind()) {
				case EXPLORER_ROOT -> listConnections(sink);
				case CONNECTION -> listDatabases(mongo, sink);
				case DATABASE -> listCollections(mongo, cancelled, sink);
				case COLLECTION -> listCollectionFolder(mongo, cancelled, sink);
				case DOCS -> listDocs(mongo, sink);
				case FILE, DOC, LIMIT_MARKER -> null;
			};
		} catch (ConnectCancelled cancelledByUser) {
			return new NuclrResourceData();
		} catch (Exception e) {
			log.error("Failed to open MongoDB resource {}: {}", mongo.getFullPath(), e.getMessage(), e);
			if (ErrorText.isAuthenticationFailure(e) && mongo.getProfileId() != null) {
				connectionRegistry.disconnect(mongo.getProfileId());
			}
			showErrorDialog(tr("panel.title"), tr("error.open", mongo.getName(), ErrorText.describe(e)));
			return new NuclrResourceData();
		}
	}

	private static NuclrResourceData listing(List<String> columns, EntrySink sink) {
		var data = new NuclrResourceData();
		data.getColumnNames().addAll(columns);
		if (sink != null) {
			sink.columns(columns);
		}
		return data;
	}

	private static void add(NuclrResourceData data, EntrySink sink, NuclrResource entry) {
		data.getEntries().add(entry);
		if (sink != null) {
			sink.add(entry);
		}
	}

	private NuclrResourceData listConnections(EntrySink sink) {
		currentResource = MongoResource.explorerRoot();
		var data = listing(List.of("Name", "Status", "Hosts"), sink);
		for (MongoConnectionProfile profile : profileStore.loadAll()) {
			var entry = MongoResource.connection(profile);
			entry.getMetadata().put("Status", connectionRegistry.isConnected(profile.getId()) ? tr("status.connected") : "");
			add(data, sink, entry);
		}
		return data;
	}

	private NuclrResourceData listDatabases(MongoResource connectionRes, EntrySink sink) throws Exception {
		currentResource = connectionRes;
		String profileId = connectionRes.getProfileId();
		if (profileStore.findById(profileId) == null) {
			return new NuclrResourceData();
		}
		MongoSession session = ensureConnected(profileId);
		var data = listing(List.of("Name", "Size"), sink);
		add(data, sink, MongoResource.parentEntry(MongoResource.explorerRoot()));
		for (DatabaseEntry entry : session.databases()) {
			add(data, sink, MongoResource.database(profileId, entry));
		}
		return data;
	}

	private NuclrResourceData listCollections(MongoResource databaseRes, AtomicBoolean cancelled, EntrySink sink)
			throws Exception {
		currentResource = databaseRes;
		String profileId = databaseRes.getProfileId();
		String database = databaseRes.getDatabase();
		MongoSession session = ensureConnected(profileId);

		var data = listing(List.of("Name", "Type", "Docs", "Size"), sink);
		MongoConnectionProfile profile = profileStore.findById(profileId);
		if (profile != null) {
			add(data, sink, MongoResource.parentEntry(MongoResource.connection(profile)));
		}
		List<CollectionEntry> collections = new ArrayList<>(session.collections(database));
		collections.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
		boolean count = collections.size() <= COUNT_LIMIT;
		for (CollectionEntry entry : collections) {
			long docs = count && entry.kind() != CollectionKind.VIEW && !isCancelled(cancelled)
					? session.estimatedCount(database, entry.name())
					: -1;
			add(data, sink, MongoResource.collection(profileId, database, entry, docs));
		}
		for (CollectionFiles.VirtualFile file : CollectionFiles.buildForDatabase(session, database)) {
			add(data, sink, MongoResource.file(profileId, database, null, file.name(), file.content()));
		}
		return data;
	}

	private NuclrResourceData listCollectionFolder(MongoResource collectionRes, AtomicBoolean cancelled,
			EntrySink sink) throws Exception {
		currentResource = collectionRes;
		String profileId = collectionRes.getProfileId();
		String database = collectionRes.getDatabase();
		MongoSession session = ensureConnected(profileId);
		CollectionEntry entry = collectionEntry(session, collectionRes);

		var data = listing(List.of("Name", "Size"), sink);
		add(data, sink, MongoResource.parentEntry(MongoResource.database(profileId, database)));
		add(data, sink, MongoResource.docs(profileId, database, entry.name()));
		for (CollectionFiles.VirtualFile file : CollectionFiles.build(session, database, entry, cancelled)) {
			add(data, sink, MongoResource.file(profileId, database, entry.name(), file.name(), file.content()));
		}
		return data;
	}

	/** The listing's entry for a collection, or the server's when the resource came without one. */
	private static CollectionEntry collectionEntry(MongoSession session, MongoResource res) {
		if (res.getCollectionEntry() != null) {
			return res.getCollectionEntry();
		}
		for (CollectionEntry e : session.collections(res.getDatabase())) {
			if (e.name().equals(res.getCollection())) {
				return e;
			}
		}
		return new CollectionEntry(res.getCollection(), CollectionKind.COLLECTION, new Document());
	}

	private NuclrResourceData listDocs(MongoResource docsRes, EntrySink sink) throws Exception {
		currentResource = docsRes;
		String profileId = docsRes.getProfileId();
		String database = docsRes.getDatabase();
		String collection = docsRes.getCollection();
		MongoSession session = ensureConnected(profileId);
		CollectionEntry entry = collectionEntry(session, MongoResource.collection(profileId, database, collection, null));

		var data = listing(List.of("Name", "Timestamp", "Size"), sink);
		add(data, sink, MongoResource.parentEntry(MongoResource.collection(profileId, database, collection, entry)));

		String timeField = entry.timeField();
		List<Document> documents = session.find(database, collection, null, null, newestFirst(entry), 0, DOC_CAP);
		// Compared ignoring case: copied out, a.json and A.json would land on one file.
		var names = new HashSet<String>();
		for (Document document : documents) {
			Object id = document.get("_id");
			String idText = ExtJson.idText(id);
			String fileName = MongoResource.docFileName(idText);
			if (!names.add(fileName.toLowerCase(Locale.ROOT))) {
				fileName = MongoResource.docFileName(idText, ExtJson.typedId(id));
				names.add(fileName.toLowerCase(Locale.ROOT));
			}
			add(data, sink, MongoResource.doc(profileId, database, collection, ExtJson.typedId(id),
					ExtJson.pretty(document) + "\n", timestamp(document, id, timeField), fileName));
		}
		if (documents.size() >= DOC_CAP) {
			long total = entry.kind() == CollectionKind.VIEW ? -1 : session.estimatedCount(database, collection);
			String shown = String.valueOf(documents.size());
			String text;
			if (total > documents.size()) {
				String all = NumberFormat.getIntegerInstance(Messages.locale()).format(total);
				text = timeField != null ? tr("docs.limit.newest.by", shown, all, timeField)
						: entry.kind() == CollectionKind.VIEW ? tr("docs.limit.first", shown, all)
								: tr("docs.limit.newest", shown, all);
			} else {
				text = tr("docs.limit.unknown", shown);
			}
			add(data, sink, MongoResource.limitMarker(profileId, database, collection, text));
		}
		return data;
	}

	/**
	 * Newest first: by the time field of a time series collection, in reverse insertion
	 * order for a capped one, otherwise by {@code _id} descending, which for the usual
	 * ObjectId ids is creation order.
	 */
	static Bson newestFirst(CollectionEntry entry) {
		if (entry.timeField() != null) {
			return new Document(entry.timeField(), -1);
		}
		if (entry.capped()) {
			return new Document("$natural", -1);
		}
		return new Document("_id", -1);
	}

	/** The Timestamp column: the time field's value, else the moment an ObjectId id was made. */
	static String timestamp(Document document, Object id, String timeField) {
		if (timeField != null && document.get(timeField) instanceof Date date) {
			return date.toInstant().toString();
		}
		if (id instanceof ObjectId objectId) {
			return objectId.getDate().toInstant().toString();
		}
		return "";
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}

	// ------------------------------------------------------------------
	// Connecting
	// ------------------------------------------------------------------

	/**
	 * The session for {@code profileId}, connecting on first use. Only the secrets the
	 * profile needs and has not stored are asked for.
	 */
	private MongoSession ensureConnected(String profileId) throws Exception {
		MongoSession existing = connectionRegistry.get(profileId);
		if (existing != null) {
			return existing;
		}
		// One first use at a time per connection: a second caller waits here and then
		// finds the session, rather than asking for the secrets again or connecting twice.
		synchronized (connectionRegistry.lockFor(profileId)) {
			return connectOnce(profileId);
		}
	}

	private MongoSession connectOnce(String profileId) throws Exception {
		MongoSession existing = connectionRegistry.get(profileId);
		if (existing != null) {
			return existing;
		}
		MongoConnectionProfile profile = profileStore.findById(profileId);
		if (profile == null) {
			throw new IllegalStateException("Unknown connection: " + profileId);
		}
		String proxyKey = CredentialStore.proxyKey(profileId);
		String proxySecret = profile.getEffectiveProxyType() == ProxyType.NONE ? null
				: credentialStore.loadPassword(proxyKey);
		boolean rememberProxy = false;
		if (proxySecret == null && profile.needsProxySecret()) {
			// Asked first: the tunnel or proxy is the first thing that has to open.
			SecretPrompt prompt = promptForSecret(profile.getEffectiveProxyType() == ProxyType.SSH
					? tr("ssh.password.prompt", profile.getName(), profile.getProxyHost())
					: tr("proxy.password.prompt", profile.getName(), profile.getProxyHost()));
			if (prompt == null) {
				throw new ConnectCancelled();
			}
			proxySecret = prompt.secret();
			rememberProxy = prompt.remember();
		}

		String secret = profile.getEffectiveAuthType().takesSecret() ? credentialStore.loadPassword(profileId) : null;
		boolean remember = false;
		if (secret == null && profile.needsSecret()) {
			SecretPrompt prompt = promptForSecret(tr("secret.prompt", profile.getName()));
			if (prompt == null) {
				throw new ConnectCancelled();
			}
			secret = prompt.secret();
			remember = prompt.remember();
		}
		String tlsSecret = profile.hasClientCertificate() ? credentialStore.loadPassword(CredentialStore.tlsKey(profileId))
				: null;

		MongoSession session = connectionRegistry.connect(profile, new MongoConnector.Secrets(secret, tlsSecret, proxySecret));
		if (remember && secret != null) {
			warnIfNotStored(credentialStore.savePassword(profileId, secret.toCharArray()));
		}
		if (rememberProxy && proxySecret != null) {
			warnIfNotStored(credentialStore.savePassword(proxyKey, proxySecret.toCharArray()));
		}
		return session;
	}

	/**
	 * {@link #ensureConnected} for an action: the same stored secrets or prompts, with a
	 * dismissed prompt reported as {@code null} rather than an exception.
	 */
	private MongoSession actionSession(MongoConnectionProfile profile) throws Exception {
		try {
			return ensureConnected(profile.getId());
		} catch (ConnectCancelled e) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Location / selection text
	// ------------------------------------------------------------------

	@Override
	public String getCurrentLocationDisplayText() {
		MongoResource r = currentResource;
		if (r == null || r.getKind() == ResourceKind.EXPLORER_ROOT) {
			return tr("panel.title");
		}
		String connection = connectionDisplayName(r.getProfileId());
		return switch (r.getKind()) {
			case CONNECTION -> connection;
			case DATABASE -> connection + " / " + r.getDatabase();
			case COLLECTION -> connection + " / " + r.getDatabase() + " / " + r.getCollection();
			case DOCS -> connection + " / " + r.getDatabase() + " / " + r.getCollection() + " / docs";
			default -> r.getFullPath();
		};
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) {
			return "";
		}
		return selectedResources.size() == 1
				? tr("summary.items.one")
				: tr("summary.items.many", String.valueOf(selectedResources.size()));
	}

	private String connectionDisplayName(String profileId) {
		MongoConnectionProfile profile = profileStore.findById(profileId);
		return profile != null ? profile.getName() : String.valueOf(profileId);
	}

	// ------------------------------------------------------------------
	// Menus (F-key bar + right-click) and actions
	// ------------------------------------------------------------------

	/** The resource under the cursor, or where the panel stands when there is none (an empty root). */
	private MongoResource menuTarget(NuclrResource resource) {
		return resource instanceof MongoResource mongo ? mongo : currentResource;
	}

	private static boolean isConnectionFocused(NuclrResource resource) {
		return resource instanceof MongoResource mongo && mongo.getKind() == ResourceKind.CONNECTION;
	}

	private static NuclrMenuResource sort(String label, String functionKey, String criterion, String columnLabel) {
		return new NuclrMenuResource(label, functionKey,
				SortEventPrefix + criterion + (columnLabel == null ? "" : ":" + columnLabel));
	}

	private void addSortItems(List<NuclrMenuResource> items) {
		MongoResource here = currentResource;
		if (here == null) {
			return;
		}
		items.add(sort(tr("sort.name"), "Ctrl+F3", "name", "Name"));
		if (here.getKind() != ResourceKind.EXPLORER_ROOT) {
			items.add(sort(tr("sort.size"), "Ctrl+F6", "size", "Size"));
		}
		// Unsorted is the order the server returned: newest first inside docs/.
		items.add(sort(tr("sort.unsort"), "Ctrl+F7", "unsorted", null));
		items.add(sort(tr("sort.dialog"), "Ctrl+F12", "dialog", null));
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource resource) {
		var items = new ArrayList<NuclrMenuResource>();
		addSortItems(items);
		MongoResource target = menuTarget(resource);
		if (target == null) {
			return items;
		}
		switch (target.getKind()) {
			case EXPLORER_ROOT, CONNECTION -> {
				items.add(new NuclrMenuResource(tr("menu.new.connection"), "F7", ActionNewConnection));
				if (isConnectionFocused(resource)) {
					items.add(new NuclrMenuResource(tr("menu.edit.connection"), "F4", ActionEditConnection));
					items.add(new NuclrMenuResource(tr("menu.delete.connection"), "F8", ActionDeleteConnection));
				}
			}
			case FILE, DOC -> items.add(new NuclrMenuResource(tr("menu.copy"), "F5", ActionCopy));
			default -> {
				// Databases, collections and docs/ folders are read-only; nothing to offer on the bar.
			}
		}
		return items;
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource, List<NuclrResource> selectedResources) {
		MongoResource target = menuTarget(focusedResource);
		if (target == null) {
			return List.of();
		}
		var newConnection = NuclrContextMenuItem.builder().label(tr("context.new.connection")).actionType(ActionNewConnection).build();
		return switch (target.getKind()) {
			case EXPLORER_ROOT -> List.of(newConnection);
			case CONNECTION -> {
				if (!isConnectionFocused(focusedResource)) {
					yield List.of(newConnection);
				}
				String profileId = ((MongoResource) focusedResource).getProfileId();
				yield List.of(
						newConnection,
						NuclrContextMenuItem.builder().label(tr("context.edit.connection")).actionType(ActionEditConnection).build(),
						NuclrContextMenuItem.builder().label(tr("context.delete.connection")).actionType(ActionDeleteConnection)
								.destructive(true).build(),
						NuclrContextMenuItem.separator(),
						NuclrContextMenuItem.builder().label(tr("context.disconnect")).actionType(ActionDisconnect)
								.enabled(connectionRegistry.isConnected(profileId)).build());
			}
			default -> List.of();
		};
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		// Actions declared in actions.json (agents, command palette). They need no pane,
		// selection or open resource, so they run the same on the headless instance the
		// commander keeps for them.
		if (MongoActions.handles(actionType)) {
			actions.run(actionType, data, callback);
			return;
		}

		switch (actionType) {
			case ActionNewConnection -> handleNewConnection(data);
			case ActionEditConnection -> handleEditConnection(focusedResource, data);
			case ActionDeleteConnection -> handleDeleteConnection(focusedResource, selectedResources, data);
			case ActionDisconnect -> handleDisconnect(focusedResource, data);
			case ActionCopy -> handleCopy(other, selectedResources, focusedResource, data, callback);
			default -> {
				// Server content is read-only: nothing else is offered, so nothing else should arrive.
			}
		}
	}

	private void handleCopy(BaseNuclrPlugin other, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback) {
		if (other == null || other.uuid().equals(this.uuid) || other instanceof QuickViewNuclrPlugin) {
			return; // no in-place copy target for a read-only virtual panel
		}
		other.act(null, ActionAcceptCopy, selectedResources, focusedResource, data, callback);
	}

	/**
	 * Test Connection in the editor: a ping through a session nobody keeps. A secret left
	 * blank on an edit means the stored one.
	 */
	private String testConnection(MongoConnectionProfile profile, MongoConnector.Secrets typed) throws Exception {
		String id = profile.getId();
		String main = typed.main();
		String tls = typed.tls();
		String proxy = typed.proxy();
		if (id != null) {
			main = main != null ? main : credentialStore.loadPassword(id);
			tls = tls != null ? tls : credentialStore.loadPassword(CredentialStore.tlsKey(id));
			proxy = proxy != null ? proxy : credentialStore.loadPassword(CredentialStore.proxyKey(id));
		}
		try (MongoSession session = connectionRegistry.open(profile, new MongoConnector.Secrets(main, tls, proxy))) {
			MongoSession.ServerInfo info = session.ping();
			return tr("editor.test.ok", info.version().isBlank() ? "?" : info.version(), info.topology());
		} catch (Exception e) {
			throw new Exception(ErrorText.describe(e), e);
		}
	}

	private void handleNewConnection(Map<String, Object> data) {
		ConnectionEditorDialog.Result result = ConnectionEditorDialog.showNew(activeWindow(), this::testConnection);
		if (result == null) {
			return;
		}
		MongoConnectionProfile profile = profileStore.add(result.profile());
		storeSecrets(profile.getId(), result);
		putRefresh(data);
	}

	private void handleEditConnection(NuclrResource focusedResource, Map<String, Object> data) {
		String profileId = connectionIdOf(focusedResource);
		MongoConnectionProfile existing = profileId == null ? null : profileStore.findById(profileId);
		if (existing == null) {
			return;
		}
		ConnectionEditorDialog.Result result = ConnectionEditorDialog.showEdit(activeWindow(), existing,
				new ConnectionEditorDialog.Stored(credentialStore.hasPassword(profileId),
						credentialStore.hasPassword(CredentialStore.tlsKey(profileId)),
						credentialStore.hasPassword(CredentialStore.proxyKey(profileId))),
				this::testConnection);
		if (result == null) {
			return;
		}
		connectionRegistry.disconnect(profileId); // the hosts or a secret may have changed
		profileStore.update(result.profile());
		storeSecrets(profileId, result);
		putRefresh(data);
	}

	/** Each secret: a typed one replaces the stored one, an unticked Remember forgets it. */
	private void storeSecrets(String profileId, ConnectionEditorDialog.Result result) {
		storeSecret(profileId, result.secret(), result.rememberSecret());
		storeSecret(CredentialStore.tlsKey(profileId), result.tlsSecret(), result.rememberTlsSecret());
		storeSecret(CredentialStore.proxyKey(profileId), result.proxySecret(), result.rememberProxySecret());
	}

	private void storeSecret(String key, String typed, boolean remember) {
		if (!remember) {
			credentialStore.deletePassword(key);
		} else if (typed != null) {
			warnIfNotStored(credentialStore.savePassword(key, typed.toCharArray()));
		}
	}

	private void handleDeleteConnection(NuclrResource focusedResource, List<NuclrResource> selectedResources,
			Map<String, Object> data) {
		var ids = new LinkedHashSet<String>();
		List<NuclrResource> targets = selectedResources != null && !selectedResources.isEmpty()
				? selectedResources
				: focusedResource != null ? List.of(focusedResource) : List.of();
		for (NuclrResource r : targets) {
			if (r instanceof MongoResource mongo && mongo.getKind() == ResourceKind.CONNECTION) {
				ids.add(mongo.getProfileId());
			}
		}
		if (ids.isEmpty()) {
			return;
		}
		int choice = JOptionPane.showConfirmDialog(activeWindow(),
				ids.size() == 1 ? tr("delete.confirm.one") : tr("delete.confirm.many", String.valueOf(ids.size())),
				tr("delete.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice != JOptionPane.YES_OPTION) {
			return;
		}
		deleteConnections(ids);
		putRefresh(data);
	}

	/** Forget the connections: session, profile and stored secrets. Separate from the dialog for tests. */
	void deleteConnections(Iterable<String> ids) {
		for (String id : ids) {
			connectionRegistry.disconnect(id);
			profileStore.delete(id);
			credentialStore.deleteAll(id);
		}
	}

	private void handleDisconnect(NuclrResource focusedResource, Map<String, Object> data) {
		String id = connectionIdOf(focusedResource);
		if (id != null) {
			connectionRegistry.disconnect(id);
			putRefresh(data);
		}
	}

	private static String connectionIdOf(NuclrResource resource) {
		return resource instanceof MongoResource mongo ? mongo.getProfileId() : null;
	}

	private static void putRefresh(Map<String, Object> data) {
		if (data == null) {
			return;
		}
		try {
			data.put("result.refresh", Boolean.TRUE);
		} catch (UnsupportedOperationException ignored) {
			// an immutable payload map; nothing more we can do to request a refresh
		}
	}

	// ------------------------------------------------------------------
	// Small Swing helpers
	// ------------------------------------------------------------------

	/** @return the entered secret (blank becomes {@code null}), or {@code null} if cancelled. */
	private SecretPrompt promptForSecret(String prompt) {
		if (GraphicsEnvironment.isHeadless()) {
			log.warn("Cannot ask without a display: {}", prompt);
			return null;
		}
		Supplier<SecretPrompt> ask = () -> {
			var field = new JPasswordField(24);
			var rememberBox = new JCheckBox(tr("secret.remember"), true);
			var panel = new JPanel(new BorderLayout(6, 6));
			panel.add(new JLabel(prompt), BorderLayout.NORTH);
			panel.add(field, BorderLayout.CENTER);
			panel.add(rememberBox, BorderLayout.SOUTH);
			int choice = JOptionPane.showConfirmDialog(activeWindow(), panel, tr("secret.title"),
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (choice != JOptionPane.OK_OPTION) {
				return null;
			}
			String typed = new String(field.getPassword());
			return new SecretPrompt(typed.isBlank() ? null : typed, rememberBox.isSelected());
		};
		if (SwingUtilities.isEventDispatchThread()) {
			return ask.get();
		}
		var result = new AtomicReference<SecretPrompt>();
		try {
			SwingUtilities.invokeAndWait(() -> result.set(ask.get()));
		} catch (Exception e) {
			return null;
		}
		return result.get();
	}

	private void warnIfNotStored(String failureReason) {
		if (failureReason != null) {
			showErrorDialog(tr("secret.not.stored.title"), tr("secret.not.stored", failureReason));
		}
	}

	private void showErrorDialog(String title, String message) {
		if (GraphicsEnvironment.isHeadless()) {
			log.warn("{}: {}", title, message);
			return;
		}
		Runnable show = () -> JOptionPane.showMessageDialog(activeWindow(), message, title, JOptionPane.ERROR_MESSAGE);
		if (SwingUtilities.isEventDispatchThread()) {
			show.run();
		} else {
			SwingUtilities.invokeLater(show);
		}
	}

	private static Window activeWindow() {
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
	}
}
