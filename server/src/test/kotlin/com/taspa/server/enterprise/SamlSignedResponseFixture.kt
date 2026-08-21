package com.taspa.server.enterprise

import org.opensaml.core.xml.XMLObject
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.core.xml.schema.XSString
import org.opensaml.core.xml.schema.impl.XSStringBuilder
import org.opensaml.saml.saml2.core.Assertion
import org.opensaml.saml.saml2.core.Attribute
import org.opensaml.saml.saml2.core.AttributeStatement
import org.opensaml.saml.saml2.core.AttributeValue
import org.opensaml.saml.saml2.core.Audience
import org.opensaml.saml.saml2.core.AudienceRestriction
import org.opensaml.saml.saml2.core.AuthnContext
import org.opensaml.saml.saml2.core.AuthnContextClassRef
import org.opensaml.saml.saml2.core.AuthnStatement
import org.opensaml.saml.saml2.core.Conditions
import org.opensaml.saml.saml2.core.Issuer
import org.opensaml.saml.saml2.core.NameID
import org.opensaml.saml.saml2.core.Response
import org.opensaml.saml.saml2.core.Status
import org.opensaml.saml.saml2.core.StatusCode
import org.opensaml.saml.saml2.core.Subject
import org.opensaml.saml.saml2.core.SubjectConfirmation
import org.opensaml.saml.saml2.core.SubjectConfirmationData
import org.opensaml.security.x509.BasicX509Credential
import org.opensaml.xmlsec.signature.Signature
import org.opensaml.xmlsec.signature.support.SignatureConstants
import org.opensaml.xmlsec.signature.support.Signer
import org.springframework.security.saml2.core.OpenSamlInitializationService
import java.io.StringWriter
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.xml.namespace.QName
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * 자가 서명 키페어로 서명한 SAML 2.0 Response 를 생성한다(양성 경로 통합 테스트용).
 *
 * OpenSaml4AuthenticationProvider 가 실제로 검증하는 형태 — 서명된 Assertion(RSA-SHA256), Subject/NameID,
 * Conditions(AudienceRestriction), SubjectConfirmationData(Recipient/NotOnOrAfter), AttributeStatement —
 * 를 그대로 만들어 실 converter/서명검증을 관통한다. IdP-initiated(InResponseTo 없음)로 구성한다.
 */
object SamlSignedResponseFixture {
    init {
        // OpenSAML 초기화(빌더/마샬러 레지스트리). 멱등.
        OpenSamlInitializationService.initialize()
    }

    // openssl 로 생성한 RSA 2048 서명 키페어(테스트 전용). SIGNING_CERT 를 커넥션의 verification cert 로 등록한다.
    val SIGNING_CERT_PEM =
        """
        -----BEGIN CERTIFICATE-----
        MIIDLzCCAhegAwIBAgIUbmg7k+NVyuxdIPYynxstJgzO1f4wDQYJKoZIhvcNAQEL
        BQAwJzElMCMGA1UEAwwcdGVzdC1pZHAtc2lnbmluZy5leGFtcGxlLmNvbTAeFw0y
        NjA3MTcxOTAyMzFaFw0zNjA3MTQxOTAyMzFaMCcxJTAjBgNVBAMMHHRlc3QtaWRw
        LXNpZ25pbmcuZXhhbXBsZS5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK
        AoIBAQC6SyD28VRFQgBFP38DjvneYSmk9BdIE5NkcFXFs/LIbwryIn4fz8gUzH7v
        o80wOZIx57tNadpme4zU3Mb+Ppua7WQk3OWHQ452OqHpP8LL79YHCyHqkyhzFif7
        MO/86fLWB5GJBLpI0Sx/0/V4nsbJw5cUbMvq6ZjbAnLXc+23uCUEXPbAOWjamTe/
        AfVbPDfhKULwv+4FNH92fCP5p9Pi+owCli9H6cEwM2gB/9y4jDe0f3Lwg9Twh/Hb
        pUbRK+sc5W5k7Fe1s57dPmF5JvSu4TPDO99MMopAXMtCjWpbAbucmL5WZHUclEFI
        DJDi9N2x5OGHNrtjescOexaB4f/PAgMBAAGjUzBRMB0GA1UdDgQWBBSovj7KbcXs
        TQrzu/G/cbHXMo/5MTAfBgNVHSMEGDAWgBSovj7KbcXsTQrzu/G/cbHXMo/5MTAP
        BgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQADXCT4SZLxoxbUhXXr
        rP1TOqzlSDhWfo3WIqjOxd+WZZdocOlAx6L3OoB5aGJzflmUPS3uDE/SCmDfHAjn
        G8rve4O4EbULrc3wQHw1PfFEKBDst7Yq7OFJXlopfA4Sy+MGGh0oBAklS3EHNJZt
        9Bz6IB0XL48AArj3HWAaYTOPx/dUnI9DRmCW/5OAxZ/SGOUKLMQ3Ra8+2rg2yXDL
        RZ78xDStwF03+3yhmeSZsEuEWyQTNp/AdxQm+V7KbR/QqtSU1ZuvWXqSAGrVHsTh
        YHwhQmJSVUDD9VBx0NYMl3Eo6UwjHOJLWyDr3DPbNGK/JAqfdnyTisGSpdo3a0ye
        kXe5
        -----END CERTIFICATE-----
        """.trimIndent()

