package com.example.mimusica

/** Puente con el módulo nativo del controlador USB propio (libusbaudio.so). */
object NativeUsb {
    val available: Boolean = runCatching { System.loadLibrary("usbaudio") }.isSuccess

    external fun version(): String
    external fun probe(fd: Int, descriptors: ByteArray): String

    // Laboratorio USB (etapa 3)
    external fun labOpen(fd: Int, descriptors: ByteArray): String
    external fun labClaim(): String
    external fun labVolume(): String
    external fun labSetVolume(raw: Int): String
    external fun labTone(rate: Int, ms: Int, ampDb: Int): String
    external fun labRelease(): String
    external fun labReset(): String

    fun versionOrError(): String =
        if (!available) "no se pudo cargar (¿falló la compilación nativa?)"
        else runCatching { version() }.getOrElse { "error: ${it.message}" }
}
