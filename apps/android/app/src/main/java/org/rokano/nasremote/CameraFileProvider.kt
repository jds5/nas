package org.rokano.nasremote

/** A dedicated provider avoids OEM issues with declaring FileProvider directly. */
class CameraFileProvider : androidx.core.content.FileProvider()
