// 镜头：老做法（9 秒）
// 进程内引擎也是硬解，但帧要下到系统内存，再传回显存——来回两趟。
(function () {
  const root = document.getElementById('scene-legacy');

  root.innerHTML = `
    <div class="pipe-title">进程内引擎 · <b style="color:#ff8a80">帧要走两趟</b></div>

    <div class="node" data-node="0" style="left:60px;width:280px">
      <div class="tag">01 / 来源</div>
      <div class="name">网络流</div>
      <div class="sub">DASH 视频轨</div>
    </div>

    <div class="node" data-node="1" style="left:400px;width:280px">
      <div class="tag">02 / 解码</div>
      <div class="name">FFmpeg 硬解</div>
      <div class="sub">帧在显存里</div>
    </div>

    <div class="node red" data-node="2" style="left:740px;width:280px">
      <div class="tag">03 / 下来</div>
      <div class="name">下载到内存</div>
      <div class="sub">av_hwframe_transfer</div>
    </div>

    <div class="node red" data-node="3" style="left:1080px;width:280px">
      <div class="tag">04 / 上去</div>
      <div class="name">上传纹理</div>
      <div class="sub">glTexSubImage2D</div>
    </div>

    <div class="node" data-node="4" style="left:1420px;width:280px">
      <div class="tag">05 / 输出</div>
      <div class="name">屏幕</div>
      <div class="sub">GPU 再采样</div>
    </div>

    <div class="link" data-link="0" style="left:340px;width:60px"></div>
    <div class="link" data-link="1" style="left:680px;width:60px"></div>
    <div class="link red" data-link="2" style="left:1020px;width:60px"></div>
    <div class="link" data-link="3" style="left:1360px;width:60px"></div>

    <div class="flow" data-flow="0"></div>
    <div class="flow" data-flow="1"></div>
    <div class="flow" data-flow="2"></div>
    <div class="flow" data-flow="3"></div>

    <div class="stat-panel" data-stats>
      <div class="stat-row"><span>每帧搬运</span><b>2 趟</b></div>
      <div class="stat-row"><span>1080p60 带宽</span><b data-bw>0.0 GB/s</b></div>
      <div class="stat-row"><span>CPU 占用</span><b data-cpu>0%</b></div>
      <div class="stat-bar"><i data-bar></i></div>
    </div>

    <div class="subs">
      <div class="sub-line" data-s="0">进程内引擎，一样是硬解</div>
      <div class="sub-line" data-s="1">但帧得先下到系统内存</div>
      <div class="sub-line" data-s="2">传回显存，才能画到屏幕上</div>
      <div class="sub-line punch" data-s="3">一来一回，每秒 1GB</div>
    </div>`;

  const nodes = [0, 1, 2, 3, 4].map(i => root.querySelector(`[data-node="${i}"]`));
  const links = [0, 1, 2, 3].map(i => root.querySelector(`[data-link="${i}"]`));
  const flows = [0, 1, 2, 3].map(i => root.querySelector(`[data-flow="${i}"]`));
  const stats = root.querySelector('[data-stats]');
  const bw = root.querySelector('[data-bw]');
  const cpu = root.querySelector('[data-cpu]');
  const bar = root.querySelector('[data-bar]');
  const lines = [0, 1, 2, 3].map(i => root.querySelector(`[data-s="${i}"]`));

  const DUR = 9000;

  const subs = [
    { el: lines[0], in: 200, out: 2200 },
    { el: lines[1], in: 2380, out: 4300 },
    { el: lines[2], in: 4460, out: 6100 },
    { el: lines[3], in: 6260, out: DUR + 200 },
  ];

  registerScene({
    start: 9200,
    end: 9200 + DUR,
    root,
    render(t) {
      nodes.forEach((el, i) => {
        const p = EASE.outCubic(seg(t, i * 130, i * 130 + 420));
        el.style.opacity = p.toFixed(3);
        el.style.transform = `translateY(${((1 - p) * 24).toFixed(2)}px)`;
      });

      links.forEach((el, i) => {
        const p = seg(t, 420 + i * 90, 800 + i * 90);
        el.style.opacity = (p * 0.9).toFixed(3);
        el.style.backgroundPosition = `${((t * 0.16) % 14).toFixed(1)}px 0`;
      });

      // 中间两格的红光呼吸
      const pulse = 0.6 + 0.4 * Math.sin((t / 420) * Math.PI);
      nodes[2].style.boxShadow = `inset 0 0 ${(28 + 20 * pulse).toFixed(0)}px rgba(255,90,78,${(0.06 + 0.06 * pulse).toFixed(3)})`;
      nodes[3].style.boxShadow = `inset 0 0 ${(28 + 20 * pulse).toFixed(0)}px rgba(255,90,78,${(0.06 + 0.06 * pulse).toFixed(3)})`;

      // 光点：走得比零拷贝那边快，显得忙
      flows.forEach((el, i) => {
        const p = ((t / 900) + i / 4) % 1;
        const x = 340 + p * 1080;
        const fade = Math.min(1, Math.min(p, 1 - p) * 7);
        el.style.left = `${x.toFixed(1)}px`;
        el.style.background = '#ff8a80';
        el.style.boxShadow = '0 0 18px rgba(255, 90, 78, .85)';
        el.style.opacity = (t > 700 ? fade * 0.9 : 0).toFixed(3);
      });

      // 统计面板
      stats.style.opacity = (seg(t, 900, 1300) * 0.97).toFixed(3);
      const load = EASE.outCubic(seg(t, 1200, 4200));
      bw.textContent = `${(1.0 * load).toFixed(1)} GB/s`;
      cpu.textContent = `${Math.round(lerp(12, 78, load))}%`;
      bar.style.width = `${lerp(12, 78, load).toFixed(1)}%`;

      for (const s of subs) {
        const pi = seg(t, s.in, s.in + 240);
        const po = seg(t, s.out - 220, s.out);
        const o = pi * (1 - po);
        s.el.style.opacity = o.toFixed(3);
        s.el.style.transform = `translateY(${((1 - pi) * 14 - po * 8).toFixed(2)}px)`;
      }

      setCamera(lerp(1, 1.026, EASE.outCubic(seg(t, 0, DUR))), 0, 0);
    },
  });
})();
