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
package dev.nuclr.plugin.core.panel.mongodb.panel;

import static dev.nuclr.plugin.core.panel.mongodb.Messages.tr;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.panel.mongodb.connection.AuthType;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnectionProfile;
import dev.nuclr.plugin.core.panel.mongodb.connection.MongoConnector;
import dev.nuclr.plugin.core.panel.mongodb.connection.ProxyType;
import dev.nuclr.plugin.core.panel.mongodb.connection.UriCredentials;

/** F7 "New Connection..." / F4 "Edit Connection..." form. */
public final class ConnectionEditorDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	/**
	 * Each secret is what was typed, or {@code null} when its box was left blank (on an
	 * edit: keep the stored one, unless its Remember box was unticked).
	 */
	public record Result(MongoConnectionProfile profile, String secret, boolean rememberSecret, String tlsSecret,
			boolean rememberTlsSecret, String proxySecret, boolean rememberProxySecret) {
	}

	/** Which secrets the connection being edited has stored. */
	public record Stored(boolean secret, boolean tlsSecret, boolean proxySecret) {

		static final Stored NONE = new Stored(false, false, false);
	}

	/** Tries a connection without saving it; returns the text to show, or throws with the failure. */
	@FunctionalInterface
	public interface Tester {
		String test(MongoConnectionProfile profile, MongoConnector.Secrets typed) throws Exception;
	}

	// Connection tab
	private final JTextField nameField = new JTextField(36);
	private final JTextField uriField = new JTextField(36);
	private final JComboBox<AuthType> authCombo = new JComboBox<>(AuthType.values());
	private final JLabel usernameLabel = new JLabel();
	private final JTextField usernameField = new JTextField(20);
	private final JLabel secretLabel = new JLabel();
	private final JPasswordField secretField = new JPasswordField(20);
	private final JCheckBox rememberBox = new JCheckBox(tr("secret.remember"), true);
	private final JTextField authSourceField = new JTextField(20);
	private final JTextField mechanismPropertiesField = new JTextField(28);

	// TLS tab
	private final JCheckBox tlsBox = new JCheckBox(tr("editor.tls"));
	private final JTextField caField = new JTextField(26);
	private final JTextField certificateField = new JTextField(26);
	private final JPasswordField certificatePasswordField = new JPasswordField(20);
	private final JCheckBox rememberCertificatePasswordBox = new JCheckBox(tr("secret.remember"), true);
	private final JCheckBox invalidHostnamesBox = new JCheckBox(tr("editor.tls.invalid.hostnames"));
	private final JCheckBox invalidCertificatesBox = new JCheckBox(tr("editor.tls.invalid.certificates"));

	// Proxy / SSH tab
	private final JComboBox<ProxyType> proxyCombo = new JComboBox<>(ProxyType.values());
	private final JTextField proxyHostField = new JTextField(24);
	private final JTextField proxyPortField = new JTextField(6);
	private final JTextField proxyUsernameField = new JTextField(20);
	private final JLabel proxyPasswordLabel = new JLabel();
	private final JPasswordField proxyPasswordField = new JPasswordField(20);
	private final JCheckBox rememberProxyBox = new JCheckBox(tr("secret.remember"), true);
	private final JTextField sshKeyField = new JTextField(22);
	private final JButton sshKeyBrowse = new JButton(tr("editor.browse"));

	private final JTextArea statusArea = new JTextArea(3, 1);
	private final JButton testButton = new JButton(tr("editor.test"));

	private final Tester tester;
	private String idBeingEdited;
	private Result result;

	private ConnectionEditorDialog(Window owner, String title, Tester tester) {
		super(owner, title, ModalityType.APPLICATION_MODAL);
		this.tester = tester;
		buildUi();
	}

	public static Result showNew(Window owner, Tester tester) {
		var dialog = new ConnectionEditorDialog(owner, tr("editor.title.new"), tester);
		dialog.uriField.setText("mongodb://localhost:27017");
		dialog.onAuthChanged();
		dialog.onProxyChanged();
		dialog.setVisible(true);
		return dialog.result;
	}

	public static Result showEdit(Window owner, MongoConnectionProfile existing, Stored stored, Tester tester) {
		var dialog = new ConnectionEditorDialog(owner, tr("editor.title.edit"), tester);
		dialog.populateFrom(existing, stored == null ? Stored.NONE : stored);
		dialog.setVisible(true);
		return dialog.result;
	}

	private void populateFrom(MongoConnectionProfile p, Stored stored) {
		idBeingEdited = p.getId();
		nameField.setText(p.getName());
		uriField.setText(p.getConnectionString());
		authCombo.setSelectedItem(p.getEffectiveAuthType());
		usernameField.setText(p.getUsername());
		authSourceField.setText(p.getAuthSource());
		mechanismPropertiesField.setText(p.getAuthMechanismProperties());
		rememberBox.setSelected(stored.secret() || !p.getEffectiveAuthType().takesSecret());
		keepTooltip(secretField, stored.secret());

		tlsBox.setSelected(p.isTls());
		caField.setText(p.getTlsCaFile());
		certificateField.setText(p.getTlsCertificateFile());
		invalidHostnamesBox.setSelected(p.isTlsAllowInvalidHostnames());
		invalidCertificatesBox.setSelected(p.isTlsAllowInvalidCertificates());
		rememberCertificatePasswordBox.setSelected(stored.tlsSecret() || !p.hasClientCertificate());
		keepTooltip(certificatePasswordField, stored.tlsSecret());

		proxyCombo.setSelectedItem(p.getEffectiveProxyType());
		proxyHostField.setText(p.getProxyHost());
		proxyPortField.setText(p.getProxyPort());
		proxyUsernameField.setText(p.getProxyUsername());
		sshKeyField.setText(p.getSshKeyPath());
		rememberProxyBox.setSelected(stored.proxySecret() || p.getEffectiveProxyType() == ProxyType.NONE);
		keepTooltip(proxyPasswordField, stored.proxySecret());

		onAuthChanged();
		onProxyChanged();
	}

	private static void keepTooltip(JPasswordField field, boolean stored) {
		if (stored) {
			field.setToolTipText(tr("editor.secret.keep.tooltip"));
		}
	}

	// ------------------------------------------------------------------
	// Layout
	// ------------------------------------------------------------------

	private void buildUi() {
		authCombo.setRenderer(labels(value -> value instanceof AuthType type
				? tr("auth." + type.name().toLowerCase(Locale.ROOT)) : value));
		proxyCombo.setRenderer(labels(value -> value instanceof ProxyType type
				? tr("proxy." + type.name().toLowerCase(Locale.ROOT)) : value));
		authCombo.addActionListener(e -> onAuthChanged());
		proxyCombo.addActionListener(e -> onProxyChanged());
		testButton.addActionListener(e -> runTest());
		uriField.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				absorbUriCredentials();
			}
		});

		nameField.setToolTipText(tr("editor.name.tooltip"));
		uriField.setToolTipText(tr("editor.uri.tooltip"));
		authSourceField.setToolTipText(tr("editor.auth.source.tooltip"));
		mechanismPropertiesField.setToolTipText(tr("editor.mechanism.properties.tooltip"));
		tlsBox.setToolTipText(tr("editor.tls.tooltip"));
		caField.setToolTipText(tr("editor.tls.ca.tooltip"));
		certificateField.setToolTipText(tr("editor.tls.certificate.tooltip"));
		certificatePasswordField.setToolTipText(tr("editor.tls.certificate.password.tooltip"));
		invalidCertificatesBox.setToolTipText(tr("editor.tls.invalid.certificates.tooltip"));
		proxyHostField.setToolTipText(tr("editor.proxy.host.tooltip"));
		proxyPortField.setToolTipText(tr("editor.proxy.port.tooltip"));
		proxyUsernameField.setToolTipText(tr("editor.proxy.username.tooltip"));
		sshKeyField.setToolTipText(tr("editor.ssh.key.tooltip"));

		var connection = form();
		int row = 0;
		row = addRow(connection, row, new JLabel(tr("editor.name")), nameField);
		row = addRow(connection, row, new JLabel(tr("editor.uri")), uriField);
		row = addRow(connection, row, null, hint(tr("editor.uri.hint")));
		row = addRow(connection, row, new JLabel(tr("editor.auth")), authCombo);
		row = addRow(connection, row, usernameLabel, usernameField);
		row = addRow(connection, row, secretLabel, secretField);
		row = addRow(connection, row, null, rememberBox);
		row = addRow(connection, row, new JLabel(tr("editor.auth.source")), authSourceField);
		addRow(connection, row, new JLabel(tr("editor.mechanism.properties")), mechanismPropertiesField);

		var tls = form();
		row = 0;
		row = addRow(tls, row, null, tlsBox);
		row = addRow(tls, row, new JLabel(tr("editor.tls.ca")), withBrowse(caField, tr("editor.tls.ca")));
		row = addRow(tls, row, new JLabel(tr("editor.tls.certificate")),
				withBrowse(certificateField, tr("editor.tls.certificate")));
		row = addRow(tls, row, new JLabel(tr("editor.tls.certificate.password")), certificatePasswordField);
		row = addRow(tls, row, null, rememberCertificatePasswordBox);
		row = addRow(tls, row, null, invalidHostnamesBox);
		addRow(tls, row, null, invalidCertificatesBox);

		var proxy = form();
		row = 0;
		row = addRow(proxy, row, new JLabel(tr("editor.proxy.type")), proxyCombo);
		row = addRow(proxy, row, new JLabel(tr("editor.proxy.host")), proxyHostField);
		row = addRow(proxy, row, new JLabel(tr("editor.proxy.port")), proxyPortField);
		row = addRow(proxy, row, new JLabel(tr("editor.proxy.username")), proxyUsernameField);
		row = addRow(proxy, row, proxyPasswordLabel, proxyPasswordField);
		row = addRow(proxy, row, null, rememberProxyBox);
		var keyRow = new JPanel(new BorderLayout(4, 0));
		keyRow.add(sshKeyField, BorderLayout.CENTER);
		keyRow.add(sshKeyBrowse, BorderLayout.EAST);
		sshKeyBrowse.addActionListener(e -> browse(sshKeyField, tr("editor.ssh.key")));
		row = addRow(proxy, row, new JLabel(tr("editor.ssh.key")), keyRow);
		addRow(proxy, row, null, hint(tr("editor.proxy.hint")));

		var tabs = new JTabbedPane();
		tabs.addTab(tr("editor.tab.connection"), top(connection));
		tabs.addTab(tr("editor.tab.tls"), top(tls));
		tabs.addTab(tr("editor.tab.proxy"), top(proxy));

		statusArea.setEditable(false);
		statusArea.setLineWrap(true);
		statusArea.setWrapStyleWord(true);
		statusArea.setOpaque(false);
		var statusScroll = new JScrollPane(statusArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		statusScroll.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

		var ok = new JButton(tr("common.ok"));
		var cancel = new JButton(tr("common.cancel"));
		ok.addActionListener(e -> onOk());
		cancel.addActionListener(e -> dispose());
		var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(testButton);
		buttons.add(ok);
		buttons.add(cancel);

		var south = new JPanel(new BorderLayout());
		south.add(statusScroll, BorderLayout.CENTER);
		south.add(buttons, BorderLayout.SOUTH);

		var content = new JPanel(new BorderLayout());
		content.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
		content.add(tabs, BorderLayout.CENTER);
		content.add(south, BorderLayout.SOUTH);
		setContentPane(content);
		getRootPane().setDefaultButton(ok);
		getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
				JComponent.WHEN_IN_FOCUSED_WINDOW);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		pack();
		setResizable(false);
		setLocationRelativeTo(getOwner());
	}

	private interface Labeler {
		Object label(Object value);
	}

	private static DefaultListCellRenderer labels(Labeler labeler) {
		return new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focus) {
				return super.getListCellRendererComponent(list, labeler.label(value), index, selected, focus);
			}
		};
	}

	private static JPanel form() {
		var form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
		return form;
	}

	/** Keeps a short form at the top of its tab rather than centred in it. */
	private static JPanel top(JPanel form) {
		var wrapper = new JPanel(new BorderLayout());
		wrapper.add(form, BorderLayout.NORTH);
		return wrapper;
	}

	private static JLabel hint(String text) {
		var label = new JLabel("<html><div style='width:300px'>" + text + "</div></html>");
		label.setEnabled(false);
		return label;
	}

	private JPanel withBrowse(JTextField field, String title) {
		var browse = new JButton(tr("editor.browse"));
		browse.addActionListener(e -> browse(field, title));
		var row = new JPanel(new BorderLayout(4, 0));
		row.add(field, BorderLayout.CENTER);
		row.add(browse, BorderLayout.EAST);
		return row;
	}

	private void browse(JTextField field, String title) {
		var chooser = new JFileChooser();
		chooser.setDialogTitle(title.replace(":", ""));
		if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
			field.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private static int addRow(JPanel form, int row, JLabel label, JComponent field) {
		var c = new GridBagConstraints();
		c.gridy = row;
		c.insets = new Insets(3, 3, 3, 3);
		c.anchor = GridBagConstraints.WEST;
		if (label != null) {
			c.gridx = 0;
			form.add(label, c);
		}
		c.gridx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = 1;
		form.add(field, c);
		return row + 1;
	}

	// ------------------------------------------------------------------
	// Behaviour
	// ------------------------------------------------------------------

	private AuthType authType() {
		AuthType type = (AuthType) authCombo.getSelectedItem();
		return type == null ? AuthType.NONE : type;
	}

	private ProxyType proxyType() {
		ProxyType type = (ProxyType) proxyCombo.getSelectedItem();
		return type == null ? ProxyType.NONE : type;
	}

	private void onAuthChanged() {
		AuthType type = authType();
		usernameField.setEnabled(type != AuthType.NONE);
		usernameLabel.setText(type == AuthType.AWS ? tr("editor.username.aws")
				: type == AuthType.X509 ? tr("editor.username.x509") : tr("editor.username"));
		boolean secret = type.takesSecret();
		secretField.setEnabled(secret);
		rememberBox.setEnabled(secret);
		secretLabel.setText(switch (type) {
			case AWS -> tr("editor.secret.aws");
			case OIDC -> tr("editor.secret.oidc");
			default -> tr("editor.secret.password");
		});
		authSourceField.setEnabled(type != AuthType.NONE && !type.external());
		mechanismPropertiesField.setEnabled(type != AuthType.NONE);
	}

	private void onProxyChanged() {
		ProxyType type = proxyType();
		boolean any = type != ProxyType.NONE;
		proxyHostField.setEnabled(any);
		proxyPortField.setEnabled(any);
		proxyUsernameField.setEnabled(any);
		proxyPasswordField.setEnabled(any);
		rememberProxyBox.setEnabled(any);
		sshKeyField.setEnabled(type == ProxyType.SSH);
		sshKeyBrowse.setEnabled(type == ProxyType.SSH);
		proxyPasswordLabel.setText(type == ProxyType.SSH ? tr("editor.ssh.password") : tr("editor.proxy.password"));
	}

	/**
	 * A pasted {@code mongodb+srv://user:pass@host/?authSource=admin} is split up: the
	 * credentials and auth options go to their fields, the string keeps the rest, so the
	 * password never lands in the saved profile.
	 */
	void absorbUriCredentials() {
		UriCredentials.Extracted found = UriCredentials.extract(uriField.getText());
		if (!found.found()) {
			return;
		}
		uriField.setText(found.connectionString());
		AuthType mechanism = AuthType.ofMechanism(found.mechanism());
		if (mechanism != null) {
			authCombo.setSelectedItem(mechanism);
		} else if (found.username() != null && authType() == AuthType.NONE) {
			authCombo.setSelectedItem(AuthType.DEFAULT);
		}
		if (found.username() != null) {
			usernameField.setText(found.username());
		}
		if (found.password() != null) {
			secretField.setText(found.password());
		}
		if (found.authSource() != null) {
			authSourceField.setText(found.authSource());
		}
		if (found.mechanismProperties() != null) {
			mechanismPropertiesField.setText(found.mechanismProperties());
		}
		onAuthChanged();
		statusArea.setText(tr("editor.uri.credentials.moved"));
	}

	private MongoConnectionProfile profileFromForm() {
		return MongoConnectionProfile.builder()
				.id(idBeingEdited)
				.name(nameField.getText().trim())
				.connectionString(uriField.getText().trim())
				.authType(authType())
				.username(blankToNull(usernameField.getText()))
				.authSource(blankToNull(authSourceField.getText()))
				.authMechanismProperties(blankToNull(mechanismPropertiesField.getText()))
				.tls(tlsBox.isSelected())
				.tlsCaFile(blankToNull(caField.getText()))
				.tlsCertificateFile(blankToNull(certificateField.getText()))
				.tlsAllowInvalidHostnames(invalidHostnamesBox.isSelected())
				.tlsAllowInvalidCertificates(invalidCertificatesBox.isSelected())
				.proxyType(proxyType())
				.proxyHost(blankToNull(proxyHostField.getText()))
				.proxyPort(blankToNull(proxyPortField.getText()))
				.proxyUsername(blankToNull(proxyUsernameField.getText()))
				.sshKeyPath(blankToNull(sshKeyField.getText()))
				.build();
	}

	private static String typed(JPasswordField field) {
		String typed = new String(field.getPassword());
		return typed.isBlank() ? null : typed;
	}

	private String validateForm() {
		if (nameField.getText().isBlank()) {
			return tr("editor.error.name");
		}
		String uri = uriField.getText().trim();
		if (!uri.startsWith("mongodb://") && !uri.startsWith("mongodb+srv://")) {
			return tr("editor.error.uri");
		}
		AuthType auth = authType();
		if ((auth.passwordRequired() || auth == AuthType.GSSAPI) && usernameField.getText().isBlank()) {
			return tr("editor.error.username");
		}
		if (proxyType() != ProxyType.NONE && proxyHostField.getText().isBlank()) {
			return tr("editor.error.proxy.host");
		}
		return null;
	}

	private void runTest() {
		absorbUriCredentials();
		String problem = validateForm();
		if (problem != null) {
			statusArea.setText(problem);
			return;
		}
		MongoConnectionProfile profile = profileFromForm();
		var secrets = new MongoConnector.Secrets(typed(secretField), typed(certificatePasswordField),
				typed(proxyPasswordField));
		testButton.setEnabled(false);
		statusArea.setText(tr("editor.testing"));
		Thread.ofVirtual().name("mongodb-test-connection").start(() -> {
			String text;
			try {
				text = tester.test(profile, secrets);
			} catch (Exception e) {
				text = tr("editor.test.failed", e.getMessage());
			}
			String shown = text;
			SwingUtilities.invokeLater(() -> {
				statusArea.setText(shown);
				statusArea.setCaretPosition(0);
				testButton.setEnabled(true);
			});
		});
	}

	private void onOk() {
		absorbUriCredentials();
		String problem = validateForm();
		if (problem != null) {
			statusArea.setText(problem);
			return;
		}
		MongoConnectionProfile profile = profileFromForm();
		boolean takesSecret = profile.getEffectiveAuthType().takesSecret();
		boolean certificate = profile.hasClientCertificate();
		boolean proxy = profile.getEffectiveProxyType() != ProxyType.NONE;
		result = new Result(profile,
				takesSecret ? typed(secretField) : null, takesSecret && rememberBox.isSelected(),
				certificate ? typed(certificatePasswordField) : null,
				certificate && rememberCertificatePasswordBox.isSelected(),
				proxy ? typed(proxyPasswordField) : null, proxy && rememberProxyBox.isSelected());
		dispose();
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}
}
