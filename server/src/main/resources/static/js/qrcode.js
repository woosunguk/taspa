/*
 * qrcode.js — 자기완결 QR 코드 생성기 (외부 의존성·CDN 없음, webauthn.js 벤더링 선례를 따르는 정적 JS).
 *
 * ISO/IEC 18004 (QR Code Model 2) 의 표준 알고리즘을 직접 구현했다.
 * 알고리즘 구조(함수 패턴 배치·마스크 평가·RS 오류정정)는 Project Nayuki 의
 * "QR Code generator library" (https://www.nayuki.io/page/qr-code-generator-library,
 * MIT License) 문서를 참고해 독자 작성한 코드다.
 *
 * License: MIT
 * Copyright (c) 2026 taspa authors
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following
 * conditions: The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software. THE SOFTWARE IS PROVIDED
 * "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 *
 * 지원 범위: Byte 모드, 오류정정 레벨 M, 버전 1~10 자동 선택(최대 213 바이트 — 식권 토큰에 충분).
 * 전역 함수 1개: window.drawQrCode(canvasEl, text) — text 를 QR 로 인코딩해 canvas 에 그린다.
 */
(function () {
    'use strict';

    // ---- GF(256) 산술 (기약다항식 0x11d) ----
    var EXP = new Array(512);
    var LOG = new Array(256);
    (function () {
        var x = 1;
        for (var i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if (x & 0x100) x ^= 0x11d;
        }
        for (var j = 255; j < 512; j++) EXP[j] = EXP[j - 255];
    })();

    function gmul(a, b) {
        if (a === 0 || b === 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    // ---- 버전별 상수 (오류정정 레벨 M, 버전 1~10) ----
    // BLOCKS[v] = [블록당 EC 코드워드 수, [블록 수, 블록당 데이터 코드워드 수], ...]
    var BLOCKS = [
        null,
        [10, [1, 16]],
        [16, [1, 28]],
        [26, [1, 44]],
        [18, [2, 32]],
        [24, [2, 43]],
        [16, [4, 27]],
        [18, [4, 31]],
        [22, [2, 38], [2, 39]],
        [22, [3, 36], [2, 37]],
        [26, [4, 43], [1, 44]],
    ];

    // 정렬 패턴 중심 좌표(버전 1~10).
    var ALIGN = [
        null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34],
        [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50],
    ];

    function dataCodewords(version) {
        var spec = BLOCKS[version];
        var total = 0;
        for (var g = 1; g < spec.length; g++) total += spec[g][0] * spec[g][1];
        return total;
    }

    function chooseVersion(byteLen) {
        for (var v = 1; v <= 10; v++) {
            // Byte 모드 오버헤드: 모드 4비트 + 길이(버전≤9: 8비트, 10: 16비트).
            var capacity = dataCodewords(v) - (v <= 9 ? 2 : 3);
            if (byteLen <= capacity) return v;
        }
        throw new Error('qrcode: data too long (' + byteLen + ' bytes)');
    }

    function utf8Bytes(str) {
        var out = [];
        for (var i = 0; i < str.length; i++) {
            var c = str.codePointAt(i);
            if (c > 0xffff) i++;
            if (c < 0x80) {
                out.push(c);
            } else if (c < 0x800) {
                out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
            } else if (c < 0x10000) {
                out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
            } else {
                out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
            }
        }
        return out;
    }

    // ---- 데이터 코드워드 구성(비트스트림 → 패딩 → 블록 분할 → RS EC → 인터리브) ----

    function buildDataCodewords(bytes, version) {
        var dataCW = dataCodewords(version);
        var bits = [];
        function put(value, length) {
            for (var i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
        }
        put(4, 4); // Byte 모드
        put(bytes.length, version <= 9 ? 8 : 16);
        for (var i = 0; i < bytes.length; i++) put(bytes[i], 8);
        // 종단자(최대 4비트) + 바이트 경계 정렬.
        var terminator = Math.min(4, dataCW * 8 - bits.length);
        put(0, terminator);
        while (bits.length % 8 !== 0) bits.push(0);
        var codewords = [];
        for (var b = 0; b < bits.length; b += 8) {
            var byte = 0;
            for (var k = 0; k < 8; k++) byte = (byte << 1) | bits[b + k];
            codewords.push(byte);
        }
        // 패딩 코드워드(0xEC, 0x11 교대).
        var pads = [0xec, 0x11];
        var p = 0;
        while (codewords.length < dataCW) codewords.push(pads[p++ % 2]);
        return codewords;
    }

    function rsGeneratorPoly(degree) {
        var g = [1];
        for (var i = 0; i < degree; i++) {
            // g(x) *= (x + α^i)
            var next = new Array(g.length + 1).fill(0);
            for (var j = 0; j < g.length; j++) {
                next[j] ^= g[j];
                next[j + 1] ^= gmul(g[j], EXP[i]);
            }
            g = next;
        }
        return g;
    }

    function rsEcBytes(data, ecLen) {
        var gen = rsGeneratorPoly(ecLen);
        var rem = data.concat(new Array(ecLen).fill(0));
        for (var i = 0; i < data.length; i++) {
            var factor = rem[i];
            if (factor !== 0) {
                for (var j = 1; j < gen.length; j++) rem[i + j] ^= gmul(gen[j], factor);
            }
        }
        return rem.slice(data.length);
    }

    function makeFinalCodewords(bytes, version) {
        var spec = BLOCKS[version];
        var ecLen = spec[0];
        var data = buildDataCodewords(bytes, version);
        var blocks = [];
        var offset = 0;
        for (var g = 1; g < spec.length; g++) {
            var count = spec[g][0];
            var dlen = spec[g][1];
            for (var b = 0; b < count; b++) {
                var d = data.slice(offset, offset + dlen);
                offset += dlen;
                blocks.push({ d: d, e: rsEcBytes(d, ecLen) });
            }
        }
        var out = [];
        var maxD = 0;
        blocks.forEach(function (blk) { if (blk.d.length > maxD) maxD = blk.d.length; });
        for (var i = 0; i < maxD; i++) {
            blocks.forEach(function (blk) { if (i < blk.d.length) out.push(blk.d[i]); });
        }
        for (var e = 0; e < ecLen; e++) {
            blocks.forEach(function (blk) { out.push(blk.e[e]); });
        }
        return out;
    }

    // ---- 행렬 구성 ----

    function encode(text) {
        var bytes = utf8Bytes(text);
        var version = chooseVersion(bytes.length);
        var size = version * 4 + 17;
        var mat = [];
        var fun = [];
        for (var r = 0; r < size; r++) {
            mat.push(new Array(size).fill(false));
            fun.push(new Array(size).fill(false));
        }

        // (x=열, y=행) 좌표계 — 함수 패턴 모듈 기록.
        function set(x, y, dark) {
            mat[y][x] = dark;
            fun[y][x] = true;
        }

        function drawFinder(cx, cy) {
            for (var dy = -4; dy <= 4; dy++) {
                for (var dx = -4; dx <= 4; dx++) {
                    var dist = Math.max(Math.abs(dx), Math.abs(dy));
                    var x = cx + dx;
                    var y = cy + dy;
                    if (x >= 0 && x < size && y >= 0 && y < size) {
                        set(x, y, dist !== 2 && dist !== 4);
                    }
                }
            }
        }

        function drawAlign(cx, cy) {
            for (var dy = -2; dy <= 2; dy++) {
                for (var dx = -2; dx <= 2; dx++) {
                    set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
                }
            }
        }

        // 포맷 정보 15비트: (EC레벨 M=0b00 << 3 | 마스크) → BCH(15,5) → 0x5412 XOR.
        function drawFormatBits(mask) {
            var data = mask; // 레벨 M 의 포맷 비트는 0b00 — 상위 2비트 0.
            var rem = data;
            for (var i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
            var bits = ((data << 10) | rem) ^ 0x5412;
            function bit(i) { return ((bits >>> i) & 1) === 1; }
            // 좌상단 복사본.
            for (var a = 0; a <= 5; a++) set(8, a, bit(a));
            set(8, 7, bit(6));
            set(8, 8, bit(7));
            set(7, 8, bit(8));
            for (var b = 9; b < 15; b++) set(14 - b, 8, bit(b));
            // 우상단·좌하단 복사본.
            for (var c = 0; c < 8; c++) set(size - 1 - c, 8, bit(c));
            for (var d = 8; d < 15; d++) set(8, size - 15 + d, bit(d));
            set(8, size - 8, true); // 다크 모듈(항상 어두움).
        }

        // 버전 정보(버전 ≥ 7): 18비트 BCH(18,6).
        function drawVersion() {
            var rem = version;
            for (var i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
            var bits = (version << 12) | rem;
            for (var j = 0; j < 18; j++) {
                var b = ((bits >>> j) & 1) === 1;
                var a = size - 11 + (j % 3);
                var c = Math.floor(j / 3);
                set(a, c, b);
                set(c, a, b);
            }
        }

        // 타이밍 패턴 → 파인더 → 정렬 → 포맷 예약 → 버전 정보 순.
        for (var t = 0; t < size; t++) {
            set(6, t, t % 2 === 0);
            set(t, 6, t % 2 === 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        var ap = ALIGN[version];
        for (var ai = 0; ai < ap.length; ai++) {
            for (var aj = 0; aj < ap.length; aj++) {
                var last = ap.length - 1;
                if ((ai === 0 && aj === 0) || (ai === 0 && aj === last) || (ai === last && aj === 0)) continue;
                drawAlign(ap[ai], ap[aj]);
            }
        }
        drawFormatBits(0); // 자리 예약(마스크 확정 후 다시 그린다).
        if (version >= 7) drawVersion();

        // 데이터 배치(우하단부터 2열 지그재그, 6열 스킵).
        var codewords = makeFinalCodewords(bytes, version);
        var bitIndex = 0;
        var totalBits = codewords.length * 8;
        for (var right = size - 1; right >= 1; right -= 2) {
            if (right === 6) right = 5;
            for (var vert = 0; vert < size; vert++) {
                for (var k = 0; k < 2; k++) {
                    var x = right - k;
                    var upward = ((right + 1) & 2) === 0;
                    var y = upward ? size - 1 - vert : vert;
                    if (!fun[y][x] && bitIndex < totalBits) {
                        mat[y][x] = ((codewords[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) === 1;
                        bitIndex++;
                    }
                    // 나머지 비트(remainder bits)는 밝음(false) 그대로 둔다.
                }
            }
        }

        // 마스크 적용(자기역원 — 두 번 적용하면 원복).
        function applyMask(mask) {
            for (var y = 0; y < size; y++) {
                for (var x = 0; x < size; x++) {
                    if (fun[y][x]) continue;
                    var invert;
                    switch (mask) {
                        case 0: invert = (x + y) % 2 === 0; break;
                        case 1: invert = y % 2 === 0; break;
                        case 2: invert = x % 3 === 0; break;
                        case 3: invert = (x + y) % 3 === 0; break;
                        case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
                        case 5: invert = ((x * y) % 2) + ((x * y) % 3) === 0; break;
                        case 6: invert = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break;
                        default: invert = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0; break;
                    }
                    if (invert) mat[y][x] = !mat[y][x];
                }
            }
        }

        var best = 0;
        var bestScore = Infinity;
        for (var m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            var score = penalty(mat);
            if (score < bestScore) {
                bestScore = score;
                best = m;
            }
            applyMask(m); // 원복.
        }
        applyMask(best);
        drawFormatBits(best);
        return mat;
    }

    // ---- 마스크 벌점(표준 규칙 N1~N4) ----

    function runPenalty(line) {
        var s = 0;
        var run = 1;
        for (var i = 1; i <= line.length; i++) {
            if (i < line.length && line[i] === line[i - 1]) {
                run++;
            } else {
                if (run >= 5) s += 3 + (run - 5);
                run = 1;
            }
        }
        return s;
    }

    var FINDER_PAT_A = [true, false, true, true, true, false, true, false, false, false, false];
    var FINDER_PAT_B = [false, false, false, false, true, false, true, true, true, false, true];

    function finderPenalty(line) {
        var s = 0;
        for (var i = 0; i + 11 <= line.length; i++) {
            var a = true;
            var b = true;
            for (var j = 0; j < 11; j++) {
                if (line[i + j] !== FINDER_PAT_A[j]) a = false;
                if (line[i + j] !== FINDER_PAT_B[j]) b = false;
            }
            if (a) s += 40;
            if (b) s += 40;
        }
        return s;
    }

    function penalty(mat) {
        var n = mat.length;
        var score = 0;
        var x, y, col;
        for (y = 0; y < n; y++) {
            score += runPenalty(mat[y]) + finderPenalty(mat[y]);
        }
        for (x = 0; x < n; x++) {
            col = [];
            for (y = 0; y < n; y++) col.push(mat[y][x]);
            score += runPenalty(col) + finderPenalty(col);
        }
        for (y = 0; y < n - 1; y++) {
            for (x = 0; x < n - 1; x++) {
                var v = mat[y][x];
                if (v === mat[y][x + 1] && v === mat[y + 1][x] && v === mat[y + 1][x + 1]) score += 3;
            }
        }
        var dark = 0;
        for (y = 0; y < n; y++) {
            for (x = 0; x < n; x++) if (mat[y][x]) dark++;
        }
        var percent = (dark * 100) / (n * n);
        score += Math.floor(Math.abs(percent - 50) / 5) * 10;
        return score;
    }

    // ---- 공개 API: canvas 렌더(조용 구역 4모듈 포함) ----

    window.drawQrCode = function (canvas, text) {
        var mat = encode(String(text));
        var size = mat.length;
        var quiet = 4;
        var scale = Math.max(2, Math.floor(240 / (size + quiet * 2)));
        var px = (size + quiet * 2) * scale;
        canvas.width = px;
        canvas.height = px;
        var ctx = canvas.getContext('2d');
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, px, px);
        ctx.fillStyle = '#000000';
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                if (mat[y][x]) ctx.fillRect((x + quiet) * scale, (y + quiet) * scale, scale, scale);
            }
        }
        return size;
    };
})();