    private val SIGNING_KEY_PEM =
        """
        -----BEGIN PRIVATE KEY-----
        MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC6SyD28VRFQgBF
        P38DjvneYSmk9BdIE5NkcFXFs/LIbwryIn4fz8gUzH7vo80wOZIx57tNadpme4zU
        3Mb+Ppua7WQk3OWHQ452OqHpP8LL79YHCyHqkyhzFif7MO/86fLWB5GJBLpI0Sx/
        0/V4nsbJw5cUbMvq6ZjbAnLXc+23uCUEXPbAOWjamTe/AfVbPDfhKULwv+4FNH92
        fCP5p9Pi+owCli9H6cEwM2gB/9y4jDe0f3Lwg9Twh/HbpUbRK+sc5W5k7Fe1s57d
        PmF5JvSu4TPDO99MMopAXMtCjWpbAbucmL5WZHUclEFIDJDi9N2x5OGHNrtjescO
        exaB4f/PAgMBAAECggEADRGXKuusGz2xkioNqXy+t6R+TOWYuoQxC1M8GSXyCrCL
        hof37Bhh81+TKhGGI/Nt95VHlPsviI/zRb2FMjHHLbuoklNywKXzGQUs9ZV0/2SA
        Ubcstm2DYWt0+5CnzUjGMdCPG5sazF/sLL18U21kCaRwdUzak0y23epSjx3BV+ly
        9VFM5Mc9HzlWh6jb9eA9f254q0J7ysURsLimOyMliOyOxD4a+S/K2OoFlVdBlWq5
        UNZpDzxtGwZ/pWgWQkw3AGKY2uiiOOgBzHOlzRZCbSKqpAU4pi5AoCm5ziY1OczF
        AF+1yhe6ercUWDTJFzMQbSk7B1k/Gh2oHJpNQyGmLQKBgQDlxy1PMOrVrJAh5rnm
        n0zmOKax/i0txPvkZGUqS1yzTy/W6AQ+7bSMgW0DKOJc8kwZsbwr3EvCuUSjJHl7
        2giiTIgNAsAi2Fa38s2OKhNORIeBAwzrsdATeUe2fGh3Hglu6ZybI55dqMjCPeFB
        2FW7zeQ+mL3usnqTHRtOVI4qwwKBgQDPjZPCgXZktwQ6EQANsN7mUh7VVy7Tcnt0
        aS+eEThaScjjG81eK3KYAjjuzxnwjK9/OpmFhMgohPZC42XVIkPs7q2pPcjq3ErL
        N1A1k0jMV01EBApGdsf73QeNVVvcKEi+c0zeMOEtVrKTNW1cBC6B9Uh76+xJpoSh
        gu7PTuGOBQKBgEutwzWO5fmEQWB5DodvtjMpO3XqQlUNyOzbSBqbAagpD5eXiTZ0
        sf+I7S+aOpvkTb40tMY5Y1Z6EKou7Dgngqp5zGGEo2WWnVEujhchsoQXvA14xLuo
        L+EKE919siZxZSf7l1HddF5ipeSb3uIB75LQaDlZs0y3yflEH+c3oC9jAoGAdcQ9
        bQYMaYU3DsOKpjoPn9xExoSbezrgNeNhM21N7l0jcUnbM22vrfTE3SwB6e3JvoU/
        ivUWpXccTEO/CjpRFGUxgHAgSrMIdVpDVsLlcZyvqMIqGmVXf9+gY19lqfvpmrbN
        jN1rR1CELucH0EhHI1JL+JSbFAc6BnLGkjaN4QkCgYBvLTUdQ21Uqa03e8oWz9/g
        o/ujHdcVOUMbMWoOZp38XqavtSmFM2PcRZXkTIyTINmgAcSmKPQcIKm6Hjlr/xjA
        PKz/USh4WrlFdPmnb1retXjF9k/ZCdlkNZQZ+3aZlbRuP04uttQ+pkTqzjCeYcw6
        wwI2tqF0eH6lVBxzeF/smw==
        -----END PRIVATE KEY-----
        """.trimIndent()

    val signingCertificate: X509Certificate by lazy {
        val body = SIGNING_CERT_PEM.trimIndent().toByteArray(Charsets.UTF_8)
        CertificateFactory
            .getInstance("X.509")
            .generateCertificate(body.inputStream()) as X509Certificate
    }

