package com.taspa.server.mfa

import dev.samstevens.totp.code.CodeVerifier
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.DefaultCodeVerifier
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.qr.QrData
import dev.samstevens.totp.qr.ZxingPngQrGenerator
import dev.samstevens.totp.secret.DefaultSecretGenerator
import dev.samstevens.totp.time.SystemTimeProvider
import dev.samstevens.totp.util.Utils
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class TotpService(
    @Value("\${taspa.mfa.totp-issuer:taspa}")
    private val issuer: String,
) {
    private val secretGenerator = DefaultSecretGenerator(32)
    private val qrGenerator = ZxingPngQrGenerator()
    private val codeVerifier: CodeVerifier

    init {
        val timeProvider = SystemTimeProvider()
        val codeGenerator = DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
        codeVerifier =
            DefaultCodeVerifier(codeGenerator, timeProvider).apply {
                setAllowedTimePeriodDiscrepancy(1)
            }
    }

    fun generateSecret(): String = secretGenerator.generate()

    /** authenticator 앱 등록용 QR 코드를 base64 PNG data URI 로 렌더링한다. */
    fun generateQrCodeDataUri(
        email: String,
        secret: String,
    ): String {
        val data =
            QrData
                .Builder()
                .label(email)
                .secret(secret)
                .issuer(issuer)
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build()
        val imageData = qrGenerator.generate(data)
        return Utils.getDataUriForImage(imageData, qrGenerator.imageMimeType)
    }

    fun verifyCode(
        secret: String,
        code: String,
    ): Boolean = codeVerifier.isValidCode(secret, code)
}
