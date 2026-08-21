"use client";

import Link from "next/link";
import { AlertTriangleIcon, ChevronRightIcon, QrCodeIcon } from "lucide-react";
import { AppShell } from "@/components/AppShell";
import { ErrorNotice } from "@/components/feedback";
import { Card, CardContent } from "@/components/ui/card";
import { PageHeader, StatusLine } from "@/components/data-display";
import { displayNameOf, useSession } from "@/lib/session";

/**
 * 진입 화면. 로그인 상태와 권한에 따라 갈 곳을 제시한다.
 * 자동 리다이렉트하지 않는 이유: 여러 역할을 가진 사용자(직원이면서 조직관리자)가 매번 원치 않는 화면으로
 * 튕기지 않게 하고, 자기가 어떤 권한을 갖고 있는지 한눈에 보이게 하기 위해서다.
 */
export default function Home() {
  const session = useSession();

  return (
    <AppShell>
      {session.status === "anonymous" ? (
        <div className="mx-auto max-w-md py-16 text-center">
          <h1 className="text-display text-foreground">사내 계정과 식대를 한곳에서</h1>
          <p className="mt-2 text-sm text-muted-foreground">
            taspa 계정으로 로그인하면 식권 발급, 조직 관리, 사용 내역을 모두 이용할 수 있습니다.
          </p>
          {/*
            ★글자색은 `text-primary-foreground` 다 — `text-white` 를 박아 두면 **다크에서 안 읽힌다**
            (다크의 브랜드 파랑은 밝은 하늘색이라 그 위 흰 글씨는 대비가 무너진다).
          */}
          <a
            href="/login"
            className="mt-6 inline-flex rounded-lg bg-primary px-5 py-2.5 text-sm font-medium text-primary-foreground hover:bg-brand-hover"
          >
            로그인
          </a>
        </div>
      ) : session.status === "authenticated" ? (
        /*
          ★폭은 좁히되 **중앙 정렬하지 않는다**. 헤더(브랜드·메뉴)는 6xl 좌측 기준인데 본문만 가운데로
          모으면 로고와 첫 카드의 좌측선이 어긋나 화면이 틀어진 것처럼 보인다(실측 176px 차이).
          목록이 짧아 오른쪽이 비는 것은 정직한 여백이지, 가운데로 밀어 감출 일이 아니다.
        */
        <div className="flex w-full max-w-3xl flex-col gap-6">
          <PageHeader title={`안녕하세요, ${displayNameOf(session.user)}님`} />

          {!session.user.emailVerified && (
            <StatusLine tone="warning" icon={<AlertTriangleIcon />}>
              이메일 인증이 완료되지 않았습니다. 일부 기능이 제한될 수 있습니다.
            </StatusLine>
          )}

          {/*
            ★진입점을 **평평하게 나열하지 않는다.**
            예전에는 카드 4개가 전부 같은 크기·같은 무게였고, 그 아래로 화면 3분의 2가 빈 공백이었다.
            매일 여는 사람에게 이 화면의 답은 하나다 — "식권". 나머지는 가끔 가는 곳이다.
            그래서 식권만 크게 두고 나머지는 한 줄짜리 목록으로 내린다.
          */}
          <PrimaryEntry
            href="/meal"
            title="식권 QR"
            description="구내식당·제휴 매장 계산대에서 보여주면 회사 식대로 결제됩니다."
          />

          <div className="flex flex-col gap-2">
            <p className="text-label text-muted-foreground">그 밖의 메뉴</p>
            <div className="flex flex-col gap-2">
              {session.user.manageableOrgs && (
                <Entry
                  href="/console"
                  title="조직 관리"
                  description="구성원·부서·사업장·초대, 식수 예측과 청구서"
                />
              )}
              {session.user.platformAdmin && (
                <Entry
                  href="/admin"
                  title="플랫폼 관리"
                  description="조직·사용자·클라이언트·가맹점과 IAM 정책"
                />
              )}
              <Entry
                href="/account"
                title="계정 설정"
                description="비밀번호·2단계 인증·패스키·활성 세션"
                external
              />
            </div>
          </div>
        </div>
      ) : session.status === "error" ? (
        // 신원 확인 실패는 익명이 아니다 — 로그인 유도로 덮으면 장애가 로그아웃으로 위장된다.
        <ErrorNotice message={session.message} onRetry={session.retry} />
      ) : null}
    </AppShell>
  );
}

/** 매일 쓰는 하나. 이 화면에서 유일하게 띄우는 덩어리다(둘이 되면 강조가 사라진다). */
function PrimaryEntry({ href, title, description }: { href: string; title: string; description: string }) {
  return (
    <Link
      href={href}
      className="surface-raised group flex items-center gap-4 rounded-xl px-5 py-5 transition-colors hover:border-primary"
    >
      <span
        aria-hidden
        className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-accent text-accent-foreground [&_svg]:size-5"
      >
        <QrCodeIcon />
      </span>
      <span className="flex min-w-0 flex-col gap-0.5">
        {/*
          ★제목은 **heading** 이다(span 이 아니라). 진입점이 화면의 구조를 이루는데 heading 이
          하나도 없으면 스크린 리더 사용자가 제목 단위로 훑을 수 없어, 링크를 하나씩 지나가며
          들어야 한다. 링크 안의 heading 은 유효하고 링크의 접근 가능 이름도 그대로다.
          (회귀: e2e web-spa "로그인 상태의 SPA 홈" — 이 시맨틱이 사라졌던 것을 그 테스트가 잡았다.)
        */}
        <h2 className="text-title text-foreground">{title}</h2>
        <span className="text-sm leading-relaxed text-muted-foreground">{description}</span>
      </span>
      <ChevronRightIcon
        aria-hidden
        className="ml-auto size-5 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5"
      />
    </Link>
  );
}

function Entry({
  href,
  title,
  description,
  external,
}: {
  href: string;
  title: string;
  description: string;
  external?: boolean;
}) {
  /*
    ★화살표를 둔다. 카드 4장이 전부 같은 모양이던 때는 **누를 수 있는 것인지**가 형태로 드러나지 않아
    설명문을 읽어야 링크임을 알 수 있었다. 이동하는 것에는 이동한다는 표시가 있어야 한다.
  */
  const body = (
    <Card size="sm" className="group transition-colors hover:border-primary">
      <CardContent className="flex items-center gap-3">
        <span className="flex min-w-0 flex-col gap-0.5">
          {/* 주 진입점(h2) 아래 단계 — 목록의 각 항목이 같은 수준이므로 h3 로 맞춘다. */}
          <h3 className="text-sm font-medium text-foreground">{title}</h3>
          <span className="text-xs leading-relaxed text-muted-foreground">{description}</span>
        </span>
        <ChevronRightIcon
          aria-hidden
          className="ml-auto size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5"
        />
      </CardContent>
    </Card>
  );

  /*
    ★래퍼에 카드와 **같은 radius** 를 준다. 그러지 않으면 포커스 링이 사각형으로 그려져 둥근 카드
    밖으로 모서리가 튀어나온다(키보드 사용자에게만 보이는 종류라 눈으로 훑을 때는 안 잡힌다).
  */
  const wrapper = "block rounded-xl";
  // 계정 화면은 서버 렌더링이라 SPA 라우팅이 아닌 실제 이동이어야 한다.
  return external ? (
    <a href={href} className={wrapper}>
      {body}
    </a>
  ) : (
    <Link href={href} className={wrapper}>
      {body}
    </Link>
  );
}
