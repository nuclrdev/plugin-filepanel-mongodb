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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.net.ssl.KeyManager;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Client certificates in every form mongosh and Compass take: PEM (plain or encrypted key) and PKCS#12. */
class TlsContextsTest {

	private static KeyPair keys;
	private static X509Certificate certificate;

	@TempDir
	Path dir;

	@BeforeAll
	static void makeCertificate() throws Exception {
		var generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		keys = generator.generateKeyPair();
		var name = new X500Name("CN=client,OU=apps,O=Nuclr");
		var builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE, new Date(),
				new Date(System.currentTimeMillis() + 86_400_000L), name, keys.getPublic());
		certificate = new JcaX509CertificateConverter()
				.getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
	}

	private Path pem(String fileName, Object... items) throws IOException {
		var text = new StringWriter();
		try (var writer = new JcaPEMWriter(text)) {
			for (Object item : items) {
				writer.writeObject(item);
			}
		}
		Path file = dir.resolve(fileName);
		Files.writeString(file, text.toString());
		return file;
	}

	private static void assertHasKey(KeyManager[] managers) {
		assertEquals(1, managers.length);
		assertNotNull(((javax.net.ssl.X509KeyManager) managers[0]).getPrivateKey("client"));
	}

	@Test
	void plainPemWithCertificateAndKey() throws Exception {
		Path file = pem("client.pem", certificate, keys.getPrivate());
		assertHasKey(TlsContexts.keyManagers(file, null));
	}

	@Test
	void keyFirstThenCertificate() throws Exception {
		Path file = pem("client.pem", keys.getPrivate(), certificate);
		assertHasKey(TlsContexts.keyManagers(file, null));
	}

	@Test
	void encryptedPkcs8KeyNeedsItsPassword() throws Exception {
		var encryptor = new JceOpenSSLPKCS8EncryptorBuilder(JcaPKCS8Generator.AES_256_CBC).setProvider(new BouncyCastleProvider())
				.setPassword("pw".toCharArray())
				.build();
		PKCS8EncryptedPrivateKeyInfo encrypted = new PKCS8EncryptedPrivateKeyInfo(
				new JcaPKCS8Generator(keys.getPrivate(), encryptor).generate().getContent());
		Path file = pem("client.pem", certificate, encrypted);

		assertHasKey(TlsContexts.keyManagers(file, "pw"));
		var missing = assertThrows(IOException.class, () -> TlsContexts.keyManagers(file, null));
		assertTrue(missing.getMessage().contains("encrypted"), missing.getMessage());
		assertThrows(IOException.class, () -> TlsContexts.keyManagers(file, "wrong"));
	}

	@Test
	void pkcs12File() throws Exception {
		KeyStore store = KeyStore.getInstance("PKCS12");
		store.load(null, null);
		store.setKeyEntry("client", keys.getPrivate(), "pw".toCharArray(), new Certificate[] { certificate });
		Path file = dir.resolve("client.p12");
		try (OutputStream out = Files.newOutputStream(file)) {
			store.store(out, "pw".toCharArray());
		}
		assertEquals(1, TlsContexts.keyManagers(file, "pw").length);
	}

	@Test
	void caFileAndContext() throws Exception {
		Path ca = pem("ca.pem", certificate);
		assertEquals(1, TlsContexts.trustManagers(ca).length);
		var profile = MongoConnectionProfile.builder().tlsCaFile(ca.toString())
				.tlsCertificateFile(pem("client.pem", certificate, keys.getPrivate()).toString()).build();
		assertTrue(TlsContexts.needed(profile));
		assertNotNull(TlsContexts.build(profile, null));
	}

	@Test
	void aPemWithoutAKeyIsExplained() throws Exception {
		Path file = pem("cert-only.pem", certificate);
		var e = assertThrows(IOException.class, () -> TlsContexts.keyManagers(file, null));
		assertTrue(e.getMessage().contains("private key"), e.getMessage());
		assertThrows(IOException.class, () -> TlsContexts.trustManagers(dir.resolve("missing.pem")));
	}
}
