#!/usr/bin/env node
// 一键发版：改版本号 → 更新 version.json → 编译 → 提交打 tag → 发 Release
//
// 用法：
//   node tools/release.mjs 0.3.0 "这一版改了什么"
// 需要：JAVA_HOME、gradle（默认用本机 Gradle 缓存里的 8.11.1）、gh 已登录
import { execSync } from "node:child_process";
import { readFileSync, writeFileSync, copyFileSync, mkdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const version = process.argv[2];
const notes = process.argv[3] || `Release ${version}`;
if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
  console.error("用法: node tools/release.mjs 0.3.0 \"更新说明\"");
  process.exit(1);
}

const repo = "rr5201314/lite-schedule";
const gradleBat =
  process.env.GRADLE_BAT ||
  "C:/Users/16015/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsomdt32c/gradle-8.11.1/bin/gradle.bat";

const sh = (cmd, cwd = root) => {
  execSync(cmd, { cwd, stdio: "inherit", shell: "cmd.exe" });
};

// 1) 版本号
const gradlePath = join(root, "android/app/build.gradle.kts");
let gradle = readFileSync(gradlePath, "utf8");
const codeMatch = gradle.match(/versionCode = (\d+)/);
const nextCode = codeMatch ? Number(codeMatch[1]) + 1 : 1;
gradle = gradle
  .replace(/versionCode = \d+/, `versionCode = ${nextCode}`)
  .replace(/versionName = "[^"]*"/, `versionName = "${version}"`);
writeFileSync(gradlePath, gradle, "utf8");
console.log(`版本号 -> ${version} (${nextCode})`);

// 2) version.json（App 更新检查读这个）
const apkName = `lite-schedule-${version}.apk`;
const manifest = {
  version,
  tag: `v${version}`,
  url: `https://github.com/${repo}/releases/tag/v${version}`,
  apk: `https://github.com/${repo}/releases/download/v${version}/${apkName}`,
  notes,
};
writeFileSync(join(root, "version.json"), JSON.stringify(manifest, null, 2) + "\n", "utf8");
console.log("version.json 已更新");

// 3) 编译
sh(`"${gradleBat}" assembleRelease --no-daemon --console=plain`, join(root, "android"));

// 4) 产物
const built = join(root, "android/app/build/outputs/apk/release/app-release.apk");
const outDir = join(root, "build-out");
if (!existsSync(outDir)) mkdirSync(outDir);
const outApk = join(outDir, apkName);
copyFileSync(built, outApk);
console.log(`APK -> ${outApk}`);

// 5) 提交 + tag
sh(`git add -A && git commit -m "release: v${version}" && git tag v${version} && git push && git push origin v${version}`);

// 6) Release
sh(`gh release create v${version} "${outApk}" --title "轻课表 v${version}" --notes "${notes.replace(/"/g, '\\"')}"`);
console.log(`发布完成: https://github.com/${repo}/releases/tag/v${version}`);
