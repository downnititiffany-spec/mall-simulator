// 截图采集：PPT 视觉验证 + 看板论文素材
// 用法: node shot.js     (输出到 screenshots/)
const path = require('path');
const { chromium } = require('D:/Develop_code/yjxxt/01_note/其他学习内容/AgentChat/node_modules/playwright-core');

const OUT = path.resolve(__dirname, '../docs/thesis-materials/screenshots');
const fs = require('fs');
fs.mkdirSync(OUT, { recursive: true });

const W = 1600, H = 900;

(async () => {
  const exe = 'C:/Users/ASUS/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe';
  const browser = await chromium.launch({ headless: true, executablePath: exe });
  const ctx = await browser.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });

  // ── 1. PPT 视觉验证（file:// 或本地服务器可见） ──
  const ppt = await ctx.newPage();
  await ppt.goto('file:///D:/Develop_code/GraduationProject/ppt/index.html', { waitUntil: 'load' });
  await ppt.waitForTimeout(2500);
  const shot = async (name) => {
    await ppt.waitForTimeout(1200);
    await ppt.screenshot({ path: path.join(OUT, name) });
    console.log('saved', name);
  };
  await shot('ppt-01-cover.png');
  for (let i = 1; i < 4; i++) { await ppt.keyboard.press('ArrowRight'); } await shot('ppt-05-pipeline.png');
  for (let i = 0; i < 5; i++) { await ppt.keyboard.press('ArrowRight'); } await shot('ppt-10-performance.png');
  for (let i = 0; i < 6; i++) { await ppt.keyboard.press('ArrowRight'); } await shot('ppt-16-closing.png');

  // ── 2. 看板截图（前端 dev :5173） ──
  const web = await ctx.newPage();
  const pages = [
    ['web-01-overview.png', 'http://127.0.0.1:5173/overview'],
    ['web-02-behavior.png', 'http://127.0.0.1:5173/behavior'],
    ['web-03-products.png', 'http://127.0.0.1:5173/products'],
    ['web-04-sales.png',    'http://127.0.0.1:5173/sales'],
    ['web-05-pipeline.png', 'http://127.0.0.1:5173/pipeline'],
    ['web-06-decisions.png','http://127.0.0.1:5173/decisions'],
    ['web-07-ai.png',       'http://127.0.0.1:5173/ai'],
  ];
  for (const [name, url] of pages) {
    await web.goto(url, { waitUntil: 'networkidle', timeout: 30000 }).catch(() => {});
    await web.waitForTimeout(2500);
    await web.screenshot({ path: path.join(OUT, name) });
    console.log('saved', name);
  }
  await browser.close();
  console.log('DONE ->', OUT);
})().catch(e => { console.error(e); process.exit(1); });