package com.buildmyvpn.deploy

import android.content.Context
import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.buildmyvpn.data.model.ActiveTransport
import com.buildmyvpn.data.model.DeployedProtocolMode
import com.buildmyvpn.data.model.Hysteria2Credentials
import com.buildmyvpn.data.model.RealityCredentials
import com.buildmyvpn.data.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

sealed interface SshAuth {
    /**
     * Password is stored in a mutable CharArray and zeroed out in `finally` ("use-and-discard").
     */
    class Password(val secret: CharArray) : SshAuth {
        fun wipe() {
            secret.fill('\u0000')
        }
    }

    class PrivateKey(
        val pemContent: String,
        val passphrase: CharArray? = null
    ) : SshAuth {
        fun wipe() {
            passphrase?.fill('\u0000')
        }
    }
}

data class DeployRequest(
    val nickname: String,
    val host: String,
    val sshPort: Int = 22,
    val sshUsername: String = "root",
    val auth: SshAuth,
    val protocolMode: DeployedProtocolMode = DeployedProtocolMode.REALITY,
    val listenPort: Int = 443,
    val existingServerId: String? = null,
    val expectedHostKeySha256: String? = null
)

class HostKeyMismatchException(
    val expected: String,
    val actual: String
) : SecurityException("SSH host key fingerprint changed (possible MITM attack)")

class HostKeyRejectedException : SecurityException("SSH host key was rejected by user")

/**
 * End-to-end SSH deployment engine for Build MyVpn:
 * - Supports password and Ed25519/RSA/ECDSA private keys (via mwiede/jsch).
 * - Enforces TOFU (Trust On First Use) SHA-256 host key verification.
 * - Uploads embedded `assets/install_singbox.sh`, executes it, and streams JSON Lines progress.
 * - Wipes in-memory SSH password bytes immediately after execution.
 */
class SshDeployEngine(private val context: Context) {

    companion object {
        private const val REMOTE_SCRIPT_PATH = "/tmp/build-myvpn-install.sh"
        private const val CONNECT_TIMEOUT_MS = 15_000
    }

    suspend fun deploy(
        request: DeployRequest,
        onConfirmNewHostKey: suspend (fingerprintSha256: String) -> Boolean,
        onEvent: (DeployEvent) -> Unit
    ): ServerProfile = withContext(Dispatchers.IO) {
        val protocolArg = when (request.protocolMode) {
            DeployedProtocolMode.REALITY -> "reality"
            DeployedProtocolMode.DUAL -> "dual"
            DeployedProtocolMode.HYSTERIA2 -> "hy2"
        }
        val scriptArgs = "--host ${shellQuote(request.host)} --protocol $protocolArg --port ${request.listenPort}"
        var verifiedFingerprint = request.expectedHostKeySha256
        var finalNode: DeployedNodePayload? = null
        var lastError: DeployEvent? = null

        executeScript(
            host = request.host,
            sshPort = request.sshPort,
            sshUsername = request.sshUsername,
            auth = request.auth,
            expectedHostKeySha256 = request.expectedHostKeySha256,
            scriptArgs = scriptArgs,
            onConfirmNewHostKey = { fp ->
                val ok = onConfirmNewHostKey(fp)
                if (ok) verifiedFingerprint = fp
                ok
            },
            onEvent = { ev ->
                onEvent(ev)
                if (ev.status == "error") lastError = ev
                if (ev.step == "complete" && ev.status == "ok" && ev.node != null) {
                    finalNode = ev.node
                }
            }
        )

        lastError?.let { err ->
            error(DeployErrorMapper.toUserMessage(err.code, err.message))
        }

        val node = finalNode ?: error("部署未返回有效节点配置，请检查服务器日志后重试。")
        val realityCreds = node.reality?.takeIf { it.enabled }?.let {
            RealityCredentials(
                uuid = it.uuid,
                publicKey = it.publicKey,
                shortId = it.shortId,
                sni = it.sni
            )
        }
        val hy2Creds = node.hysteria2?.takeIf { it.enabled }?.let {
            Hysteria2Credentials(
                password = it.password,
                tlsMode = it.tlsMode,
                certificateSha256 = it.certificateSha256
            )
        }

        ServerProfile(
            id = request.existingServerId ?: UUID.randomUUID().toString(),
            name = request.nickname.trim().ifBlank { request.host },
            host = node.host,
            port = node.port,
            sshPort = request.sshPort,
            sshUsername = request.sshUsername,
            sshHostKeySha256 = verifiedFingerprint,
            deployedMode = request.protocolMode,
            activeTransport = if (request.protocolMode == DeployedProtocolMode.HYSTERIA2) {
                ActiveTransport.HYSTERIA2
            } else {
                ActiveTransport.REALITY
            },
            reality = realityCreds,
            hysteria2 = hy2Creds,
            bbrStatus = node.bbrStatus
        )
    }

