// CSV 导出验证：销售页点击导出 → 下载文件内容断言
(async () => {
  const { chromium } = require('D:/Develop_code/yjxxt/01_note/其他学习内容/AgentChat/node_modules/playwright-core');
  const b = await chromium.launch({ headless: true, executablePath: 'C:/Users/ASUS/AppData/Local/ms-playwright/chromium-1223/chrome-win64/chrome.exe' });
  const p = await b.newPage({ viewport: { width: 1600, height: 900 }, acceptDownloads: true });
  await p.goto('http://127.0.0.1:5173/login', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await p.waitForSelector('#username');
  await p.fill('#username', 'admin');
  await p.fill('#password', 'admin123');
  await p.click('.login-btn');
  await p.waitForURL(/overview/, { timeout: 20000 });
  await p.goto('http://127.0.0.1:5173/sales', { waitUntil: 'domcontentloaded', timeout: 20000 });
  await p.waitForTimeout(2500);
  const [download] = await Promise.all([
    p.waitForEvent('download', { timeout: 10000 }),
    p.click('button:has-text("导出 CSV")')
  ]);
  const path = await download.path();
  console.log('下载名:', download.suggestedFilename());
  const fs = require('fs');
  const content = fs.readFileSync(path, 'utf8');
  console.log('CSV 首行:', content.split('\n')[0]);
  console.log('CSV 行数:', content.trim().split('\n').length, '(表头+数据)');
  console.log('含中文BOM:', content.charCodeAt(0) === 0xFEFF);
  const hasData = content.split('\n').length > 1;
  await b.close();
  console.log(hasData ? 'EXPORT_OK' : 'EXPORT_FAIL');
})().catch(e => { console.error('ERR:', e.message.slice(0, 250)); process.exit(1); });