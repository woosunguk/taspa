"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { ErrorNotice } from "@/components/feedback";
import { api } from "@/lib/api";
import { useMutation } from "@/lib/useApi";
import { Section, TextField } from "../../_components/console-ui";
import { orgPath } from "../../_lib/org-context";
import type { OrgView } from "../../_lib/types";

/**
 * 조직 프로필 편집. 서버는 이 경로로 **이름·타임존만** 받는다(상태·slug 는 플랫폼 관리자 전용이라
 * 여기서 바꿀 방법이 없다). 타임존은 이 대시보드의 모든 날짜 경계 기준이라, 바꾸면 이번 달 집계와
 * 청구 기간이 함께 달라진다.
 */
export function ProfileSection({
  orgId,
  initialName,
  initialTimezone,
  onSaved,
}: {
  orgId: string;
  initialName: string;
  initialTimezone: string;
  onSaved: () => void;
}) {
  const [name, setName] = useState(initialName);
  const [timezone, setTimezone] = useState(initialTimezone);

  const save = useMutation(async () =>
    api.put<OrgView>(orgPath(orgId), {
      name: name.trim(),
      timezone: timezone.trim(),
    }),
  );

  const dirty = name.trim() !== initialName || timezone.trim() !== initialTimezone;

  return (
    <Section
      title="조직 설정"
      description="이름과 타임존을 바꿉니다. 타임존은 이 화면의 '이번 달'·식수 집계·청구서의 하루 경계 기준입니다."
    >
      {save.error && <ErrorNotice message={save.error} onDismiss={save.clearError} />}

      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          id="org-name"
          label="조직 이름"
          value={name}
          onChange={setName}
          placeholder="예: 지란지교소프트"
        />
        <TextField
          id="org-timezone"
          label="타임존"
          value={timezone}
          onChange={setTimezone}
          placeholder="Asia/Seoul"
          hint="IANA 타임존 이름입니다. 예: Asia/Seoul, UTC"
        />
      </div>

      <div className="flex items-center gap-2">
        <Button
          type="button"
          disabled={save.busy || !dirty || name.trim().length === 0}
          onClick={async () => {
            const result = await save.mutate();
            if (result) {
              toast.success("조직 설정을 저장했습니다");
              onSaved();
            }
          }}
        >
          {save.busy ? "저장 중" : "저장"}
        </Button>
        {dirty && <span className="text-xs text-muted-foreground">저장하지 않은 변경이 있습니다</span>}
      </div>
    </Section>
  );
}
