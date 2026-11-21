package com.buildmyvpn.data

import android.content.Context
import android.util.AtomicFile
import com.buildmyvpn.data.crypto.CryptoManager
import com.buildmyvpn.data.model.ProxyMode
import com.buildmyvpn.data.model.RoutingRule
import com.buildmyvpn.data.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppPersistedState(
    val servers: List<ServerProfile> = emptyList(),
    val selectedServerId: String? = null,
    val proxyMode: ProxyMode = ProxyMode.SMART,
    val routingRule: RoutingRule = RoutingRule.GENERAL,
    val rulesUpdatedAt: String? = null
)

/**
 * Atomic, Keystore-encrypted local repository for servers and minimal user preferences.
 * Guarantees no SSH passwords are ever persisted.
 */
class ServerRepository private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: ServerRepository? = null

        fun getInstance(context: Context): ServerRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ServerRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val storageFile: AtomicFile
        get() = AtomicFile(File(context.filesDir, "build_myvpn_state.enc"))

    private val _state = MutableStateFlow(AppPersistedState())
    val state: StateFlow<AppPersistedState> = _state.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val loaded = runCatching {
                val raw = storageFile.readFully().toString(Charsets.UTF_8)
                val decrypted = CryptoManager.decrypt(raw)
                json.decodeFromString(AppPersistedState.serializer(), decrypted)
            }.getOrDefault(AppPersistedState())
            _state.value = loaded
        }
    }

    suspend fun upsertServer(profile: ServerProfile, selectImmediately: Boolean = true) {
        update { current ->
            val updatedList = current.servers.filterNot { it.id == profile.id } + profile
            current.copy(
                servers = updatedList,
                selectedServerId = if (selectImmediately || current.selectedServerId == null) {
                    profile.id
                } else {
                    current.selectedServerId
                }
            )
        }
    }

    suspend fun renameServer(serverId: String, newName: String) {
        update { current ->
            current.copy(
                servers = current.servers.map {
                    if (it.id == serverId) it.copy(name = newName.trim()) else it
                }
            )
        }
    }

    suspend fun deleteServer(serverId: String) {
        update { current ->
            val remaining = current.servers.filterNot { it.id == serverId }
            val nextSelected = if (current.selectedServerId == serverId) {
                remaining.firstOrNull()?.id
            } else {
                current.selectedServerId
            }
            current.copy(servers = remaining, selectedServerId = nextSelected)
        }
    }

    suspend fun selectServer(serverId: String) {
        update { it.copy(selectedServerId = serverId) }
    }

    suspend fun setProxyMode(mode: ProxyMode) {
        update { it.copy(proxyMode = mode) }
    }

    suspend fun setRoutingRule(rule: RoutingRule) {
        update { it.copy(routingRule = rule) }
    }

    suspend fun setRulesUpdatedAt(timestamp: String) {
        update { it.copy(rulesUpdatedAt = timestamp) }
    }

    suspend fun updateServerRegion(serverId: String, countryCode: String, regionLabel: String) {
        update { current ->
            current.copy(
                servers = current.servers.map {
                    if (it.id == serverId) it.copy(countryCode = countryCode, regionLabel = regionLabel) else it
                }
            )
        }
    }

    suspend fun addTrafficBytes(serverId: String, rxDelta: Long, txDelta: Long) {
        if (rxDelta <= 0L && txDelta <= 0L) return
        update { current ->
            current.copy(
                servers = current.servers.map {
                    if (it.id == serverId) {
                        it.copy(
                            usedRxBytes = (it.usedRxBytes + rxDelta.coerceAtLeast(0L)),
                            usedTxBytes = (it.usedTxBytes + txDelta.coerceAtLeast(0L))
                        )
                    } else it
                }
            )
        }
    }

    suspend fun resetTraffic(serverId: String) {
        update { current ->
            current.copy(
                servers = current.servers.map {
                    if (it.id == serverId) it.copy(usedRxBytes = 0L, usedTxBytes = 0L) else it
                }
            )
        }
    }

    private suspend fun update(transform: (AppPersistedState) -> AppPersistedState) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = transform(_state.value)
                val serialized = json.encodeToString(AppPersistedState.serializer(), next)
                val encrypted = CryptoManager.encrypt(serialized)
                val out = storageFile.startWrite()
                try {
                    out.write(encrypted.toByteArray(Charsets.UTF_8))
                    storageFile.finishWrite(out)
                } catch (e: Throwable) {
                    storageFile.failWrite(out)
                    throw e
                }
                _state.value = next
            }
        }
}
