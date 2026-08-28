"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

/**
 * QR 스캔 — 카메라 우선, **수동 입력은 언제나 열려 있는 대체 경로**.
 *
 * 디코더는 **2단**이다:
 *  1. 브라우저 내장 `BarcodeDetector` — Chrome/Edge/Android 계열. 네이티브라 가장 빠르고 배터리를 덜 쓴다.
 *  2. `jsQR`(Apache-2.0, 의존성 0) — **iOS/macOS Safari·Firefox 용 폴백.** 비디오 프레임을 캔버스로 옮겨
 *     JS 로 디코딩한다. 필요할 때만 `await import()` 로 불러오므로 Chrome 경로의 번들은 늘지 않는다.
 *
 * ★2단을 넣은 이유: 그전에는 Safari 에서 "이 브라우저는 카메라 스캔을 지원하지 않습니다" 로 끝났다.
 *   계산원이 손님 화면의 문자열을 받아 치는 경로는 남아 있었지만, **아이폰·아이패드를 계산대 단말로 쓰는
 *   매장에서는 카메라가 아예 쓸모없다** — 이 제품의 기본 동작이 절반의 단말에서 사라지는 셈이었다.
 *
 * 수동 입력은 두 경로가 모두 실패할 때(권한 거부·카메라 부재·비보안 컨텍스트)를 위해 **그대로 남긴다**.
 *
 * 카메라는 이 컴포넌트가 화면에 있는 동안만 켜진다(부모가 승인 단계로 넘어가면 언마운트 → 트랙 종료).
 * 계산대 단말은 하루 종일 켜져 있어, 스트림을 끄지 않으면 카메라 표시등이 계속 들어와 있게 된다.
 */

interface DetectedBarcode {
  rawValue: string;
}

interface BarcodeDetectorLike {
  detect(source: CanvasImageSource): Promise<DetectedBarcode[]>;
}

/** 표준화 진행 중인 API 라 TS lib.dom 에 타입이 없다 — 쓰는 만큼만 좁게 선언한다. */
type BarcodeDetectorCtor = new (options?: { formats?: string[] }) => BarcodeDetectorLike;

/** 프레임마다 돌리면 저사양 단말이 뜨거워진다. 사람이 QR 을 대는 속도에는 4회/초로 충분하다. */
const DETECT_INTERVAL_MS = 250;

/**
 * "이 단말에서는 카메라를 쓸 수 없다" — 권한 거부·카메라 부재와 **같은 경로로** 흘려보내려고 예외로
 * 만든다. 실패 처리가 한 군데면 안내 문구가 서로 어긋날 수 없다.
 *
 * 이제 이 예외는 `getUserMedia` 자체가 없을 때만 던진다(비보안 컨텍스트 또는 아주 오래된 브라우저).
 * `BarcodeDetector` 부재는 더 이상 실패가 아니다 — jsQR 폴백으로 내려간다.
 */
class UnsupportedCameraError extends Error {
  constructor() {
    super("getUserMedia unavailable");
    this.name = "UnsupportedCameraError";
  }
}

/** 프레임 → QR 문자열. null 이면 이 프레임에서는 찾지 못했다(정상 — 다음 주기에 다시 시도). */
type FrameDecoder = (video: HTMLVideoElement) => Promise<string | null>;

function nativeDecoder(): FrameDecoder | null {
  const ctor = (window as unknown as { BarcodeDetector?: BarcodeDetectorCtor }).BarcodeDetector;
  if (typeof ctor !== "function") return null;
  const detector = new ctor({ formats: ["qr_code"] });
  return async (video) => (await detector.detect(video))[0]?.rawValue?.trim() ?? null;
}

/**
 * jsQR 폴백. 캔버스 하나를 재사용하고 **긴 변 480px 로 축소**해 디코딩한다 — 전체 해상도로 매 주기
 * 픽셀을 읽으면 구형 아이패드에서 화면이 눌린다. QR 은 480px 이면 충분히 읽힌다.
 */
async function canvasDecoder(): Promise<FrameDecoder> {
  const { default: jsQR } = await import("jsqr");
  const canvas = document.createElement("canvas");
  const ctx = canvas.getContext("2d", { willReadFrequently: true });
  return async (video) => {
    if (!ctx || video.videoWidth === 0) return null;
    const scale = Math.min(1, 480 / Math.max(video.videoWidth, video.videoHeight));
    canvas.width = Math.round(video.videoWidth * scale);
    canvas.height = Math.round(video.videoHeight * scale);
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    const frame = ctx.getImageData(0, 0, canvas.width, canvas.height);
    // dontInvert: 식권 QR 은 항상 밝은 배경에 어두운 모듈이다. 반전 시도를 빼면 디코딩이 두 배 빠르다.
    return (
      jsQR(frame.data, frame.width, frame.height, { inversionAttempts: "dontInvert" })?.data?.trim() ?? null
    );
  };
}

