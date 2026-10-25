// 镜头：开场（4 秒）
// decoded frame → ??? → screen，方块砸下来把 ??? 盖成 VRAM，屏幕点亮。
(function () {
  const root = document.getElementById('scene-open');
  const BLOCKS = 60;

  root.innerHTML = `
    <div class="open-typed"><span data-pre></span><span class="tok-wrap"><span class="tok" data-tok></span><i class="slam" data-slam></i></span><span data-post></span></div>
    <div class="open-screen" data-screen><div class="scan" data-scan></div></div>
    <div class="subs">
      <div class="sub-line" data-sub>Minecraft 里播视频，一帧都不用回系统内存</div>
    </div>`;

  const pre = root.querySelector('[data-pre]');
  const tok = root.querySelector('[data-tok]');
  const post = root.querySelector('[data-post]');
  const slam = root.querySelector('[data-slam]');
  const screen = root.querySelector('[data-screen]');
  const scan = root.querySelector('[data-scan]');
  const sub = root.querySelector('[data-sub]');

  const cells = [];
  for (let i = 0; i < BLOCKS; i++) {
    const el = document.createElement('i');
    screen.appendChild(el);
    cells.push(el);
  }

  const A = 'decoded frame';
  const B = ' \u2192 ';
  const C = ' \u2192 screen';
  const TOKEN = '???';
  const PRE = A + B;
  const FULL = PRE + TOKEN + C;
  const TOK_START = PRE.length;
  const TOK_END = TOK_START + TOKEN.length;

  const DUR = 4000;
  const TYPE_END = 1000;
  const SLAM_AT = 1560;
  const LIGHT_AT = 1820;

  const glitchWindows = [[1040, 1110], [1210, 1270], [1380, 1450]];

  registerScene({
    start: 0,
    end: DUR,
    root,
    render(t) {
      const n = Math.floor(seg(t, 120, TYPE_END) * FULL.length);
      const slammed = t >= SLAM_AT;

      pre.textContent = FULL.slice(0, Math.min(n, TOK_START));
      post.textContent = n >= TOK_END ? FULL.slice(TOK_END) : '';

      if (slammed) {
        tok.textContent = 'VRAM';
        tok.classList.add('done');
      } else if (n > TOK_START) {
        tok.textContent = FULL.slice(TOK_START, Math.min(n, TOK_END));
        tok.classList.remove('done');
      } else {
        tok.textContent = '';
        tok.classList.remove('done');
      }

      const glitching = !slammed && glitchWindows.some(([a, b]) => t >= a && t <= b);
      tok.style.opacity = glitching ? '0.25' : '1';

      // 方块砸落
      if (t >= SLAM_AT - 40) {
        const y = lerp(-360, 0, EASE.inQuad(seg(t, SLAM_AT - 40, SLAM_AT + 150)));
        const s = lerp(1.25, 1, EASE.outCubic(seg(t, SLAM_AT - 40, SLAM_AT + 170)));
        slam.style.opacity = (0.85 * (1 - seg(t, SLAM_AT + 160, SLAM_AT + 560))).toFixed(3);
        slam.style.transform = `translateY(${y.toFixed(1)}px) scale(${s.toFixed(3)})`;
      } else {
        slam.style.opacity = '0';
      }

      // 屏幕逐格点亮，亮完回落到面板；格子里带一点游走的亮度，看起来像有画面
      const settle = seg(t, LIGHT_AT + 620, LIGHT_AT + 1200);
      screen.style.opacity = seg(t, LIGHT_AT - 140, LIGHT_AT + 60).toFixed(3);
      cells.forEach((el, i) => {
        const col = i % 10;
        const row = Math.floor(i / 10);
        const delay = (col + row * 1.5) * 15;
        const p = seg(t, LIGHT_AT + delay, LIGHT_AT + delay + 190);
        const wave = 0.62 + 0.38 * Math.sin(i * 0.62 + t / 820);
        const brightness = (1 - 0.45 * settle) * (0.55 + 0.45 * wave);
        el.style.opacity = (p * brightness).toFixed(3);
        el.style.transform = `scale(${lerp(0.6, 1, EASE.outCubic(p)).toFixed(3)})`;
      });

      // 扫描光带
      const y = lerp(-140, 460, (t / 2600) % 1);
      scan.style.transform = `translateY(${y.toFixed(1)}px)`;

      // 字幕
      const sp = seg(t, 2400, 2740);
      sub.style.opacity = sp.toFixed(3);
      sub.style.transform = `translateY(${((1 - sp) * 16).toFixed(1)}px)`;

      setCamera(lerp(1, 1.02, EASE.outCubic(seg(t, 0, DUR))), 0, 0);
    },
  });
})();
