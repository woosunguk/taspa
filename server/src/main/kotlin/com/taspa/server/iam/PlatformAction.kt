package com.taspa.server.iam

/**
 * 관리 콘솔 핸들러가 **자기 인가 좌표를 선언**한다 — 어떤 action 을, 어떤 리소스에 대해 요구하는가.
 *
 * 선언이 컨트롤러 본문이 아니라 애노테이션인 이유는 두 가지다:
 *  1. 인가가 **본문보다 먼저** 실행되는 것이 구조적으로 보장된다(인터셉터). 컨트롤러 안에서 호출하면
 *     누군가 부수효과 뒤로 옮길 수 있고, 그러면 거부된 요청이 이미 행을 만든 뒤가 된다.
 *  2. 선언 누락을 **기동 시점에** 잡을 수 있다(`PlatformSurfaceValidator`). 실행해 봐야 아는 게 아니라
 *     부팅이 깨진다.
 *
 * [resource] 는 TRN 템플릿이고 `{name}` 자리에 요청의 URI 템플릿 변수가 들어간다.
 * 예: `trn:taspa:platform:{orgId}:domain/{domainId}`.
 * 반드시 `trn:taspa:platform:` 또는 `trn:taspa:iam::` 로 시작해야 한다 — 콘솔 자원(`trn:taspa:org:...`)
 * 위에 올리면 org 위임 정책의 글롭이 관리 조작까지 넘긴다(Trn.kt 주석 참조).
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class PlatformAction(
    val action: String,
    val resource: String,
)