/** 실패는 원인별로 계산원이 할 일이 다르다 — 권한은 다시 허용, 부재·미지원은 수동 입력. */
function failureNotice(cause: unknown): string {
  if (cause instanceof UnsupportedCameraError) {
    // getUserMedia 부재의 압도적 다수는 **비보안 컨텍스트**다(http + LAN IP). 원인을 바로 짚어 준다 —
    // "지원하지 않는다"고만 하면 계산원이 브라우저를 바꿔 보다가 같은 벽에 다시 부딪힌다.
    return "이 주소에서는 카메라를 열 수 없습니다. https 주소(또는 localhost)로 접속하면 카메라가 켜집니다. 지금은 아래에 코드를 직접 입력하세요.";
  }
  const name = cause instanceof Error ? cause.name : "";
  if (name === "NotAllowedError" || name === "SecurityError") {
    return "카메라 사용이 차단돼 있습니다. 브라우저 권한에서 카메라를 허용하거나, 아래에 코드를 직접 입력하세요.";
  }
  if (name === "NotFoundError" || name === "OverconstrainedError") {
    return "사용할 수 있는 카메라를 찾지 못했습니다. 아래에 코드를 직접 입력하세요.";
  }
  return "카메라를 열지 못했습니다. 아래에 코드를 직접 입력하세요.";
}

export function QrScanner({ onScan }: { onScan: (token: string) => void }) {
  const [manual, setManual] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [typed, setTyped] = useState("");
  const videoRef = useRef<HTMLVideoElement | null>(null);

  /*
   * 콜백을 카메라 effect 의 의존성에 넣으면 부모가 리렌더될 때마다 스트림이 껐다 켜진다(화면 깜빡임,
   * 안드로이드에서는 수 초간 검은 화면). 최신 콜백은 ref 로 읽는다 — 갱신은 렌더가 아니라 effect 에서.
   */
  const onScanRef = useRef(onScan);
  useEffect(() => {
    onScanRef.current = onScan;
  }, [onScan]);

  useEffect(() => {
    if (manual) return;

    let stream: MediaStream | null = null;
    let timer: number | undefined;
    let stopped = false;

    const stop = () => {
      stopped = true;
      if (timer !== undefined) window.clearInterval(timer);
      stream?.getTracks().forEach((track) => track.stop());
    };

    void (async () => {
      try {
        if (!navigator.mediaDevices?.getUserMedia) throw new UnsupportedCameraError();
        // 네이티브가 있으면 그것을, 없으면 jsQR 을 쓴다. 어느 쪽이든 아래 루프는 같다.
        const decode = nativeDecoder() ?? (await canvasDecoder());

        stream = await navigator.mediaDevices.getUserMedia({
          // 계산대 단말은 손님 쪽을 보는 후면 카메라가 기본이다.
          video: { facingMode: "environment" },
        });
        // 권한 대화상자를 띄운 사이 화면이 넘어갔을 수 있다 — 그러면 방금 얻은 스트림을 즉시 놓아준다.
        if (stopped) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        const video = videoRef.current;
        if (!video) return;
        video.srcObject = stream;
        await video.play();

        timer = window.setInterval(() => {
          // HAVE_CURRENT_DATA 미만이면 디코딩할 프레임이 아직 없다.
          if (stopped || video.readyState < 2) return;
          void decode(video)
            .then((raw) => {
              if (!raw || stopped) return;
              stop(); // 같은 QR 이 다음 프레임에서 또 잡혀 승인이 두 번 시작되는 것을 막는다.
              onScanRef.current(raw);
            })
            .catch(() => {
              /* 프레임 단위 디코딩 실패는 정상이다(초점·흔들림) — 다음 주기에 다시 시도한다. */
            });
        }, DETECT_INTERVAL_MS);
      } catch (cause) {
        if (stopped) return;
        setNotice(failureNotice(cause));
        setManual(true);
      }
    })();

    return stop;
  }, [manual]);

  const submitTyped = useCallback(() => {
    const value = typed.trim();
    if (!value) return;
    setTyped("");
    onScanRef.current(value);
  }, [typed]);

  return (
    <div className="flex flex-col gap-4">
      {!manual && (
        <div className="relative overflow-hidden rounded-2xl bg-black">
          <video
            ref={videoRef}
            className="aspect-[4/3] w-full object-cover"
            playsInline
            muted
            autoPlay
            aria-label="QR 스캔 카메라"
          />
          {/* 조준 영역 — 계산원이 QR 을 어디에 대야 하는지 알려 준다. */}
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <div className="h-48 w-48 rounded-2xl border-4 border-white/80 shadow-[0_0_0_9999px_rgba(0,0,0,0.35)]" />
          </div>
        </div>
      )}

      {notice && (
        <p role="status" className="rounded-xl bg-muted px-4 py-3 text-base text-muted-foreground">
          {notice}
        </p>
      )}

      {manual ? (
        <div className="flex flex-col gap-3">
          <label htmlFor="pos-manual-token" className="text-lg font-medium text-foreground">
            QR 코드 직접 입력
          </label>
          <Input
            id="pos-manual-token"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") submitTyped();
            }}
            placeholder="손님 앱에 표시된 코드"
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            className="h-16 rounded-xl px-4 font-mono text-lg"
          />
          <div className="flex gap-3">
            <Button
              type="button"
              size="lg"
              className="h-16 flex-1 rounded-xl text-xl"
              onClick={submitTyped}
              disabled={typed.trim().length === 0}
            >
              코드 확인
            </Button>
            <Button
              type="button"
              variant="outline"
              size="lg"
              className="h-16 rounded-xl px-5 text-lg"
              onClick={() => {
                setNotice(null);
                setManual(false);
              }}
            >
              카메라
            </Button>
          </div>
        </div>
      ) : (
        <Button
          type="button"
          variant="outline"
          size="lg"
          className="h-14 rounded-xl text-lg"
          onClick={() => {
            setNotice(null);
            setManual(true);
          }}
        >
          스캔이 안 되나요? 코드 직접 입력
        </Button>
      )}
    </div>
  );
}
