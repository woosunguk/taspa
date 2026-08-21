"use client";

import { useEffect, useState } from "react";

/**
 * 1초마다 현재 시각(ms)을 돌려준다. QR 만료·재시도 대기 같은 카운트다운의 유일한 시간원이다.
 *
 * 남은 시간은 항상 `Date.now()` **차이**로 계산한다. 절대 시각을 표시하지 않으므로 기기 시계가 틀어져
 * 있어도 카운트다운은 실제로 흐른 시간만큼만 줄어든다.
 */
export function useTicker(intervalMs = 1000): number {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);

  return now;
}
