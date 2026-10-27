package com.shieldpericias.sentinel.core.usb

/**
 * Perfis de proteção USB. Cada perfil declara QUAIS camadas pretende usar;
 * a aplicação efetiva depende do modo administrativo e do suporte do aparelho.
 */
enum class UsbProfile(
    val restrictFileTransfer: Boolean,
    val restrictPhysicalMedia: Boolean,
    val disableUsbDataSignaling: Boolean,
) {
    /** Prioriza proteções nativas (Modo de Proteção Avançada). Nenhuma política DO aplicada. */
    COMPATIBILITY(restrictFileTransfer = false, restrictPhysicalMedia = false, disableUsbDataSignaling = false),

    /** Restringe transferência de arquivos (MTP/PTP) e mídia física; mantém ADB/Android Auto possíveis. */
    ELEVATED(restrictFileTransfer = true, restrictPhysicalMedia = true, disableUsbDataSignaling = false),

    /** Desabilita sinalização de dados USB (só carga) quando o aparelho permitir. */
    MAXIMUM(restrictFileTransfer = true, restrictPhysicalMedia = true, disableUsbDataSignaling = true);

    /** Impactos previstos em acessórios/fluxos. Texto neutro para a UI. */
    val impacts: List<UsbImpact>
        get() = buildList {
            if (restrictFileTransfer) add(UsbImpact.FILE_TRANSFER_BLOCKED)
            if (restrictPhysicalMedia) add(UsbImpact.OTG_STORAGE_BLOCKED)
            if (disableUsbDataSignaling) {
                add(UsbImpact.ADB_BLOCKED)
                add(UsbImpact.ANDROID_AUTO_USB_BLOCKED)
                add(UsbImpact.OTG_ACCESSORIES_BLOCKED)
            }
        }
}

enum class UsbImpact {
    FILE_TRANSFER_BLOCKED,
    OTG_STORAGE_BLOCKED,
    ADB_BLOCKED,
    ANDROID_AUTO_USB_BLOCKED,
    OTG_ACCESSORIES_BLOCKED,
}
