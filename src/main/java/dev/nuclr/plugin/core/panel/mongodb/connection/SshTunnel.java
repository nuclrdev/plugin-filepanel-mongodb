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

import static dev.nuclr.plugin.core.panel.mongodb.Messages.tr;

import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.DynamicPortForwardingTracker;
import org.apache.sshd.common.NamedResource;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.apache.sshd.putty.PuttyKeyUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * A SOCKS proxy forwarded over SSH ({@code ssh -D}), for servers only reachable from a
 * bastion host: {@code 127.0.0.1:<free port>} on this machine is a SOCKS5 proxy whose
 * connections the SSH server opens. The driver is pointed at it, so every host it
 * learns of, each member of a replica set, each mongos, is reached as the SSH server
 * sees it, by the names the servers themselves report.
 *
 * <p>Host keys are checked against {@code ~/.nuclr/net/known_hosts}, the file the Net
 * panel keeps, so a bastion already trusted there is not asked about again here.
 */
@Slf4j
public final class SshTunnel implements AutoCloseable {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
	private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(30);
	/** Keeps an idle tunnel from being dropped by a NAT or firewall between queries. */
	private static final Duration HEARTBEAT = Duration.ofSeconds(30);
	private static final String PPK_HEADER = "PuTTY-User-Key-File-";

	private final SshClient client;
	private final ClientSession session;
	private final DynamicPortForwardingTracker tracker;

	private SshTunnel(SshClient client, ClientSession session, DynamicPortForwardingTracker tracker) {
		this.client = client;
		this.session = session;
		this.tracker = tracker;
	}

	/** The local port of the SOCKS proxy. */
	public int localPort() {
		return tracker.getBoundAddress().getPort();
	}

	/**
	 * Log in to the profile's SSH server and start a SOCKS proxy on a free local port.
	 *
	 * @param secret the SSH password, or the key file's passphrase; {@code null} for an
	 *               unencrypted key and no password
	 */
	public static SshTunnel open(MongoConnectionProfile profile, String secret) throws IOException {
		String sshHost = profile.getProxyHost() == null ? "" : profile.getProxyHost().trim();
		if (sshHost.isEmpty()) {
			throw new IOException(tr("ssh.error.no.host"));
		}
		int sshPort = parsePort(profile.getProxyPort(), 22);
		String sshUser = profile.getProxyUsername() == null || profile.getProxyUsername().isBlank()
				? System.getProperty("user.name")
				: profile.getProxyUsername().trim();

		SshClient client = SshClient.setUpDefaultClient();
		client.setServerKeyVerifier(verifier());
		client.setSessionHeartbeat(SessionHeartbeatController.HeartbeatType.IGNORE, HEARTBEAT);
		client.start();
		ClientSession session = null;
		try {
			log.info("Opening SSH tunnel {}@{}:{}", sshUser, sshHost, sshPort);
			session = client.connect(sshUser, sshHost, sshPort).verify(CONNECT_TIMEOUT).getSession();

			String keyPath = profile.getSshKeyPath();
			if (keyPath != null && !keyPath.isBlank()) {
				loadKeys(expandHome(keyPath.trim()), secret).forEach(session::addPublicKeyIdentity);
			}
			if (secret != null && !secret.isEmpty()) {
				// Offered alongside a key too: the one secret field stands for a password
				// or a passphrase, and the server simply skips whichever does not apply.
				session.addPasswordIdentity(secret);
			}
			session.auth().verify(AUTH_TIMEOUT);

			DynamicPortForwardingTracker tracker = session
					.createDynamicPortForwardingTracker(new SshdSocketAddress("127.0.0.1", 0));
			return new SshTunnel(client, session, tracker);
		} catch (IOException | RuntimeException e) {
			closeQuietly(session);
			client.stop();
			throw e;
		}
	}

	@Override
	public void close() {
		closeQuietly(tracker);
		closeQuietly(session);
		client.stop();
	}

	private static void closeQuietly(AutoCloseable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch (Exception e) {
			log.debug("Error closing SSH tunnel: {}", e.getMessage());
		}
	}

