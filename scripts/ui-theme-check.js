// UI 新主题视觉验证：登录 → 大盘 → 断言主题 CSS 生效（navy 侧栏/白卡/无布局破裂）
(async () => {
  const { chromium } = require('D:/Develop_code/yjxxt/01_note/其他学习内容/AgentChat/node_modules/playwright-core');
  const b = await chromium.launch({ headless: true, executablePath: 'C:/Users/ASUS/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe' });
  const p = await b.newPage({ viewport: { width: 1600, height: 900 } });
  const errs = [];
  p.on('pageerror', e => errs.push(e.message.slice(0, 150)));
  await p.goto('http://127.0.0.1:5173/login', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await p.waitForSelector('#username');
  console.log('登录页字体:', await p.evaluate(() => getComputedStyle(document.body).fontFamily.includes('Fira') ? 'Fira OK' : 'fallback'));
  await p.fill('#username', 'admin');
  await p.fill('#password', 'admin123');
  await p.click('.login-btn');
  await p.waitForURL(/overview/, { timeout: 20000 });
  await p.waitForTimeout(2600);
  const info = await p.evaluate(() => {
    const sb = getComputedStyle(document.querySelector('.sidebar'));
    const card = getComputedStyle(document.querySelector('.metric-card'));
    const val = document.querySelector('.metric-card .value');
    return {
      sidebarBg: sb.backgroundColor,
      sidebarW: sb.width,
      cardBorder: card.borderColor,
      cardRadius: card.borderRadius,
      valueFont: val ? getComputedStyle(val).fontFamily.includes('Fira Code') : 'no-card',
      cards: document.querySelectorAll('.metric-card').length,
      navActive: document.querySelector('.nav-item.router-link-active')?.textContent.trim() || '?',
      bodyBg: getComputedStyle(document.body).backgroundColor,
      hScroll: document.body.scrollWidth > document.body.clientWidth
    };
  });
  console.log('侧栏背景:', info.sidebarBg, '| 宽度:', info.sidebarW);
  console.log('卡片边框/圆角:', info.cardBorder, '/', info.cardRadius, '| 指标卡数:', info.cards);
  console.log('指标数字字体 Fira Code:', info.valueFont, '| 激活导航:', info.navActive);
  console.log('页面背景:', info.bodyBg, '| 横向滚动:', info.hScroll);
  console.log('JS错误:', errs.length ? errs.join(';') : '无');
  await p.screenshot({ path: 'D:/Develop_code/GraduationProject/docs/thesis-materials/screenshots/web-01-overview-theme.png' });
  await b.close();
  const ok = info.sidebarBg === 'rgb(30, 64, 175)' && info.valueFont && !info.hScroll && errs.length === 0;
  console.log(ok ? 'THEME_OK' : 'THEME_CHECK');
})().catch(e => { console.error('ERR:', e.message.slice(0, 250)); process.exit(1); });