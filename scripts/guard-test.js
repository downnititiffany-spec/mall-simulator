// 登录守卫 headless 验证
(async () => {
  const { chromium } = require('D:/Develop_code/yjxxt/01_note/其他学习内容/AgentChat/node_modules/playwright-core');
  const exe = 'C:/Users/ASUS/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe';
  const b = await chromium.launch({ headless: true, executablePath: exe });
  const p = await b.newPage({ viewport: { width: 1600, height: 900 } });
  // 1) 无 token 访问 /overview → 应跳 /login
  await p.goto('http://127.0.0.1:5173/overview', { waitUntil: 'networkidle' }).catch(() => {});
  await p.waitForTimeout(1800);
  console.log('未登录访问 /overview → URL:', p.url());
  // 2) 登录
  await p.fill('input[type=text], input:not([type=password])', 'admin').catch(() => {});
  await p.fill('input[type=password]', 'admin123').catch(() => {});
  await p.click('button').catch(() => {});
  await p.waitForTimeout(2500);
  console.log('登录后 URL:', p.url().slice(0, 60));
  const cards = await p.evaluate(() => document.querySelectorAll('.metric-card').length);
  console.log('指标卡数:', cards);
  const nav = await p.evaluate(() =>
    [...document.querySelectorAll('nav a, .nav a, aside a, .side-nav a')].map(a => a.textContent.trim()).join(' / '));
  console.log('导航项(admin):', nav.slice(0, 120));
  const stored = await p.evaluate(() => localStorage.getItem('mall_token') ? '有token' : '无token');
  console.log('localStorage:', stored);
  await b.close();
  console.log('GUARD_OK');
})().catch(e => { console.error('ERR:', e.message); process.exit(1); });