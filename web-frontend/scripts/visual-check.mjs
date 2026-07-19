import { mkdir } from "node:fs/promises";
import { chromium } from "playwright-core";

const output = new URL("../.visual/", import.meta.url);
await mkdir(output, { recursive: true });

const browser = await chromium.launch({
  executablePath: "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  headless: true,
});

const consoleErrors = [];
for (const viewport of [
  { name: "desktop", width: 1536, height: 960 },
  { name: "mobile", width: 390, height: 844 },
]) {
  const page = await browser.newPage({ viewport });
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(`${viewport.name}: ${message.text()}`);
  });
  await page.goto("http://127.0.0.1:3000", { waitUntil: "networkidle" });
  await page.screenshot({
    path: new URL(`dashboard-${viewport.name}.png`, output).pathname.slice(1),
    fullPage: true,
  });
  await page.close();
}

await browser.close();
if (consoleErrors.length) {
  console.error(consoleErrors.join("\n"));
  process.exitCode = 1;
}
