// AI 助手历史回填验证
(async () => {
  const { chromium } = require('D:/Develop_code/yjxxt/01_note/其他学习内容/AgentChat/node_modules/playwright-core');
  const b = await chromium.launch({ headless: true, executablePath: 'C:/Users/ASUS/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe' });
  const p = await b.newPage({ viewport: { width: 1600, height: 900 } });
  await p.goto('http://127.0.0.1:5173/login', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await p.waitForSelector('#username');
  await p.fill('#username', 'operator');
  await p.fill('#password', 'operator123');
  await p.click('.login-btn');
  await p.waitForURL(/overview/, { timeout: 20000 });
  await p.goto('http://127.0.0.1:5173/ai', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await p.waitForTimeout(1500);
  // 问一个问题（规则降级模式应可执行）
  await p.fill('.chart-box input', '最新一期转化漏斗各阶段人数？');
  await p.click('button:has-text("发送")');
  await p.waitForTimeout(6000);
  const body = await p.evaluate(() => document.body.innerText.replace(/\s+/g, ' '));
  console.log('问答状态:', body.includes('EXECUTED') || body.includes('REPAIRED') ? '有执行结果' : '无（' + body.match(/管道状态[^）]*/)?.[0] || '无状态' + ')');
  console.log('历史出现:', body.includes('我的最近问答'));
  console.log('历史含本次问题:', body.includes('最新一期转化漏斗各阶段人数'));
  const hist = await p.evaluate(() => {
    const boxes = [...document.querySelectorAll('.chart-box')];
    const hb = boxes.find(x => (x.querySelector('.chart-title') || {}).textContent?.includes('最近问答'));
    return hb ? hb.innerText.replace(/\s+/g, ' ').slice(0, 120) : '未找到';
  });
  console.log('历史区块:', hist);
  await b.close();
  console.log('AI_HISTORY_OK');
})().catch(e => { console.error('ERR:', e.message.slice(0, 250)); process.exit(1); });