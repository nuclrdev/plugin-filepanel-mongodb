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

import org.junit.jupiter.api.Test;

/** Timeout messages as driver 5.x words them, taken from real failures. */
class ErrorTextTest {

	@Test
	void untrustedCertificateNamesTheCauseAndTheFix() {
		String message = "Timed out while waiting for a server that matches ReadPreferenceServerSelector{readPreference=primary}. "
				+ "Client view of cluster state is {type=UNKNOWN, servers=[{address=localhost:27217, type=UNKNOWN, "
				+ "state=CONNECTING, exception={com.mongodb.MongoSocketWriteException: Exception sending message}, "
				+ "caused by {javax.net.ssl.SSLHandshakeException: (certificate_unknown) PKIX path building failed: "
				+ "sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to "
				+ "requested target}, caused by {sun.security.provider.certpath.SunCertPathBuilderException: unable to find "
				+ "valid certification path to requested target}}]";
		assertEquals("No server could be reached. localhost:27217: SunCertPathBuilderException: unable to find valid "
				+ "certification path to requested target (the server's certificate is not trusted: set its CA "
				+ "certificate on the TLS tab)", ErrorText.serversUnreachable(message));
	}

	@Test
	void everyServerOfAReplicaSetIsListed() {
		String message = "Timed out ... Client view of cluster state is {type=REPLICA_SET, servers=["
				+ "{address=db1:27017, type=UNKNOWN, state=CONNECTING, exception={com.mongodb.MongoSocketException: db1}, "
				+ "caused by {java.net.UnknownHostException: db1}}, "
				+ "{address=db2:27017, type=UNKNOWN, state=CONNECTING}]";
		assertEquals("No server could be reached. db1:27017: UnknownHostException: db1 (unknown host: check the name, "
				+ "or connect through the SSH tunnel that can see it); db2:27017: no answer yet",
				ErrorText.serversUnreachable(message));
	}
}
