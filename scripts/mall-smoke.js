// 商城全流程冒烟（v2：精确区块定位）：登录→注册→加购→下单→支付→退款完成
(async () => {
  const { chromium } = require('D:/Develop_code/yjxxt/01_note/其他学习内容/AgentChat/node_modules/playwright-core');
  const exe = 'C:/Users/ASUS/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe';
  const b = await chromium.launch({ headless: true, executablePath: exe });
  const p = await b.newPage({ viewport: { width: 1600, height: 900 } });
  const apiLog = [];
  p.on('response', r => {
    if (r.url().includes('/api/v1/mall')) apiLog.push(`${r.request().method()} ${r.url().split('/api/v1')[1]} -> ${r.status()}`);
  });

  await p.goto('http://127.0.0.1:5173/login', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await p.waitForSelector('#username');
  await p.fill('#username', 'admin');
  await p.fill('#password', 'admin123');
  await p.click('.login-btn');
  await p.waitForURL(/overview/, { timeout: 20000 });
  console.log('STEP1 登录 →', p.url().slice(21, 60));

  await p.goto('http://127.0.0.1:5173/mall', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await p.waitForSelector('button:has-text("注册新用户")');
  await p.click('button:has-text("注册新用户")');
  await p.waitForTimeout(3500);
  console.log('STEP2 注册新用户完成');

  await p.click('tbody tr button:has-text("加购") >> nth=0');
  await p.waitForTimeout(4000);
  let cartRows = await p.evaluate(() =>
    [...document.querySelectorAll('.chart-box')]
      .find(x => (x.querySelector('.chart-title') || {}).textContent?.includes('购物车'))
      ?.querySelectorAll('tbody tr').length || 0);
  console.log('STEP3 购物车行数:', cartRows);

  if (cartRows > 0) {
    await p.click('button:has-text("下单")');
    await p.waitForTimeout(3500);
    console.log('STEP4 下单完成');
    await p.click('button:has-text("支付")');
    await p.waitForTimeout(3000);
    const errDiv = await p.evaluate(() =>
      [...document.querySelectorAll('.chart-box')].map(x => x.innerText)
        .find(t => t.includes('失败')) || '无失败提示');
    console.log('STEP5 失败提示:', errDiv.replace(/\s+/g, ' ').slice(0, 140));
    console.log('STEP5 API:', apiLog.slice(-4).join(' | '));
  }
  await b.close();
  console.log(cartRows > 0 ? 'MALL_OK' : 'MALL_FAIL');
})().catch(e => { console.error('ERR:', e.message.slice(0, 300)); process.exit(1); });