	static int parsePort(String text, int fallback) {
		try {
			return text == null || text.isBlank() ? fallback : Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** {@code ~/.ssh/id_ed25519} as people type it, and as Workbench and OpenSSH accept it. */
	static Path expandHome(String path) {
		if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
			return Path.of(System.getProperty("user.home") + path.substring(1));
		}
		return Path.of(path);
	}

	private static List<KeyPair> loadKeys(Path keyFile, String passphrase) throws IOException {
		if (!Files.isRegularFile(keyFile)) {
			throw new IOException(tr("ssh.error.key.missing", keyFile.toString()));
		}
		FilePasswordProvider passwords = passphrase == null || passphrase.isEmpty()
				? FilePasswordProvider.EMPTY
				: FilePasswordProvider.of(passphrase);
		Iterable<KeyPair> pairs;
		try {
			if (isPpk(keyFile)) {
				pairs = PuttyKeyUtils.DEFAULT_INSTANCE.loadKeyPairs(null, keyFile, passwords);
			} else {
				try (InputStream in = Files.newInputStream(keyFile)) {
					pairs = SecurityUtils.loadKeyPairIdentities(
							null, NamedResource.ofName(keyFile.toString()), in, passwords);
				}
			}
		} catch (IOException | java.security.GeneralSecurityException e) {
			throw new IOException(tr("ssh.error.key.load", keyFile.toString(), e.getMessage()), e);
		}
		var result = new ArrayList<KeyPair>();
		if (pairs != null) {
			pairs.forEach(result::add);
		}
		if (result.isEmpty()) {
			throw new IOException(tr("ssh.error.key.empty", keyFile.toString()));
		}
		return result;
	}

	private static boolean isPpk(Path keyFile) throws IOException {
		if (keyFile.getFileName() != null
				&& keyFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ppk")) {
			return true;
		}
		try (InputStream in = Files.newInputStream(keyFile)) {
			return new String(in.readNBytes(PPK_HEADER.length()), StandardCharsets.US_ASCII).startsWith(PPK_HEADER);
		}
	}

	// ------------------------------------------------------------------
	// Host key verification
	// ------------------------------------------------------------------

	private static Path knownHostsFile() {
		return Path.of(System.getProperty("user.home"), ".nuclr", "net", "known_hosts");
	}

	private static ServerKeyVerifier verifier() throws IOException {
		Path file = knownHostsFile();
		Files.createDirectories(file.getParent());
		if (!Files.exists(file)) {
			Files.createFile(file);
		}
		var verifier = new KnownHostsServerKeyVerifier((session, address, key) -> {
			boolean accepted = confirm(tr("ssh.hostkey.unknown.title"),
					tr("ssh.hostkey.unknown", address(address), KeyUtils.getKeyType(key), fingerprint(key)),
					JOptionPane.QUESTION_MESSAGE);
			log.info("Unknown host key for {} ({}): {}", address, fingerprint(key), accepted ? "accepted" : "rejected");
			return accepted;
		}, file);
		verifier.setModifiedServerKeyAcceptor((session, address, entry, expected, actual) -> {
			boolean accepted = confirm(tr("ssh.hostkey.changed.title"),
					tr("ssh.hostkey.changed", address(address), fingerprint(expected), fingerprint(actual)),
					JOptionPane.WARNING_MESSAGE);
			log.warn("CHANGED host key for {} (recorded {}, presented {}): {}", address, fingerprint(expected),
					fingerprint(actual), accepted ? "ACCEPTED by user" : "rejected");
			return accepted;
		});
		return verifier;
	}

	private static String fingerprint(PublicKey key) {
		return KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
	}

	private static String address(SocketAddress address) {
		String text = String.valueOf(address);
		return text.startsWith("/") ? text.substring(1) : text;
	}

	/** Yes/No on the event thread, defaulting to No; with no display nothing is trusted. */
	private static boolean confirm(String title, String message, int messageType) {
		if (GraphicsEnvironment.isHeadless()) {
			log.warn("{}: {} (no display to ask on, so rejected)", title, message);
			return false;
		}
		var accepted = new AtomicBoolean();
		Runnable ask = () -> {
			Object[] options = { tr("ssh.hostkey.trust"), tr("common.cancel") };
			int choice = JOptionPane.showOptionDialog(
					KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(), message, title,
					JOptionPane.YES_NO_OPTION, messageType, null, options, options[1]);
			accepted.set(choice == 0);
		};
		if (SwingUtilities.isEventDispatchThread()) {
			ask.run();
		} else {
			try {
				SwingUtilities.invokeAndWait(ask);
			} catch (Exception e) {
				return false;
			}
		}
		return accepted.get();
	}
}