    suspend fun disableHysteria2(
        profile: ServerProfile,
        auth: SshAuth,
        onConfirmNewHostKey: suspend (fingerprintSha256: String) -> Boolean,
        onEvent: (DeployEvent) -> Unit
    ): ServerProfile = withContext(Dispatchers.IO) {
        var lastError: DeployEvent? = null
        executeScript(
            host = profile.host,
            sshPort = profile.sshPort,
            sshUsername = profile.sshUsername,
            auth = auth,
            expectedHostKeySha256 = profile.sshHostKeySha256,
            scriptArgs = "--disable-hy2",
            onConfirmNewHostKey = onConfirmNewHostKey,
            onEvent = { ev ->
                onEvent(ev)
                if (ev.status == "error") lastError = ev
            }
        )
        lastError?.let { error(DeployErrorMapper.toUserMessage(it.code, it.message)) }
        profile.copy(
            deployedMode = DeployedProtocolMode.REALITY,
            activeTransport = ActiveTransport.REALITY,
            hysteria2 = null
        )
    }

    suspend fun uninstall(
        profile: ServerProfile,
        auth: SshAuth,
        onConfirmNewHostKey: suspend (fingerprintSha256: String) -> Boolean,
        onEvent: (DeployEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        var lastError: DeployEvent? = null
        executeScript(
            host = profile.host,
            sshPort = profile.sshPort,
            sshUsername = profile.sshUsername,
            auth = auth,
            expectedHostKeySha256 = profile.sshHostKeySha256,
            scriptArgs = "--uninstall",
            onConfirmNewHostKey = onConfirmNewHostKey,
            onEvent = { ev ->
                onEvent(ev)
                if (ev.status == "error") lastError = ev
            }
        )
        lastError?.let { error(DeployErrorMapper.toUserMessage(it.code, it.message)) }
    }

    private fun executeScript(
        host: String,
        sshPort: Int,
        sshUsername: String,
        auth: SshAuth,
        expectedHostKeySha256: String?,
        scriptArgs: String,
        onConfirmNewHostKey: suspend (fingerprintSha256: String) -> Boolean,
        onEvent: (DeployEvent) -> Unit
    ) {
        var session: Session? = null
        try {
            val jsch = JSch()
            var mismatchedActual: String? = null
            var rejectedByUser = false

            jsch.hostKeyRepository = object : HostKeyRepository {
                override fun check(hostName: String, key: ByteArray): Int {
                    val actualFp = sha256Fingerprint(key)
                    return if (expectedHostKeySha256 != null) {
                        if (actualFp == expectedHostKeySha256) {
                            HostKeyRepository.OK
                        } else {
                            mismatchedActual = actualFp
                            HostKeyRepository.CHANGED
                        }
                    } else {
                        val approved = runBlocking { onConfirmNewHostKey(actualFp) }
                        if (approved) {
                            HostKeyRepository.OK
                        } else {
                            rejectedByUser = true
                            HostKeyRepository.NOT_INCLUDED
                        }
                    }
                }

                override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
                override fun remove(host: String?, type: String?) = Unit
                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                override fun getKnownHostsRepositoryID(): String = "build-myvpn-tofu"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }

            if (auth is SshAuth.PrivateKey) {
                val passBytes = auth.passphrase?.let { String(it).toByteArray(Charsets.UTF_8) }
                jsch.addIdentity(
                    "build-myvpn-key",
                    auth.pemContent.toByteArray(Charsets.UTF_8),
                    null,
                    passBytes
                )
            }

            session = jsch.getSession(sshUsername, host, sshPort).apply {
                if (auth is SshAuth.Password) {
                    setPassword(String(auth.secret))
                }
                val config = Properties().apply {
                    put("StrictHostKeyChecking", "yes")
                    put("PreferredAuthentications", "publickey,password,keyboard-interactive")
                }
                setConfig(config)
            }

            try {
                session.connect(CONNECT_TIMEOUT_MS)
            } catch (e: JSchException) {
                if (mismatchedActual != null && expectedHostKeySha256 != null) {
                    throw HostKeyMismatchException(expectedHostKeySha256, mismatchedActual!!)
                }
                if (rejectedByUser) {
                    throw HostKeyRejectedException()
                }
                if (e.message?.contains("Auth fail", ignoreCase = true) == true) {
                    onEvent(DeployEvent(step = "ssh", status = "error", code = "ERR_AUTH_FAILED", message = e.message.orEmpty()))
                    throw IllegalStateException(DeployErrorMapper.toUserMessage("ERR_AUTH_FAILED", e.message.orEmpty()), e)
                }
                throw e
            }

            // 1. Upload embedded deploy script via SFTP
            val scriptBytes = context.assets.open("install_singbox.sh").use { it.readBytes() }
            val sftp = session.openChannel("sftp") as ChannelSftp
            try {
                sftp.connect(CONNECT_TIMEOUT_MS)
                sftp.put(ByteArrayInputStream(scriptBytes), REMOTE_SCRIPT_PATH)
                sftp.chmod(Integer.parseInt("700", 8), REMOTE_SCRIPT_PATH)
            } finally {
                sftp.disconnect()
            }

            // 2. Execute script with root or sudo, cleaning up temporary script on exit
            val isRoot = sshUsername == "root"
            val useSudoPassword = !isRoot && auth is SshAuth.Password
            val cmd = when {
                isRoot -> "bash $REMOTE_SCRIPT_PATH $scriptArgs; rc=\$?; rm -f $REMOTE_SCRIPT_PATH; exit \$rc"
                useSudoPassword -> "sudo -S -p '' bash $REMOTE_SCRIPT_PATH $scriptArgs; rc=\$?; rm -f $REMOTE_SCRIPT_PATH; exit \$rc"
                else -> "sudo -n bash $REMOTE_SCRIPT_PATH $scriptArgs; rc=\$?; rm -f $REMOTE_SCRIPT_PATH; exit \$rc"
            }

            val exec = session.openChannel("exec") as ChannelExec
            try {
                exec.setCommand(cmd)
                val stdout = exec.inputStream
                val stdin = exec.outputStream
                exec.connect(CONNECT_TIMEOUT_MS)

                if (useSudoPassword && auth is SshAuth.Password) {
                    stdin.write((String(auth.secret) + "\n").toByteArray(Charsets.UTF_8))
                    stdin.flush()
                }

                BufferedReader(InputStreamReader(stdout, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val event = DeployProtocolParser.parseLine(line!!)
                        if (event != null) {
                            onEvent(event)
                        }
                    }
                }
            } finally {
                exec.disconnect()
            }
        } finally {
            session?.disconnect()
            when (auth) {
                is SshAuth.Password -> auth.wipe()
                is SshAuth.PrivateKey -> auth.wipe()
            }
        }
    }

    private fun sha256Fingerprint(rawHostKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rawHostKey)
        val b64 = Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        return "SHA256:$b64"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
