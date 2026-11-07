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

/** How the connection reaches the servers. */
public enum ProxyType {

	/** Straight to the hosts of the connection string. */
	NONE,

	/**
	 * Through an SSH server: a SOCKS proxy forwarded over the SSH session, so every
	 * member of a replica set or sharded cluster is reached as the SSH server sees it.
	 */
	SSH,

	/** Through a SOCKS5 proxy, with or without a username and password. */
	SOCKS5
}
