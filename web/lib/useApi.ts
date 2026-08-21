"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, api } from "./api";

export interface Query<T> {
  data: T | undefined;
  loading: boolean;
  /** 사용자에게 그대로 보여줄 수 있는 문구(서버 message 또는 일반화된 안내). */
  error: string | undefined;
  reload: () => void;
}

/**
 * 조회용 훅. 모든 화면이 이걸 쓴다 — 화면마다 로딩/에러 처리를 새로 짜면 같은 제품에서 실패가 제각각으로
 * 보인다. 인증 만료·step-up 은 `api` 계층이 이미 리다이렉트로 처리하므로 여기선 나머지 오류만 다룬다.
 *
 * `path` 가 null 이면 요청하지 않는다(선행 조건이 아직 없을 때 — 예: 조직을 아직 고르지 않음).
 */
export function useApi<T>(path: string | null, deps: unknown[] = []): Query<T> {
  const [data, setData] = useState<T | undefined>(undefined);
  const [loading, setLoading] = useState<boolean>(path !== null);
  const [error, setError] = useState<string | undefined>(undefined);
  const [nonce, setNonce] = useState(0);
  // 응답이 늦게 도착한 이전 요청이 최신 상태를 덮어쓰지 않게 한다.
  const latest = useRef(0);

  const reload = useCallback(() => setNonce((n) => n + 1), []);

  /**
   * 요청을 결정하는 값들. **초기화는 effect 가 아니라 렌더 중에** 한다(React 공식 "props 가 바뀔 때
   * 상태 조정" 패턴). effect 안에서 `setLoading(true)` 를 하면 렌더가 한 번 더 도는 것도 문제지만,
   * 그 사이 한 프레임 동안 **직전 조건의 결과가 최신인 것처럼** 보인다 — 조직을 바꾼 직후 이전 조직의
   * 숫자가 스쳐 지나가는 형태라, 돈 화면에서는 그 한 프레임이 오해의 소지가 된다.
   */
  const query = [path, ...deps];
  const signature = [...query, nonce];
  const [applied, setApplied] = useState(signature);
  if (!sameSignature(applied, signature)) {
    // ★**질의 자체가 바뀌면 이전 결과를 버린다.** `loading` 만 되돌리면 화면은 옛 조건의 숫자를
    //   **새 조건 라벨 아래** 계속 보여주고, 요청이 실패하면 그 상태가 무기한 남는다
    //   (조직을 바꿨는데 이전 조직 금액이 그대로 있는 형태 — 돈 화면에서는 오해가 아니라 사고다).
    // ★단 `nonce` 만 바뀐 재조회(reload)는 **같은 질의**이므로 이전 결과를 유지한다
    //   (stale-while-revalidate — "다시 시도"가 화면을 비우지 않는다).
    const queryChanged = !sameSignature(applied.slice(0, query.length), query);
    setApplied(signature);
    setLoading(path !== null);
    setError(undefined);
    if (queryChanged || path === null) setData(undefined);
  }

  useEffect(() => {
    if (path === null) {
      // 진행 중이던 요청을 무효화한다 — 뒤늦게 도착한 응답이 "조건 없음" 화면을 덮지 않게.
      latest.current += 1;
      return;
    }
    const ticket = ++latest.current;

    api
      .get<T>(path)
      .then((result) => {
        if (ticket !== latest.current) return;
        setData(result);
        setLoading(false);
      })
      .catch((cause) => {
        if (ticket !== latest.current) return;
        // 리다이렉트 중이면(인증 만료·step-up) 화면을 오류로 바꾸지 않는다 — 곧 이동한다.
        if (cause instanceof Error && cause.message === "navigating") return;
        setError(messageOf(cause));
        setLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [path, nonce, ...deps]);

  return { data, loading, error, reload };
}

/** 변경 요청용 헬퍼 — 진행 상태와 오류를 화면이 일관되게 다루게 한다. */
export function useMutation<TArgs extends unknown[], TResult>(run: (...args: TArgs) => Promise<TResult>) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  /**
   * ★`run` 은 **항상 최신 렌더의 것**이어야 한다.
   *
   * 예전엔 `mutate` 가 `useCallback(..., [])` 로 첫 렌더의 클로저를 붙잡았다. 값을 인자로 넘기는
   * 화면은 무사했지만, 폼 상태를 클로저로 읽는 화면은 **초기값(빈 문자열)을 서버로 보냈다** —
   * 역할 이름을 입력하고 저장했는데 "역할 이름은 1~128자여야 합니다" 400 이 돌아왔다. 화면에는
   * 방금 입력한 값이 그대로 보이므로 사용자도 개발자도 원인을 짐작할 수 없는 형태다.
   *
   * "값은 인자로 넘길 것"이라는 규약으로 막을 수도 있지만, 규약은 다음 화면에서 다시 깨진다.
   * 훅에서 닫는 편이 낫다.
   */
  const latestRun = useRef(run);
  useEffect(() => {
    latestRun.current = run;
  });

  const mutate = useCallback(async (...args: TArgs): Promise<TResult | undefined> => {
    setBusy(true);
    setError(undefined);
    try {
      return await latestRun.current(...args);
    } catch (cause) {
      if (cause instanceof Error && cause.message === "navigating") return undefined;
      setError(messageOf(cause));
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);

  return { mutate, busy, error, clearError: () => setError(undefined) };
}

/**
 * React 가 effect 의존성을 비교하는 방식(`Object.is`, 얕은 비교)을 그대로 따른다 — 두 판정이 갈리면
 * 요청은 다시 나가는데 화면은 loading 으로 돌아가지 않거나(또는 그 반대) 하는 어긋남이 생긴다.
 */
export function sameSignature(a: readonly unknown[], b: readonly unknown[]): boolean {
  return a.length === b.length && a.every((value, index) => Object.is(value, b[index]));
}

export function messageOf(cause: unknown): string {
  if (cause instanceof ApiError) return cause.message;
  if (cause instanceof Error) return cause.message;
  return "요청을 처리하지 못했습니다";
}
