package com.shieldpericias.sentinel.core

/**
 * Nível administrativo efetivo do Shield Sentinel. A distinção é obrigatória:
 * a maioria das políticas só existe em DEVICE_OWNER.
 */
enum class AdminMode {
    /** DPC provisionado como proprietário do dispositivo (todo o aparelho). */
    DEVICE_OWNER,

    /** DPC proprietário de um perfil (gerenciado). Políticas limitadas ao perfil. */
    PROFILE_OWNER,

    /** Apenas Device Admin legado (DeviceAdminReceiver ativo, sem ownership). */
    DEVICE_ADMIN,

    /** Aplicativo comum: apenas leitura de estado e orientação ao usuário. */
    NONE;

    val isOwner: Boolean get() = this == DEVICE_OWNER || this == PROFILE_OWNER
    val isDeviceOwner: Boolean get() = this == DEVICE_OWNER
}
