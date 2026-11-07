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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class UriCredentialsTest {

	@Test
	void movesUserPasswordAndAuthOptionsOutOfAnAtlasString() {
		var found = UriCredentials.extract(
				"mongodb+srv://app:p%40ss+w0rd@cluster0.abcde.mongodb.net/shop?retryWrites=true&authSource=admin&w=majority");
		assertEquals("mongodb+srv://cluster0.abcde.mongodb.net/shop?retryWrites=true&w=majority", found.connectionString());
		assertEquals("app", found.username());
		assertEquals("p@ss+w0rd", found.password());
		assertEquals("admin", found.authSource());
		assertNull(found.mechanism());
	}

	@Test
	void takesMechanismAndPropertiesFromAReplicaSetString() {
		var found = UriCredentials.extract("mongodb://alice%40EXAMPLE.COM@h1:27017,h2:27017/?replicaSet=rs0"
				+ "&authMechanism=GSSAPI&authMechanismProperties=SERVICE_NAME:mongo,CANONICALIZE_HOST_NAME:true");
		assertEquals("mongodb://h1:27017,h2:27017/?replicaSet=rs0", found.connectionString());
		assertEquals("alice@EXAMPLE.COM", found.username());
		assertNull(found.password());
		assertEquals("GSSAPI", found.mechanism());
		assertEquals("SERVICE_NAME:mongo,CANONICALIZE_HOST_NAME:true", found.mechanismProperties());
		assertEquals(AuthType.GSSAPI, AuthType.ofMechanism(found.mechanism()));
	}

	@Test
	void leavesAStringWithoutCredentialsAlone() {
		var found = UriCredentials.extract("mongodb://localhost:27017/?directConnection=true");
		assertFalse(found.found());
		assertEquals("mongodb://localhost:27017/?directConnection=true", found.connectionString());
	}

	@Test
	void dropsTheQuestionMarkWhenOnlyAuthOptionsWereThere() {
		var found = UriCredentials.extract("mongodb://h/db?authSource=admin");
		assertEquals("mongodb://h/db", found.connectionString());
	}

	@Test
	void hostsLeaveOutCredentialsAndOptions() {
		assertEquals("mongodb://h1:27017,h2", UriCredentials.hosts("mongodb://u:p@h1:27017,h2/db?replicaSet=rs0"));
		assertEquals(List.of("h1", "h2", "::1"), UriCredentials.hostNames("mongodb://h1:27017,h2,[::1]:27018"));
	}
}
