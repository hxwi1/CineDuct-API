// 镜头：零拷贝（三拍连在一个镜头里，20.6 秒）
// 一拍 0–6400ms     解码就在显存，系统内存全程空着
// 二拍 6400–14000ms 跨进程只递句柄和锁；最后把老路剪断
// 三拍 14000–20600ms GPU 内转 BGRA8、槽位 33MB→8MB、屏幕亮起来
// 图标来自 Lucide（ISC），锁用它的 lock / lock-open 路径，内联 SVG。
(function () {
  const root = document.getElementById('scene-pipeline');

  root.innerHTML = `
    <div class="pipe-title" data-title>零拷贝路径 · <b>DXGI 共享纹理</b></div>

    <div class="node gpu" data-node="a" style="left:140px">
      <div class="tag">01 / 解码</div>
      <div class="name">FFmpeg 硬解</div>
      <div class="sub">D3D11VA · DXVA2</div>
    </div>

    <div class="node gpu" data-node="b" style="left:580px">
      <div class="tag">02 / 显存</div>
      <div class="name">D3D11 纹理</div>
      <div class="sub">BGRA8 · 4 槽轮转</div>
      <div class="vram-chip" data-chip></div>
    </div>

    <div class="node" data-node="c" style="left:1020px">
      <div class="tag">03 / 互操作</div>
      <div class="name">GL 直接采样</div>
      <div class="sub">WGL_NV_DX_interop2</div>
    </div>

    <div class="node" data-node="d" style="left:1460px">
      <div class="tag">04 / 输出</div>
      <div class="name">屏幕</div>
      <div class="sub">lock → 采样 → unlock</div>
      <div class="screen-mini" data-mini>
        <i></i><i></i><i></i><i></i><i></i><i></i>
        <i></i><i></i><i></i><i></i><i></i><i></i>
        <i></i><i></i><i></i><i></i><i></i><i></i>
      </div>
    </div>

    <div class="link" data-link="ab" style="left:440px;width:140px"></div>
    <div class="link" data-link="bc" style="left:880px;width:140px"></div>
    <div class="link" data-link="cd" style="left:1320px;width:140px"></div>

    <div class="flow" data-flow="0"></div>
    <div class="flow" data-flow="1"></div>
    <div class="flow" data-flow="2"></div>

    <div class="down-link" data-downlink></div>
    <div class="bounce-dot" data-bounce></div>

    <div class="mem-box" data-mem>
      <div class="label">系统内存</div>
      <div class="hint">全程空着</div>
      <div class="mem-cross" data-cross></div>
    </div>

    <div class="proc-divider" data-divider></div>
    <div class="proc-label" data-plabel="l" style="left:812px;width:130px;text-align:right">子进程</div>
    <div class="proc-label" data-plabel="r" style="left:966px;width:130px">游戏进程</div>

    <div class="slots" data-slots>
      <i class="slot" data-slot="0"></i>
      <i class="slot" data-slot="1"></i>
      <i class="slot" data-slot="2"></i>
      <i class="slot" data-slot="3"></i>
    </div>
    <div class="mini-tag" data-tag-a style="left:730px;top:428px">NT handle</div>

    <div class="lock" data-lock>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
        <rect x="3" y="11" width="18" height="11" rx="2.5"></rect>
        <path data-shackle d="M7 11V7a5 5 0 0 1 10 0v4"></path>
      </svg>
    </div>
    <div class="lock-word" data-lock-word>KeyedMutex</div>

    <div class="sample-dot" data-sample></div>

    <div class="scissor" data-scissor>\u2702</div>
    <div class="cut-spark" data-spark="0"></div>
    <div class="cut-spark" data-spark="1"></div>
    <div class="cut-spark" data-spark="2"></div>
    <div class="cut-spark" data-spark="3"></div>
    <div class="cut-spark" data-spark="4"></div>
    <div class="cut-spark" data-spark="5"></div>

    <div class="flash" data-flash></div>

    <div class="vp-panel" data-vp>
      <div class="vp-name">ID3D11VideoProcessor</div>
      <div class="vp-row">
        <span class="fmt" data-fmt-yuv>NV12</span>
        <span class="vp-arrow">&#8594;</span>
        <span class="fmt out" data-fmt-rgb>BGRA8</span>
      </div>
    </div>

    <div class="size-box" data-sizebox></div>
    <div class="size-label" data-label-a style="left:1300px;top:582px">3840 × 2160 · 33 MB</div>
    <div class="size-label" data-label-b style="left:1060px;top:762px">1920 × 1080 · 8 MB</div>

    <div class="subs">
      <div class="sub-line" data-s="0">解码这一步，帧就直接写进显存</div>
      <div class="sub-line" data-s="1">系统内存这条路，整个跳过</div>
      <div class="sub-line" data-s="2">省掉的，就是下来再上去那一趟</div>
      <div class="sub-line punch" data-s="3">帧没进过系统内存</div>

      <div class="sub-line" data-s="4">跨进程，靠的是一张共享纹理</div>
      <div class="sub-line" data-s="5">递过去的是句柄和锁，不是像素</div>
      <div class="sub-line" data-s="6">interop 把它注册成一张 GL 纹理</div>
      <div class="sub-line punch" data-s="7">那条老路，从这就不走了</div>

      <div class="sub-line" data-s="8">解码出来是 NV12</div>
      <div class="sub-line" data-s="9">转格式、缩放，都在 GPU 上做</div>
      <div class="sub-line punch" data-s="10">4K 的槽位，从 33MB 压到 8MB</div>
      <div class="sub-line" data-s="11">屏幕这边，拿到就能画</div>
    </div>`;

  const q = sel => root.querySelector(sel);
  const node = k => q(`[data-node="${k}"]`);
  const nodes = { a: node('a'), b: node('b'), c: node('c'), d: node('d') };
  const title = q('[data-title]');
  const links = ['ab', 'bc', 'cd'].map(k => q(`[data-link="${k}"]`));
  const flows = [0, 1, 2].map(i => q(`[data-flow="${i}"]`));
  const chip = q('[data-chip]');
  const downlink = q('[data-downlink]');
  const bounce = q('[data-bounce]');
  const mem = q('[data-mem]');
  const cross = q('[data-cross]');
  const divider = q('[data-divider]');
  const plabelL = q('[data-plabel="l"]');
  const plabelR = q('[data-plabel="r"]');
  const slotsWrap = q('[data-slots]');
  const slots = [0, 1, 2, 3].map(i => q(`[data-slot="${i}"]`));
  const tagA = q('[data-tag-a]');
  const lock = q('[data-lock]');
  const shackle = q('[data-shackle]');
  const lockWord = q('[data-lock-word]');
  const sample = q('[data-sample]');
  const scissor = q('[data-scissor]');
  const sparks = [0, 1, 2, 3, 4, 5].map(i => q(`[data-spark="${i}"]`));
  const flash = q('[data-flash]');
  const vp = q('[data-vp]');
  const fmtYuv = q('[data-fmt-yuv]');
  const fmtRgb = q('[data-fmt-rgb]');
  const sizeBox = q('[data-sizebox]');
  const labelA = q('[data-label-a]');
  const labelB = q('[data-label-b]');
  const mini = q('[data-mini]');
  const miniCells = Array.from(mini.querySelectorAll('i'));
  const lines = Array.from({ length: 12 }, (_, i) => q(`[data-s="${i}"]`));

  const BEAT1 = 6400;
  const BEAT2 = 7600;
  const BEAT3 = 6600;
  const DUR = BEAT1 + BEAT2 + BEAT3;
  const CUT = 5200;
  const FREEZE = 320;

  const enterAt = { a: 0, b: 140, c: 280, d: 420 };

  const subs1 = [
    { el: lines[0], in: 420, out: 1500 },
    { el: lines[1], in: 1620, out: 2860 },
    { el: lines[2], in: 2960, out: 3980 },
    { el: lines[3], in: 4120, out: BEAT1 + 60 },
  ];

  const subs2 = [
    { el: lines[4], in: 300, out: 1950 },
    { el: lines[5], in: 2080, out: 3900 },
    { el: lines[6], in: 4020, out: 5100 },
    { el: lines[7], in: CUT + 420, out: BEAT2 + 200 },
  ];

  const subs3 = [
    { el: lines[8], in: 260, out: 1700 },
    { el: lines[9], in: 1850, out: 3250 },
    { el: lines[10], in: 3400, out: 5050 },
    { el: lines[11], in: 5200, out: BEAT3 + 200 },
  ];

  function showLine(s, t) {
    const pi = seg(t, s.in, s.in + 240);
    const po = seg(t, s.out - 220, s.out);
    const o = pi * (1 - po);
    s.el.style.opacity = o.toFixed(3);
    s.el.style.transform = `translateY(${((1 - pi) * 14 - po * 8).toFixed(2)}px)`;
  }

  registerScene({
    start: 17200,
    end: 17200 + DUR,
    root,
    render(t) {
      const inBeat2 = t >= BEAT1;
      const inBeat3 = t >= BEAT1 + BEAT2;
      const b = t - BEAT1;
      const c3 = t - BEAT1 - BEAT2;

      // 剪断之后冻结 320ms，视觉上的静音
      const ft = inBeat2 ? (b < CUT ? b : b < CUT + FREEZE ? CUT : b - FREEZE) : t;
      const cut = inBeat2 && b >= CUT;

      // ---------- 共用：卡片与链路 ----------
      for (const k of ['a', 'b', 'c', 'd']) {
        const p = EASE.outCubic(seg(t, enterAt[k], enterAt[k] + 420));
        const el = nodes[k];
        el.style.opacity = p.toFixed(3);
        el.style.transform = `translateY(${((1 - p) * 26).toFixed(2)}px)`;
      }

      chip.style.opacity = inBeat2
        ? '0'
        : (0.35 + 0.5 * (0.55 + 0.45 * Math.sin((t / 640) * Math.PI))).toFixed(3);

      links.forEach((el, i) => {
        const p = seg(t, 380 + i * 90, 780 + i * 90);
        el.style.opacity = (p * 0.9).toFixed(3);
        el.style.backgroundPosition = `${((ft * 0.06) % 14).toFixed(1)}px 0`;
      });

      flows.forEach((el, i) => {
        const p = ((ft / 1600) + i / 3) % 1;
        const x = 440 + p * 1020;
        const fade = Math.min(1, Math.min(p, 1 - p) * 6);
        el.style.left = `${x.toFixed(1)}px`;
        el.style.opacity = (t > 700 ? fade : 0).toFixed(3);
      });

      // ---------- 第一拍 ----------
      downlink.style.opacity = (seg(t, 760, 1000) * (cut ? 0.5 : 0.8)).toFixed(3);
      downlink.style.background = cut
        ? 'linear-gradient(180deg, rgba(255,90,78,.7) 50%, transparent 50%)'
        : 'linear-gradient(180deg, rgba(140,151,168,.5) 50%, transparent 50%)';
      downlink.style.backgroundSize = '2px 12px';

      if (cut) {
        const mask = 'linear-gradient(180deg, #000 0 42%, transparent 42% 58%, #000 58%)';
        downlink.style.webkitMaskImage = mask;
        downlink.style.maskImage = mask;
      } else {
        downlink.style.webkitMaskImage = 'none';
        downlink.style.maskImage = 'none';
      }

      const down = EASE.inQuad(seg(t, 900, 1420));
      const up = EASE.outCubic(seg(t, 1560, 2040));
      const travel = down * 118 - up * 118;
      bounce.style.opacity = t >= 900 && !inBeat2 ? (1 - seg(t, 2040, 2140)).toFixed(3) : '0';
      bounce.style.transform = `translateY(${travel.toFixed(1)}px)`;

      mem.style.opacity = (seg(t, 700, 1000) * (cut ? 0.35 : 0.95)).toFixed(3);
      const cp = seg(t, 1440, 1760);
      cross.style.opacity = (cp * (cut ? 0.4 : 0.95)).toFixed(3);
      cross.style.transform = `scale(${lerp(1.5, 1, EASE.outCubic(cp)).toFixed(3)})`;

      // ---------- 第二拍 ----------
      divider.style.opacity = inBeat2 ? (seg(b, 120, 620) * 0.8).toFixed(3) : '0';
      plabelL.style.opacity = inBeat2 ? (seg(b, 340, 700) * 0.9).toFixed(3) : '0';
      plabelR.style.opacity = inBeat2 ? (seg(b, 440, 800) * 0.9).toFixed(3) : '0';

      slotsWrap.style.opacity = inBeat2 ? seg(b, 260, 700).toFixed(3) : '0';
      tagA.style.opacity = inBeat2 ? (seg(b, 360, 780) * 0.95).toFixed(3) : '0';

      const cur = Math.floor(ft / 460) % 4;
      slots.forEach((el, i) => {
        const on = i === cur;
        el.style.borderColor = on ? 'rgba(74,237,217,.95)' : 'rgba(74,237,217,.35)';
        el.style.background = on ? 'rgba(74,237,217,.55)' : 'rgba(74,237,217,.1)';
        el.style.boxShadow = on ? '0 0 18px rgba(74,237,217,.75)' : 'none';
        el.style.transform = on ? 'scale(1.06)' : 'scale(1)';
      });

      // 锁 → 采样 → 解，1100ms 一轮
      const CY = 1100;
      const phase = inBeat2 ? ((((ft - 700) % CY) + CY) % CY) : 0;
      const lockOn = inBeat2 && b >= 700 && !cut;
      lock.style.opacity = lockOn ? '1' : '0';
      lockWord.style.opacity = lockOn
        ? (0.45 + 0.35 * (phase > 380 && phase < 900 ? 1 : 0)).toFixed(3)
        : '0';

      const locked = phase < 900;
      shackle.setAttribute('transform', locked ? '' : 'rotate(-30 7 11)');
      lock.style.color = locked ? 'var(--gold)' : 'rgba(242,193,78,.55)';

      const sp = (phase - 380) / 520;
      if (lockOn && sp >= 0 && sp <= 1) {
        sample.style.opacity = '1';
        sample.style.left = `${lerp(742, 1600, EASE.inOutCubic(sp)).toFixed(1)}px`;
      } else {
        sample.style.opacity = '0';
      }

      // 剪断
      const scIn = seg(b, CUT - 360, CUT - 60);
      scissor.style.opacity = cut ? '0' : (scIn * 0.95).toFixed(3);
      scissor.style.transform = `translate(${lerp(60, 0, EASE.outCubic(scIn)).toFixed(1)}px, ${lerp(-30, 0, EASE.outCubic(scIn)).toFixed(1)}px) rotate(${lerp(-8, 0, scIn).toFixed(1)}deg)`;

      sparks.forEach((el, i) => {
        if (!cut) { el.style.opacity = '0'; return; }
        const life = seg(b, CUT, CUT + 520);
        const ang = (-Math.PI / 2) + (i - 2.5) * 0.42;
        const dist = 8 + 54 * EASE.outCubic(life);
        el.style.opacity = (0.95 * (1 - life)).toFixed(3);
        el.style.left = `${(735 + Math.cos(ang) * dist).toFixed(1)}px`;
        el.style.top = `${(622 + Math.sin(ang) * dist * 0.7).toFixed(1)}px`;
      });

      flash.style.opacity = cut ? (0.9 * (1 - seg(b, CUT, CUT + 300))).toFixed(3) : '0';

      // ---------- 第三拍 ----------
      vp.style.opacity = inBeat3 ? seg(c3, 120, 520).toFixed(3) : '0';
      fmtYuv.style.opacity = inBeat3 ? (1 - 0.7 * seg(c3, 900, 1250)).toFixed(3) : '0';
      fmtRgb.style.opacity = inBeat3 ? seg(c3, 1050, 1400).toFixed(3) : '0';

      const shr = EASE.inOutCubic(seg(c3, 1700, 2900));
      sizeBox.style.opacity = inBeat3 ? seg(c3, 1450, 1850).toFixed(3) : '0';
      sizeBox.style.transform = `translate(${(-240 * shr).toFixed(1)}px, ${(74 * shr).toFixed(1)}px) scale(${lerp(1, 0.5, shr).toFixed(3)})`;
      labelA.style.opacity = inBeat3
        ? (seg(c3, 1450, 1800) * (1 - seg(c3, 2450, 2750))).toFixed(3)
        : '0';
      labelB.style.opacity = inBeat3 ? seg(c3, 2850, 3150).toFixed(3) : '0';

      const lit = seg(c3, 3400, 4300);
      mini.style.opacity = inBeat3 ? seg(c3, 3250, 3600).toFixed(3) : '0';
      miniCells.forEach((el, i) => {
        const p = seg(c3, 3300 + i * 26, 3300 + i * 26 + 220);
        el.style.opacity = p.toFixed(3);
      });
      nodes.d.style.borderColor = inBeat3 && lit > 0
        ? `rgba(74,237,217,${(0.12 + 0.55 * lit).toFixed(3)})`
        : '';

      title.innerHTML = inBeat3
        ? 'GPU 内完成 · <b>不落内存</b>'
        : inBeat2
          ? '跨进程 · <b>只递句柄</b>'
          : '零拷贝路径 · <b>DXGI 共享纹理</b>';

      // ---------- 字幕 ----------
      lines.forEach(el => { el.style.opacity = '0'; });
      if (t < BEAT1) subs1.forEach(s => showLine(s, t));
      else if (t < BEAT1 + BEAT2) subs2.forEach(s => showLine(s, b));
      else subs3.forEach(s => showLine(s, c3));

      // ---------- 镜头 ----------
      const shakeAmp = cut ? 7 * (1 - seg(b, CUT, CUT + 300)) : 0;
      const tx = cut ? Math.sin((b - CUT) * 0.06) * shakeAmp : 0;
      const ty = cut ? Math.cos((b - CUT) * 0.05) * shakeAmp * 0.6 : 0;
      setCamera(lerp(1, 1.03, EASE.outCubic(seg(t, 0, DUR))), tx, ty);
    },
  });
})();
