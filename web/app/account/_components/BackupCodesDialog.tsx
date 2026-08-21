"use client";

import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

/**
 * 백업 코드 1회 표시.
 *
 * 서버는 코드를 해시로만 보관하므로 **이 화면을 닫으면 다시 볼 수 없다.** 그 사실을 문구로 분명히 말하고,
 * 복사·다운로드를 그 자리에서 제공한다(사용자가 스크린샷에 의존하지 않도록).
 */
export function BackupCodesDialog({ codes, onClose }: { codes: string[] | null; onClose: () => void }) {
  const [acknowledged, setAcknowledged] = useState(false);
  const open = codes !== null && codes.length > 0;
  const text = (codes ?? []).join("\n");

  const close = () => {
    setAcknowledged(false);
    onClose();
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) close();
      }}
    >
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>백업 코드</DialogTitle>
          <DialogDescription>
            휴대폰을 잃어버렸을 때 로그인하는 마지막 수단입니다. 각 코드는 한 번만 쓸 수 있습니다.
            <b className="text-foreground"> 이 창을 닫으면 다시 볼 수 없습니다.</b>
          </DialogDescription>
        </DialogHeader>

        <ul className="grid grid-cols-2 gap-2 rounded-lg border border-border bg-muted/40 p-3">
          {(codes ?? []).map((code) => (
            <li key={code} className="tabular text-center font-mono text-sm text-foreground">
              {code}
            </li>
          ))}
        </ul>

        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={async () => {
              try {
                await navigator.clipboard.writeText(text);
                toast.success("백업 코드를 복사했습니다");
                setAcknowledged(true);
              } catch {
                toast.error("복사하지 못했습니다. 코드를 직접 선택해 복사해 주세요");
              }
            }}
          >
            복사
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              // Blob → 임시 objectURL. 파일로 남겨 두면 사용자가 안전한 곳에 옮겨 둘 수 있다.
              const blob = new Blob([`${text}\n`], {
                type: "text/plain;charset=utf-8",
              });
              const url = URL.createObjectURL(blob);
              const anchor = document.createElement("a");
              anchor.href = url;
              anchor.download = "taspa-backup-codes.txt";
              anchor.click();
              URL.revokeObjectURL(url);
              setAcknowledged(true);
            }}
          >
            다운로드
          </Button>
        </div>

        {/* 닫기를 막지는 않는다 — 갇힌 느낌을 주는 대신, 아직 저장하지 않았다는 사실만 분명히 말한다. */}
        {!acknowledged && (
          <p className="text-xs text-[color:var(--taspa-warning)]">아직 복사하거나 내려받지 않았습니다.</p>
        )}
        <DialogFooter>
          <Button onClick={close} variant={acknowledged ? "default" : "outline"}>
            {acknowledged ? "저장했습니다" : "그래도 닫기"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
