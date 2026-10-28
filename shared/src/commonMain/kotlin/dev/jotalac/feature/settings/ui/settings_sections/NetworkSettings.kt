package dev.jotalac.feature.settings.ui.settings_sections

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.jotalac.core.domain.ProxyConfig
import dev.jotalac.core.domain.ProxyProtocol
import dev.jotalac.core.ui.components.SettingsDropdown
import dev.jotalac.core.utils.isIosPlatform
import dev.jotalac.feature.settings.ui.SectionTitle
import dev.jotalac.feature.settings.ui.SettingsCollapsableSection
import git_writer.shared.generated.resources.Res
import git_writer.shared.generated.resources.cloud
import git_writer.shared.generated.resources.settings_network_enable_label
import git_writer.shared.generated.resources.settings_network_host_label
import git_writer.shared.generated.resources.settings_network_password_label
import git_writer.shared.generated.resources.settings_network_port_label
import git_writer.shared.generated.resources.settings_network_protocol_label
import git_writer.shared.generated.resources.settings_network_section_subtitle
import git_writer.shared.generated.resources.settings_network_section_title
import git_writer.shared.generated.resources.settings_network_title
import git_writer.shared.generated.resources.settings_network_username_label
import org.jetbrains.compose.resources.stringResource

@Composable
fun NetworkSettings(
    proxy: ProxyConfig,
    onProxyChange: (ProxyConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionTitle(
            stringResource(Res.string.settings_network_title),
            Res.drawable.cloud
        )

        SettingsCollapsableSection(
            title = stringResource(Res.string.settings_network_section_title),
            subtitle = stringResource(Res.string.settings_network_section_subtitle),
            initiallyExpanded = false
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.settings_network_enable_label),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )

                    Switch(
                        checked = proxy.enabled,
                        onCheckedChange = { enabled -> onProxyChange(proxy.copy(enabled = enabled)) }
                    )
                }

                AnimatedVisibility(
                    visible = proxy.enabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )

                        // SOCKS needs the builtin libgit2 HTTP client on iOS, which only tunnels CONNECT
                        val protocolOptions =
                            if (isIosPlatform) listOf(ProxyProtocol.HTTP) else ProxyProtocol.entries

                        SettingsDropdown(
                            label = stringResource(Res.string.settings_network_protocol_label),
                            selected = proxy.protocol,
                            options = protocolOptions,
                            optionLabel = { it.name },
                            onSelect = { protocol -> onProxyChange(proxy.copy(protocol = protocol)) },
                        )

                        // the fields edit local state and mirror it into the settings asynchronously -
                        // bound directly to the settings state, a keystroke would land in the text from
                        // before the DataStore round trip and insert left of the caret
                        var host by remember(proxy.host) { mutableStateOf(proxy.host) }
                        var port by remember(proxy.port) {
                            mutableStateOf(if (proxy.port == 0) "" else proxy.port.toString())
                        }
                        var username by remember(proxy.username) { mutableStateOf(proxy.username) }
                        var password by remember(proxy.password) { mutableStateOf(proxy.password) }

                        OutlinedTextField(
                            value = host,
                            onValueChange = {
                                host = it
                                onProxyChange(proxy.copy(host = it))
                            },
                            label = { Text(stringResource(Res.string.settings_network_host_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = port,
                            onValueChange = { input ->
                                val digits = input.filter(Char::isDigit).take(5)
                                port = digits
                                onProxyChange(proxy.copy(port = digits.toIntOrNull() ?: 0))
                            },
                            label = { Text(stringResource(Res.string.settings_network_port_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = username,
                            onValueChange = {
                                username = it
                                onProxyChange(proxy.copy(username = it))
                            },
                            label = { Text(stringResource(Res.string.settings_network_username_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                onProxyChange(proxy.copy(password = it))
                            },
                            label = { Text(stringResource(Res.string.settings_network_password_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }
        }
    }
}
