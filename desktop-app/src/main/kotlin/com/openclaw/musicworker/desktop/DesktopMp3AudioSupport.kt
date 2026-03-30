package com.openclaw.musicworker.desktop

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader

internal object DesktopMp3AudioSupport {
    fun openDecodedAudio(file: File): DecodedAudio {
        val reader = MpegAudioFileReader()
        val audioFileFormat = reader.getAudioFileFormat(file)
        val encodedStream = reader.getAudioInputStream(file)
        try {
            val decodedStream = decodeToPlayableStream(encodedStream)
            return DecodedAudio(
                audioInputStream = decodedStream,
                audioFileFormat = audioFileFormat,
            )
        } catch (error: Throwable) {
            runCatching { encodedStream.close() }
            throw error
        }
    }

    fun validateAudioFile(file: File) {
        openDecodedAudio(file).audioInputStream.use { stream ->
            stream.format
        }
    }

    private fun decodeToPlayableStream(encodedStream: AudioInputStream): AudioInputStream {
        val sourceFormat = encodedStream.format
        val targetFormat = buildTargetPcmFormat(sourceFormat)
        if (sourceFormat.matches(targetFormat)) {
            return encodedStream
        }

        return if (sourceFormat.encoding.toString().contains("MPEG", ignoreCase = true)) {
            MpegFormatConversionProvider().getAudioInputStream(targetFormat, encodedStream)
        } else {
            AudioSystem.getAudioInputStream(targetFormat, encodedStream)
        }
    }

    private fun buildTargetPcmFormat(sourceFormat: AudioFormat): AudioFormat {
        var sampleSizeBits = sourceFormat.sampleSizeInBits
        if (sampleSizeBits <= 0) {
            sampleSizeBits = 16
        }
        if (sourceFormat.encoding == AudioFormat.Encoding.ULAW || sourceFormat.encoding == AudioFormat.Encoding.ALAW) {
            sampleSizeBits = 16
        }
        if (sampleSizeBits != 8) {
            sampleSizeBits = 16
        }

        val channels = sourceFormat.channels.coerceAtLeast(1)
        val sampleRate = sourceFormat.sampleRate.takeIf { it > 0f } ?: 44_100f
        val frameSize = channels * (sampleSizeBits / 8)
        val frameRate = sourceFormat.frameRate.takeIf { it > 0f } ?: sampleRate

        return AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            sampleRate,
            sampleSizeBits,
            channels,
            frameSize,
            frameRate,
            false,
        )
    }

    data class DecodedAudio(
        val audioInputStream: AudioInputStream,
        val audioFileFormat: javax.sound.sampled.AudioFileFormat,
    )
}

internal class DesktopMp3BasicPlayer : javazoom.jlgui.basicplayer.BasicPlayer() {
    @Throws(UnsupportedAudioFileException::class, java.io.IOException::class)
    override fun initAudioInputStream(file: File) {
        val decodedAudio = DesktopMp3AudioSupport.openDecodedAudio(file)
        m_audioFileFormat = decodedAudio.audioFileFormat
        m_audioInputStream = decodedAudio.audioInputStream
    }
}
