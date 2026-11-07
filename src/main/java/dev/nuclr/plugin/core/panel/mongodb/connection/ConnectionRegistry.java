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
package dev.nuclr.plugin.core.panel.mongodb.connection;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.nuclr.plugin.core.panel.mongodb.client.MongoSession;

/**
 * One {@link MongoSession} per connection, kept while the plugin is loaded: the driver
 * keeps its own connection pool and server monitoring, and the secrets are asked for
 * (or read from the keychain) once rather than on every folder.
 */
public final class ConnectionRegistry {

	/** Opens a session for a profile; a seam so tests can answer from memory. */
	@FunctionalInterface
	public interface SessionFactory {
		MongoSession open(MongoConnectionProfile profile, MongoConnector.Secrets secrets) throws Exception;
	}

	private final Map<String, MongoSession> sessions = new ConcurrentHashMap<>();
	private final Map<String, Object> locks = new ConcurrentHashMap<>();
	private final SessionFactory factory;

	public ConnectionRegistry() {
		this(MongoConnector::open);
	}

	public ConnectionRegistry(SessionFactory factory) {
		this.factory = factory;
	}

	public MongoSession get(String profileId) {
		return sessions.get(profileId);
	}

	public boolean isConnected(String profileId) {
		return sessions.containsKey(profileId);
	}

	/**
	 * The lock first use of a connection holds, from looking for a session through
	 * asking for secrets to connecting, so two callers at once share one session.
	 */
	public Object lockFor(String profileId) {
		return locks.computeIfAbsent(profileId, id -> new Object());
	}

	/**
	 * The kept session for {@code profile}, or a new one checked with a {@code ping}, so
	 * an unreachable server or a refused login fails here rather than in a listing.
	 */
	public MongoSession connect(MongoConnectionProfile profile, MongoConnector.Secrets secrets) throws Exception {
		synchronized (lockFor(profile.getId())) {
			MongoSession existing = sessions.get(profile.getId());
			if (existing != null) {
				return existing;
			}
			MongoSession session = open(profile, secrets);
			try {
				session.ping();
			} catch (RuntimeException e) {
				session.close();
				throw e;
			}
			sessions.put(profile.getId(), session);
			return session;
		}
	}

	/** A session not kept anywhere, for the editor's Test Connection. The caller closes it. */
	public MongoSession open(MongoConnectionProfile profile, MongoConnector.Secrets secrets) throws Exception {
		return factory.open(profile, secrets);
	}

	public void disconnect(String profileId) {
		MongoSession session = sessions.remove(profileId);
		if (session != null) {
			session.close();
		}
	}

	public void disconnectAll() {
		sessions.keySet().forEach(this::disconnect);
	}
}
