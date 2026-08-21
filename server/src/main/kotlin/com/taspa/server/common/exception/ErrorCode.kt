package com.taspa.server.common.exception

enum class ErrorCode(
    val message: String,
) {
    EMAIL_ALREADY_EXISTS("Email already exists"),
    INVALID_CREDENTIALS("Invalid email or password"),
    USER_NOT_FOUND("User not found"),
    VALIDATION_ERROR("Validation error"),
    PASSWORD_POLICY_VIOLATION("Password does not meet policy requirements"),
    ACCOUNT_LOCKED("Account is locked due to too many failed login attempts"),
    ACCOUNT_SUSPENDED("Account is suspended"),
    MFA_ALREADY_ENABLED("MFA is already enabled"),
    MFA_NOT_ENABLED("MFA is not enabled"),
    MFA_NOT_SETUP("MFA setup has not been initiated"),
    MFA_INVALID_CODE("Invalid MFA code"),
    RESET_TOKEN_EXPIRED("Password reset token has expired"),
    RESET_TOKEN_INVALID("Password reset token is invalid"),
    UNAUTHENTICATED("Authentication is required"),
    REAUTH_REQUIRED("Recent authentication is required for this action"),
    LAST_LOGIN_METHOD("Cannot remove the last remaining login method"),
    CLIENT_ID_ALREADY_EXISTS("Client ID already exists"),
    CLIENT_NOT_CONFIDENTIAL("Public clients do not have a client secret"),
    ADMIN_SELF_ACTION("Administrators cannot suspend themselves or revoke their own admin role"),
    NOT_FOUND("Resource not found"),
    FORBIDDEN("You do not have permission to access this resource"),
    EMAIL_UNCHANGED("The new email is the same as the current email"),
    EMAIL_CHANGE_NOT_PENDING("No pending email change to confirm"),
    VERIFICATION_CODE_INVALID("Verification code is invalid or expired"),
    CURRENT_PASSWORD_INCORRECT("Current password is incorrect"),
    CONFIRMATION_MISMATCH("Confirmation input does not match"),
    INVITATION_INVALID("Invitation is invalid or no longer available"),
    INVITATION_EXPIRED("Invitation has expired"),
    INVITATION_EMAIL_MISMATCH("This invitation was issued to a different email address"),
    DOMAIN_ALREADY_CLAIMED("This domain is already registered by an organization"),

    // 식권 QR 폐쇄루프(L1) — 기계 API(POS) 대상이라 코드가 곧 계약이다(UI i18n 은 다음 배치).
    QR_TOKEN_INVALID("QR token is invalid"),
    QR_TOKEN_EXPIRED("QR token has expired"),
    QR_TOKEN_ALREADY_USED("QR token has already been used"),
    QR_RATE_LIMITED("QR token was issued too recently, try again shortly"),
    MEAL_WINDOW_CLOSED("No meal window is open at this time"),
    DAILY_MEAL_LIMIT("Daily meal count limit has been reached"),

    /**
     * 휴직·퇴직자의 식권 발급·승인 거부. 멤버십 ACTIVE 와는 **다른 축**이다 — HR(SCIM)이 휴직을 밀면
     * employment_status 만 바뀌고 멤버십은 살아 있어, 이 코드가 없으면 회사가 계속 지불한다.
     */
    NOT_EMPLOYED("Member is not in active employment"),
    MERCHANT_SUSPENDED("Merchant is not active"),

    // 정산 집계(청구서) — FINALIZED 는 불변: 재생성·재확정 모두 이 코드로 409 거절된다.
    INVOICE_ALREADY_FINALIZED("Invoice has already been finalized and is immutable"),
    INVOICE_STALE("Invoice draft is stale; regenerate it before finalizing"),

    // 부서·사업장 식대 정책 재정의 — 노드당 상시 재정의는 1행(DB 부분 유니크). 사용자에게는
    // "새로 만들 게 아니라 기존 것을 고쳐야 한다"가 정확한 안내다.
    MEAL_POLICY_OVERRIDE_EXISTS("A standing meal policy override already exists for this scope"),

    // IAM 정책 관리(RBAC) — 정책 문서 검증·불변성·이름 충돌.
    IAM_POLICY_NOT_FOUND("IAM policy not found"),
    IAM_POLICY_IMMUTABLE("System-managed IAM policy cannot be modified or deleted"),
    IAM_VALIDATION("IAM policy document is invalid"),
    IAM_CONFLICT("An IAM resource with this name already exists"),
    IAM_LOCKOUT("This change would leave no platform administrator able to manage IAM policies"),

    // 프로토콜 수준 요청 오류 — VALIDATION_ERROR 로 뭉뚱그리면 400 과 405/415 를 구분할 수 없어
    // 클라이언트가 "입력값을 고치면 되는 오류"로 오해한다(고쳐야 할 건 메서드/Content-Type 이다).
    METHOD_NOT_ALLOWED("HTTP method is not supported for this endpoint"),
    UNSUPPORTED_MEDIA_TYPE("Request Content-Type is not supported by this endpoint"),
    INTERNAL_ERROR("Internal server error"),
}
