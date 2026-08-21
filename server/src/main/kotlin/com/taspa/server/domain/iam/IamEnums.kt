package com.taspa.server.domain.iam

/** 정책 부착·inline 정책의 principal 종류. Phase 1 은 USER·GROUP(assumable ROLE 은 Phase 2). */
enum class IamPrincipalType { USER, GROUP }
