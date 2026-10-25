// 时间轴核心：所有动画都是 t 的纯函数，渲染器逐帧 seek。
const EASE = {
  linear: t => t,
  inQuad: t => t * t,
  outCubic: t => 1 - Math.pow(1 - t, 3),
  outQuint: t => 1 - Math.pow(1 - t, 5),
  inOutCubic: t => (t < .5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2),
  outBack: t => {
    const c1 = 1.70158, c3 = c1 + 1;
    return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
  },
};

const clamp01 = v => (v < 0 ? 0 : v > 1 ? 1 : v);

// t 落在 [a,b] 区间内的进度 0..1
const seg = (t, a, b) => clamp01((t - a) / (b - a));

const lerp = (a, b, p) => a + (b - a) * p;

const scenes = [];

function registerScene(scene) {
  scenes.push(scene);
}

const camera = document.getElementById('camera');

function setCamera(scale = 1, tx = 0, ty = 0) {
  camera.style.transform = `translate(${tx.toFixed(2)}px, ${ty.toFixed(2)}px) scale(${scale.toFixed(4)})`;
}

function fadeUp(el, p, distance = 26) {
  el.style.opacity = p.toFixed(3);
  el.style.transform = `translateY(${((1 - p) * distance).toFixed(2)}px)`;
}

window.__seek = function (t) {
  for (const s of scenes) {
    const active = t >= s.start && t < s.end;
    s.root.style.display = active ? 'block' : 'none';
    if (active) s.render(t - s.start, s.end - s.start);
  }
};

window.__duration = () => scenes.reduce((m, s) => Math.max(m, s.end), 0);
window.__scenes = () => scenes.map(s => ({ start: s.start, end: s.end }));
