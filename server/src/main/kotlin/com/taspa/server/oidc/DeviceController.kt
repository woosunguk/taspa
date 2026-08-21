package com.taspa.server.oidc

import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * Device Authorization Grant(Stage 5) 사용자용 화면 — SAS 공식 1.4.2 device-flow 샘플의 DeviceController 패턴.
 *
 * 흐름:
 *  1) 기기(TV·CLI 등)가 POST /oauth2/device_authorization 로 user_code·device_code 를 발급받고,
 *     사용자에게 verification_uri(=/activate)와 user_code 를 표시한다.
 *  2) 사용자는 브라우저로 /activate 에 접속한다.
 *     - user_code 쿼리가 있으면(verification_uri_complete 진입) 바로 device_verification 으로 넘긴다.
 *     - 없으면 코드 입력 폼(device/activate)을 보여준다(폼은 POST /oauth2/device_verification).
 *  3) /oauth2/device_verification(SAS 필터)이 인증을 요구하고(미인증 → /login 게이트) user_code 를
 *     검증한 뒤 동의가 필요하면 consentPage(/oauth2/consent)로 리다이렉트한다.
 *  4) 동의(허용) 제출 → SAS 가 device_code 를 승인 처리하고 기본 성공 URL(/?success)로 리다이렉트한다.
 *
 * /activate 는 permitAll 목록에 없어 SecurityConfig 의 anyRequest().authenticated() 로 보호된다
 * (미인증 접근은 formLogin 의 /login 진입점으로 유도되고, 로그인 후 saved-request 로 복귀한다).
 */
@Controller
class DeviceController {
    private companion object {
        // user_code 는 신뢰할 수 없는 입력이다. 안전 문자([A-Za-z0-9-])만 통과시켜 redirect 문자열에
        // 그대로 이어붙여도 쿼리 주입/오픈 리다이렉트가 불가능하게 한다(RFC 8628 user_code 는 이 부분집합).
        // 패턴에 맞지 않으면(빈 값 포함) 입력 폼을 보여준다 — 사용자가 다시 입력하면 된다.
        val SAFE_USER_CODE = Regex("^[A-Za-z0-9-]{1,32}$")
    }

    /**
     * user_code 입력 화면. verification_uri_complete(…/activate?user_code=XXXX-YYYY)로 들어오면
     * 값 재입력 없이 곧바로 device verification 엔드포인트로 넘긴다.
     */
    @GetMapping("/activate")
    fun activate(
        @RequestParam(name = OAuth2ParameterNames.USER_CODE, required = false) userCode: String?,
    ): String {
        if (userCode != null && SAFE_USER_CODE.matches(userCode)) {
            return "redirect:/oauth2/device_verification?${OAuth2ParameterNames.USER_CODE}=$userCode"
        }
        return "device/activate"
    }

    /** 승인 완료 화면(/activated). */
    @GetMapping("/activated")
    fun activated(): String = "device/activated"

    /**
     * device_verification 동의 승인 후 SAS 기본 성공 리다이렉트(/?success)를 받는 지점.
     * SAS 는 커스텀 응답 핸들러가 없으면 "/?success" 로 보내므로 동일 확인 화면을 렌더링한다.
     */
    @GetMapping(value = ["/"], params = ["success"])
    fun success(): String = "device/activated"
}
