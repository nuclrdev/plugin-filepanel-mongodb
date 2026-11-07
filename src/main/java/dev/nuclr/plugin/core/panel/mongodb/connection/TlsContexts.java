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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.EncryptionException;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

/**
 * Builds the {@link SSLContext} for a connection whose TLS needs more than the JDK's
 * defaults: a CA file (a self-signed or private CA), a client certificate (X.509
 * authentication, or a server that demands mutual TLS), or trusting anything.
 *
 * <p>A client certificate is either a PEM file holding the certificate chain and its
 * private key, the {@code tlsCertificateKeyFile} mongosh and Compass take (PKCS#1,
 * PKCS#8 or SEC1 keys, encrypted or not), or a PKCS#12 file ({@code .p12} / {@code .pfx}).
 */
public final class TlsContexts {

	private static final BouncyCastleProvider BC = new BouncyCastleProvider();
	private static final char[] NO_PASSWORD = new char[0];

	private TlsContexts() {
	}

	/** Whether the profile needs a context of its own, rather than the JDK's default. */
	public static boolean needed(MongoConnectionProfile profile) {
		return !MongoConnectionProfile.isBlank(profile.getTlsCaFile()) || profile.hasClientCertificate()
				|| profile.isTlsAllowInvalidCertificates();
	}

	/**
	 * @param certificatePassword the client certificate file's password, or {@code null}
	 */
	public static SSLContext build(MongoConnectionProfile profile, String certificatePassword)
			throws IOException, GeneralSecurityException {
		TrustManager[] trust;
		if (profile.isTlsAllowInvalidCertificates()) {
			trust = new TrustManager[] { new TrustEverything() };
		} else if (!MongoConnectionProfile.isBlank(profile.getTlsCaFile())) {
			trust = trustManagers(SshTunnel.expandHome(profile.getTlsCaFile().trim()));
		} else {
			trust = null; // the JDK's own CA list
		}
		KeyManager[] keys = profile.hasClientCertificate()
				? keyManagers(SshTunnel.expandHome(profile.getTlsCertificateFile().trim()), certificatePassword)
				: null;
		SSLContext context = SSLContext.getInstance("TLS");
		context.init(keys, trust, null);
		return context;
	}

	static TrustManager[] trustManagers(Path caFile) throws IOException, GeneralSecurityException {
		List<X509Certificate> certificates = readCertificates(caFile);
		if (certificates.isEmpty()) {
			throw new IOException(tr("tls.error.no.ca", caFile.toString()));
		}
		KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
		store.load(null, null);
		int n = 0;
		for (X509Certificate certificate : certificates) {
			store.setCertificateEntry("ca-" + n++, certificate);
		}
		TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		factory.init(store);
		return factory.getTrustManagers();
	}

	static KeyManager[] keyManagers(Path file, String password) throws IOException, GeneralSecurityException {
		if (!Files.isRegularFile(file)) {
			throw new IOException(tr("tls.error.missing", file.toString()));
		}
		char[] pass = password == null ? NO_PASSWORD : password.toCharArray();
		KeyStore store;
		if (isPkcs12(file)) {
			store = KeyStore.getInstance("PKCS12");
			try (InputStream in = Files.newInputStream(file)) {
				store.load(in, pass);
			} catch (IOException e) {
				throw new IOException(tr("tls.error.pkcs12", file.toString(), e.getMessage()), e);
			}
		} else {
			List<X509Certificate> chain = readCertificates(file);
			PrivateKey key = readPrivateKey(file, password);
			if (chain.isEmpty() || key == null) {
				throw new IOException(tr("tls.error.pem", file.toString()));
			}
			store = KeyStore.getInstance("PKCS12");
			store.load(null, null);
			store.setKeyEntry("client", key, pass, chain.toArray(new Certificate[0]));
		}
		KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		factory.init(store, pass);
		return factory.getKeyManagers();
	}

	private static boolean isPkcs12(Path file) {
		String name = file.getFileName() == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
		return name.endsWith(".p12") || name.endsWith(".pfx");
	}

	/** Every certificate in a PEM file, other blocks (a private key) skipped; or a DER file's. */
	static List<X509Certificate> readCertificates(Path file) throws IOException, GeneralSecurityException {
		if (!Files.isRegularFile(file)) {
			throw new IOException(tr("tls.error.missing", file.toString()));
		}
		var converter = new JcaX509CertificateConverter().setProvider(BC);
		var certificates = new ArrayList<X509Certificate>();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1);
				PEMParser parser = new PEMParser(reader)) {
			Object item;
			while ((item = parser.readObject()) != null) {
				if (item instanceof X509CertificateHolder holder) {
					certificates.add(converter.getCertificate(holder));
				}
			}
		} catch (IOException notPem) {
			// fall through to DER
		}
		if (certificates.isEmpty()) {
			try (InputStream in = Files.newInputStream(file)) {
				for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
					certificates.add((X509Certificate) certificate);
				}
			} catch (GeneralSecurityException notDer) {
				// leave it empty; the caller says what is missing
			}
		}
		return certificates;
	}

	/** The first private key in a PEM file, decrypted with {@code password} if it is encrypted. */
	static PrivateKey readPrivateKey(Path file, String password) throws IOException {
		var converter = new JcaPEMKeyConverter().setProvider(BC);
		char[] pass = password == null ? NO_PASSWORD : password.toCharArray();
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1);
				PEMParser parser = new PEMParser(reader)) {
			Object item;
			while ((item = parser.readObject()) != null) {
				switch (item) {
					case PEMKeyPair pair -> {
						return converter.getPrivateKey(pair.getPrivateKeyInfo());
					}
					case PrivateKeyInfo info -> {
						return converter.getPrivateKey(info);
					}
					case PEMEncryptedKeyPair encrypted -> {
						requirePassword(password, file);
						return converter.getKeyPair(encrypted.decryptKeyPair(
								new JcePEMDecryptorProviderBuilder().setProvider(BC).build(pass))).getPrivate();
					}
					case PKCS8EncryptedPrivateKeyInfo encrypted -> {
						requirePassword(password, file);
						try {
							return converter.getPrivateKey(encrypted.decryptPrivateKeyInfo(
									new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(BC).build(pass)));
						} catch (Exception e) {
							throw new IOException(tr("tls.error.key.password", file.toString()), e);
						}
					}
					default -> {
						// a certificate or parameters; keep looking
					}
				}
			}
		} catch (EncryptionException e) {
			throw new IOException(tr("tls.error.key.password", file.toString()), e);
		}
		return null;
	}

	private static void requirePassword(String password, Path file) throws IOException {
		if (password == null || password.isEmpty()) {
			throw new IOException(tr("tls.error.key.password", file.toString()));
		}
	}

	/** Trusts every certificate: the "allow invalid certificates" switch. */
	private static final class TrustEverything implements X509TrustManager {

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}
}
