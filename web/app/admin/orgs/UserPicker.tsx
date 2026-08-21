"use client";

import { useEffect, useState } from "react";
import { useApi } from "@/lib/useApi";
import { TextField } from "../_components/kit";
import type { AdminUserSummary } from "../_lib/types";

/**
 * 이메일로 사용자를 찾아 고르는 입력.
 *
 * ★그전에는 '사용자 UUID' 텍스트 입력 하나였고 힌트가 "사용자 화면에서 상세를 열면 UUID 를 확인할 수
 * 있습니다"였다. 즉 **새 조직에 첫 조직관리자를 지정하는 유일한 발견 가능한 경로가 화면 두 개를
 * 오가며 UUID 를 복사·붙여넣는 것**이었다. 온보딩의 첫 단계가 그러면 그 뒤 화면이 아무리 좋아도
 * 제품을 쓰기 시작할 수 없다.
 *
 * 검색은 기존 `/api/admin/users?query=` 를 그대로 쓴다(전용 엔드포인트를 새로 만들지 않는다 —
 * 같은 목록·같은 권한이어야 관리자가 본 것과 고른 것이 어긋나지 않는다).
 */
export function UserPicker({
  value,
  onChange,
}: {
  /** 선택된 사용자 id. 미선택은 빈 문자열. */
  value: string;
  onChange: (userId: string, email: string) => void;
}) {
  const [term, setTerm] = useState("");
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<AdminUserSummary | null>(null);

  // 타자마다 서버를 두드리지 않는다 — 관리자 목록 조회는 가볍지 않다.
  useEffect(() => {
    const id = setTimeout(() => setQuery(term.trim()), 250);
    return () => clearTimeout(id);
  }, [term]);

  const results = useApi<AdminUserSummary[]>(
    query.length >= 2 ? `/api/admin/users?query=${encodeURIComponent(query)}` : null,
  );

  if (picked && value === picked.id) {
    return (
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-border px-3 py-2.5">
        <div className="min-w-0 flex-1">
          <p className="truncate font-medium text-foreground">{picked.email}</p>
          {picked.displayName && (
            <p className="truncate text-sm text-muted-foreground">{picked.displayName}</p>
          )}
        </div>
        <button
          type="button"
          className="text-sm text-primary underline-offset-2 hover:underline"
          onClick={() => {
            setPicked(null);
            onChange("", "");
          }}
        >
          다시 고르기
        </button>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      <TextField
        label="사용자"
        value={term}
        onChange={setTerm}
        placeholder="이메일 또는 이름으로 검색"
        hint="이미 계정이 있는 사용자만 즉시 합류시킬 수 있습니다. 계정이 없으면 조직 콘솔의 '초대' 를 쓰세요."
      />
      {query.length >= 2 && (
        <div className="max-h-56 overflow-y-auto rounded-lg border border-border">
          {results.loading && <p className="px-3 py-2 text-sm text-muted-foreground">검색 중…</p>}
          {results.error && <p className="px-3 py-2 text-sm text-destructive">{results.error}</p>}
          {!results.loading && !results.error && (results.data?.length ?? 0) === 0 && (
            <p className="px-3 py-2 text-sm text-muted-foreground">
              일치하는 계정이 없습니다. 계정이 아직 없다면 초대로 합류시켜야 합니다.
            </p>
          )}
          {results.data?.map((user) => (
            <button
              key={user.id}
              type="button"
              className="flex w-full flex-col items-start gap-0.5 border-b border-border px-3 py-2 text-left last:border-b-0 hover:bg-accent"
              onClick={() => {
                setPicked(user);
                onChange(user.id, user.email);
              }}
            >
              <span className="truncate font-medium text-foreground">{user.email}</span>
              <span className="truncate text-xs text-muted-foreground">
                {user.displayName ?? "이름 없음"} · {user.status}
                {user.role === "ADMIN" && " · 플랫폼 관리자"}
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
