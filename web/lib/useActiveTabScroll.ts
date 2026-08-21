"use client";

import { useEffect, useRef } from "react";

/**
 * 가로 스크롤되는 탭 줄에서 **현재 탭을 보이는 자리로 끌어온다.**
 *
 * ★이게 없으면 모바일에서 뒤쪽 탭에 들어갔을 때 활성 표시가 화면 밖이라 **"지금 어느 탭인가"가
 * 화면에 전혀 나오지 않는다.** 조직 콘솔은 탭이 10개라 5번째('식대정책')까지만 390px 에 들어오고,
 * 관리 콘솔도 11개라 같은 상태였다 — 활동로그·정합성 대사 화면에서 강조 표시가 하나도 안 보였다.
 *
 * ★`block: "nearest"` 는 필수다. 빼면 세로로도 스크롤돼 화면이 위아래로 튄다.
 *
 * @param key 이 값이 바뀔 때마다 다시 맞춘다(보통 `pathname`).
 */
export function useActiveTabScroll<T extends HTMLElement>(key: string) {
  const ref = useRef<T>(null);
  useEffect(() => {
    ref.current?.scrollIntoView({ inline: "center", block: "nearest" });
  }, [key]);
  return ref;
}

/**
 * 값이 **생겼을 때** 그 요소를 화면 안으로 끌어온다(상세 패널·결과 영역).
 *
 * ★관리 콘솔의 "상세"는 50행짜리 표 **아래**에 렌더된다. 스크롤 이동이 없으면 눌러도 화면에
 * 아무 변화가 없어 보여서 사용자는 버튼이 고장난 줄 안다(실제로 그렇게 보였다).
 * `key` 가 비면(닫힘) 아무 것도 하지 않는다 — 닫을 때 화면이 튀면 그게 더 이상하다.
 */
/**
 * 스크롤 동작. **`prefers-reduced-motion` 을 존중한다.**
 *
 * ★`globals.css` 의 전역 규칙은 **CSS 트랜지션·애니메이션만** 덮는다 — JS 로 넘기는
 * `behavior: "smooth"` 는 그 규칙이 닿지 않아, 움직임을 줄이라고 설정한 사용자에게도 화면이 미끄러진다
 * (전정기관 장애가 있으면 실제로 불편을 준다). 여기서 한 번 더 확인한다.
 */
function scrollBehavior(): ScrollBehavior {
  if (typeof window === "undefined" || !window.matchMedia) return "smooth";
  return window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth";
}

export function useRevealOnChange<T extends HTMLElement>(key: string | null) {
  const ref = useRef<T>(null);
  useEffect(() => {
    if (key) ref.current?.scrollIntoView({ behavior: scrollBehavior(), block: "start" });
  }, [key]);
  return ref;
}
