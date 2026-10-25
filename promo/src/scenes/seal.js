// 镜头：全网首发印章
(function () {
  const root = document.getElementById('scene-seal');

  root.innerHTML = `
    <div class="seal-hero">
      <div class="ring" data-ring></div>
      <div class="seal-drop" data-drop>
        <div class="seal" data-seal>
          <span>全</span><span>网</span><span>首</span><span>发</span>
        </div>
      </div>
      <div class="seal-lines">
        <p class="sline strong" data-l1>MC 模组生态内</p>
        <p class="sline dim" data-l2>D3D11 解码帧经 DXGI 共享纹理直通 OpenGL</p>
        <p class="sline version" data-l3>CineDuct API v3.3.0-alpha-1017</p>
      </div>
      <div class="seal-flash" data-flash></div>
    </div>`;

  const drop = root.querySelector('[data-drop]');
  const seal = root.querySelector('[data-seal]');
  const ring = root.querySelector('[data-ring]');
  const flash = root.querySelector('[data-flash]');
  const l1 = root.querySelector('[data-l1]');
  const l2 = root.querySelector('[data-l2]');
  const l3 = root.querySelector('[data-l3]');

  flash.style.cssText += 'position:absolute;inset:0;background:radial-gradient(700px 520px at 50% 42%,rgba(255,90,78,.38),transparent 70%);opacity:0;pointer-events:none;';

  const DUR = 5200;
  const IMPACT = 620;

  registerScene({
    start: 4000,
    end: 4000 + DUR,
    root,
    render(t) {
      // 下坠
      const p = EASE.inQuad(seg(t, 0, IMPACT));
      const y = lerp(-660, 0, p);
      const rot = lerp(-24, -8, p);
      drop.style.transform = `translate(-50%, -50%) translateY(${y.toFixed(2)}px) rotate(${rot.toFixed(2)}deg)`;

      // 落点挤压
      const squash = seg(t, IMPACT, IMPACT + 200);
      const sy = 1 - 0.075 * Math.sin(squash * Math.PI);
      const sx = 1 + 0.05 * Math.sin(squash * Math.PI);
      seal.style.transform = `scale(${sx.toFixed(4)}, ${sy.toFixed(4)})`;

      // 冲击波（跟着印章的倾角，才像从印章里打出来的）
      const rp = seg(t, IMPACT, IMPACT + 340);
      const rs = lerp(0.94, 1.52, EASE.outCubic(rp));
      ring.style.transform = `rotate(-8deg) scale(${rs.toFixed(3)})`;
      ring.style.opacity = (0.5 * (1 - rp)).toFixed(3);

      // 落点红光
      flash.style.opacity = (0.85 * (1 - seg(t, IMPACT, IMPACT + 320))).toFixed(3);

      // 震屏
      const sp = seg(t, IMPACT, IMPACT + 300);
      const amp = 9 * (1 - sp);
      const tx = Math.sin(sp * Math.PI * 5) * amp;
      const ty = Math.cos(sp * Math.PI * 4) * amp * 0.6;

      // 镜头缓推
      const push = lerp(1, 1.035, EASE.outCubic(seg(t, 0, DUR)));
      setCamera(push, tx, ty);

      // 界定小字
      fadeUp(l1, EASE.outCubic(seg(t, 760, 1400)));
      fadeUp(l2, EASE.outCubic(seg(t, 1120, 1760)), 18);
      fadeUp(l3, EASE.outCubic(seg(t, 1460, 2060)), 12);
    },
  });
})();