    private val signingKey: PrivateKey by lazy {
        val base64 =
            SIGNING_KEY_PEM
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(base64)
        KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /**
     * 서명된 SAML Response XML(문자열, 미인코딩) 을 만든다. Saml2AuthenticationToken 의 2번째 인자로 넘긴다.
     */
    fun signedResponse(
        idpEntityId: String,
        spEntityId: String,
        acsLocation: String,
        nameId: String,
        email: String,
        displayName: String = "SAML User",
    ): String {
        val now = Instant.now()
        val notOnOrAfter = now.plus(5, ChronoUnit.MINUTES)
        val notBefore = now.minus(5, ChronoUnit.MINUTES)

        val assertion =
            build<Assertion>(Assertion.DEFAULT_ELEMENT_NAME).apply {
                id = "_" + UUID.randomUUID()
                issueInstant = now
                issuer = build<Issuer>(Issuer.DEFAULT_ELEMENT_NAME).apply { value = idpEntityId }
                subject =
                    build<Subject>(Subject.DEFAULT_ELEMENT_NAME).apply {
                        nameID =
                            build<NameID>(NameID.DEFAULT_ELEMENT_NAME).apply {
                                value = nameId
                                format = NameID.EMAIL
                            }
                        subjectConfirmations.add(
                            build<SubjectConfirmation>(SubjectConfirmation.DEFAULT_ELEMENT_NAME).apply {
                                method = SubjectConfirmation.METHOD_BEARER
                                subjectConfirmationData =
                                    build<SubjectConfirmationData>(SubjectConfirmationData.DEFAULT_ELEMENT_NAME).apply {
                                        recipient = acsLocation
                                        this.notOnOrAfter = notOnOrAfter
                                    }
                            },
                        )
                    }
                conditions =
                    build<Conditions>(Conditions.DEFAULT_ELEMENT_NAME).apply {
                        this.notBefore = notBefore
                        this.notOnOrAfter = notOnOrAfter
                        audienceRestrictions.add(
                            build<AudienceRestriction>(AudienceRestriction.DEFAULT_ELEMENT_NAME).apply {
                                audiences.add(
                                    build<Audience>(Audience.DEFAULT_ELEMENT_NAME).apply { uri = spEntityId },
                                )
                            },
                        )
                    }
                authnStatements.add(
                    build<AuthnStatement>(AuthnStatement.DEFAULT_ELEMENT_NAME).apply {
                        authnInstant = now
                        authnContext =
                            build<AuthnContext>(AuthnContext.DEFAULT_ELEMENT_NAME).apply {
                                authnContextClassRef =
                                    build<AuthnContextClassRef>(AuthnContextClassRef.DEFAULT_ELEMENT_NAME).apply {
                                        uri = "urn:oasis:names:tc:SAML:2.0:ac:classes:Password"
                                    }
                            }
                    },
                )
                attributeStatements.add(
                    build<AttributeStatement>(AttributeStatement.DEFAULT_ELEMENT_NAME).apply {
                        attributes.add(stringAttribute("email", email))
                        attributes.add(stringAttribute("name", displayName))
                    },
                )
                signature =
                    build<Signature>(Signature.DEFAULT_ELEMENT_NAME).apply {
                        signingCredential = BasicX509Credential(signingCertificate, signingKey)
                        signatureAlgorithm = SignatureConstants.ALGO_ID_SIGNATURE_RSA_SHA256
                        canonicalizationAlgorithm = SignatureConstants.ALGO_ID_C14N_EXCL_OMIT_COMMENTS
                    }
            }

        val response =
            build<Response>(Response.DEFAULT_ELEMENT_NAME).apply {
                id = "_" + UUID.randomUUID()
                issueInstant = now
                destination = acsLocation
                issuer = build<Issuer>(Issuer.DEFAULT_ELEMENT_NAME).apply { value = idpEntityId }
                status =
                    build<Status>(Status.DEFAULT_ELEMENT_NAME).apply {
                        statusCode =
                            build<StatusCode>(StatusCode.DEFAULT_ELEMENT_NAME).apply {
                                value = StatusCode.SUCCESS
                            }
                    }
                assertions.add(assertion)
            }

        // 전체 트리를 한 번 마샬링한 뒤 서명한다 — Assertion DOM 이 Response 문서 안에서 확정된 후 서명해야
        // 재마샬링에 의한 서명 무효화가 없다.
        val marshaller =
            XMLObjectProviderRegistrySupport.getMarshallerFactory().getMarshaller(response)
                ?: error("no marshaller registered for Response")
        val element = marshaller.marshall(response)
        Signer.signObject(assertion.signature!!)

        val writer = StringWriter()
        TransformerFactory
            .newInstance()
            .newTransformer()
            .apply {
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            }.transform(DOMSource(element), StreamResult(writer))
        return writer.toString()
    }

    private fun stringAttribute(
        name: String,
        value: String,
    ): Attribute =
        build<Attribute>(Attribute.DEFAULT_ELEMENT_NAME).apply {
            this.name = name
            attributeValues.add(
                (
                    XMLObjectProviderRegistrySupport
                        .getBuilderFactory()
                        .getBuilder(XSString.TYPE_NAME) as XSStringBuilder
                ).buildObject(AttributeValue.DEFAULT_ELEMENT_NAME, XSString.TYPE_NAME)
                    .apply { this.value = value },
            )
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T : XMLObject> build(qname: QName): T =
        XMLObjectProviderRegistrySupport.getBuilderFactory().getBuilder(qname)!!.buildObject(qname) as T
}
