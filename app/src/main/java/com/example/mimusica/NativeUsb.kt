package com.example.mimusica

/** Puente con el módulo nativo del controlador USB propio (libusbaudio.so). */
object NativeUsb {
    val available: Boolean = runCatching { System.loadLibrary("usbaudio") }.isSuccess

    external fun version(): String
    external fun probe(fd: Int, descriptors: ByteArray): String

    fun versionOrError(): String =
        if (!available) "no se pudo cargar (¿falló la compilación nativa?)"
        else runCatching { version() }.getOrElse { "error: ${it.message}" }
}
